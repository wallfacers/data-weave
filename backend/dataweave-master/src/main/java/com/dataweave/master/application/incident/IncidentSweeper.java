package com.dataweave.master.application.incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.domain.incident.IncidentEvent;
import com.dataweave.master.domain.incident.IncidentStates;
import com.dataweave.master.domain.incident.MessageKinds;
import com.dataweave.master.infrastructure.incident.IncidentMessageRepository;
import com.dataweave.master.infrastructure.incident.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 069 巡检开单器（US1/FR-001/FR-015）：周期扫描 FAILED/SUSPENDED 实例，自动开单或归并进既有未收口事故；
 * 对调度内核零侵入——纯只读观察 task_instance，不改状态机/CAS/锁序（守 SC-008 红线）。
 * 多 master 防重靠 {@link IncidentRepository#tryOpen} 的 {@code UNIQUE(tenant_id, open_key)} 单赢，
 * 同任务重复失败的幂等归并靠 {@code incident_instance} 关联表（不用 SKIP LOCKED，属乐观 CAS 单赢模式，同既有巡检器惯例）。
 */
@Service
public class IncidentSweeper {

    private static final Logger log = LoggerFactory.getLogger(IncidentSweeper.class);
    private static final int SCAN_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final IncidentRepository incidentRepo;
    private final IncidentMessageRepository messageRepo;
    private final IncidentEventPublisher publisher;
    private final IncidentAgentService agentService;
    private final ExecutorService diagnosisPool;
    private final Semaphore stormGate;

    public IncidentSweeper(JdbcTemplate jdbc, IncidentRepository incidentRepo, IncidentMessageRepository messageRepo,
                            IncidentEventPublisher publisher, IncidentAgentService agentService,
                            @Value("${ops.incident.llm-pool-size:2}") int poolSize,
                            @Value("${ops.incident.storm-max-inflight:5}") int stormMaxInflight) {
        this.jdbc = jdbc;
        this.incidentRepo = incidentRepo;
        this.messageRepo = messageRepo;
        this.publisher = publisher;
        this.agentService = agentService;
        this.diagnosisPool = Executors.newFixedThreadPool(Math.max(1, poolSize),
                r -> new Thread(r, "incident-agent"));
        this.stormGate = new Semaphore(Math.max(1, stormMaxInflight));
    }

    private record Candidate(UUID instanceId, long tenantId, long projectId, long taskId, String taskDefName,
                              String cronExpression, Boolean longRunning) {
    }

    @Scheduled(fixedDelayString = "${ops.incident.sweep-interval-ms:30000}")
    public void sweep() {
        // 先重试尚停留在 OPEN 的既有事故（上轮风暴限流未提交成功的诊断），CAS 认领天然去重不会重复外呼
        for (Incident pending : incidentRepo.findAllByState(IncidentStates.OPEN)) {
            submitDiagnosis(pending.id());
        }
        // 069 T021/T022：追踪处置后 latest_instance 终态——成功收口/失败进入下一梯度处置或升级人工
        for (Incident acting : incidentRepo.findAllByState(IncidentStates.ACTING)) {
            submitAction(acting.id());
        }

        List<Candidate> candidates;
        try {
            candidates = jdbc.query(
                    "SELECT id, tenant_id, project_id, task_id, task_def_name, cron_expression, long_running " +
                    "FROM task_instance WHERE state IN ('FAILED','SUSPENDED') " +
                    "AND run_mode NOT IN ('TEST','BACKFILL') AND task_id IS NOT NULL " +
                    "ORDER BY id DESC LIMIT ?",
                    (rs, n) -> new Candidate(
                            (UUID) rs.getObject("id"), rs.getLong("tenant_id"), rs.getLong("project_id"),
                            rs.getLong("task_id"), rs.getString("task_def_name"),
                            rs.getString("cron_expression"), rs.getObject("long_running", Boolean.class)),
                    SCAN_LIMIT);
        } catch (Exception e) {
            log.warn("[IncidentSweeper] candidate scan failed: {}", e.toString());
            return;
        }

        for (Candidate c : candidates) {
            try {
                processCandidate(c);
            } catch (Exception e) {
                log.warn("[IncidentSweeper] candidate processing failed instanceId={}: {}", c.instanceId(), e.toString());
            }
        }
    }

    private void processCandidate(Candidate c) {
        String triggerSource = Boolean.TRUE.equals(c.longRunning())
                ? "STREAMING"
                : (c.cronExpression() != null && !c.cronExpression().isBlank() ? "CRON" : "MANUAL");

        Optional<Incident> opened = incidentRepo.tryOpen(
                c.tenantId(), c.projectId(), c.taskId(), c.taskDefName(), c.instanceId(), triggerSource);
        if (opened.isPresent()) {
            Incident inc = opened.get();
            var msg = messageRepo.append(inc.id(), MessageKinds.SYSTEM,
                    "事故已开立（失败实例 " + c.instanceId() + "）", null, "system");
            publisher.publish(c.projectId(), new IncidentEvent.MessageAppended(inc.id(), msg));
            publisher.publish(c.projectId(), new IncidentEvent.IncidentChanged(inc));
            submitDiagnosis(inc.id());
            return;
        }

        Optional<Incident> existing = incidentRepo.findOpenByTask(c.tenantId(), c.taskId());
        if (existing.isEmpty()) return; // 竞态：已被收口，跳过
        boolean newLink = incidentRepo.linkInstance(existing.get().id(), c.instanceId());
        if (!newLink) return; // 此实例已处理过（幂等），跳过——避免每轮重扫误重复归并

        incidentRepo.mergeInstance(c.tenantId(), c.taskId(), c.instanceId());
        Incident merged = incidentRepo.findById(existing.get().id()).orElse(existing.get());
        var msg = messageRepo.append(merged.id(), MessageKinds.SYSTEM,
                "归并新的失败实例 " + c.instanceId(), null, "system");
        publisher.publish(c.projectId(), new IncidentEvent.MessageAppended(merged.id(), msg));
        publisher.publish(c.projectId(), new IncidentEvent.IncidentChanged(merged));
        submitDiagnosis(merged.id());
    }

    private void submitDiagnosis(UUID incidentId) {
        if (!stormGate.tryAcquire()) {
            log.info("[IncidentSweeper] storm gate saturated, incident {} deferred to next sweep", incidentId);
            return;
        }
        diagnosisPool.submit(() -> {
            try {
                agentService.diagnose(incidentId);
            } catch (Exception e) {
                log.warn("[IncidentSweeper] diagnose failed incidentId={}: {}", incidentId, e.toString());
            } finally {
                stormGate.release();
            }
        });
    }

    /** 069 T021/T022：ACTING 事故验证/下一步处置，复用同一风暴闸门+线程池（诊断与处置互斥竞争，行为符合预期）。 */
    private void submitAction(UUID incidentId) {
        if (!stormGate.tryAcquire()) {
            log.info("[IncidentSweeper] storm gate saturated, incident {} action deferred to next sweep", incidentId);
            return;
        }
        diagnosisPool.submit(() -> {
            try {
                agentService.actOrVerify(incidentId);
            } catch (Exception e) {
                log.warn("[IncidentSweeper] actOrVerify failed incidentId={}: {}", incidentId, e.toString());
            } finally {
                stormGate.release();
            }
        });
    }
}
