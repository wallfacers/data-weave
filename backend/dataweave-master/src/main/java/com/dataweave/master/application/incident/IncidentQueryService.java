package com.dataweave.master.application.incident;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.dataweave.master.application.OpsContracts.PageResult;
import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.domain.incident.IncidentMessage;
import com.dataweave.master.domain.incident.IncidentProposal;
import com.dataweave.master.domain.incident.IncidentStats;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.infrastructure.incident.IncidentMessageRepository;
import com.dataweave.master.infrastructure.incident.IncidentProposalRepository;
import com.dataweave.master.infrastructure.incident.IncidentRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 069 事故只读查询面（US1/FR-011 数据来源，US4 指挥中心复用同一服务）。
 * 项目隔离由调用方（Controller）经 ProjectScope 校验后传入 projectId；本层再做归属核验防越权直取。
 */
@Service
public class IncidentQueryService {

    private final IncidentRepository incidentRepo;
    private final IncidentMessageRepository messageRepo;
    private final IncidentProposalRepository proposalRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IncidentQueryService(IncidentRepository incidentRepo, IncidentMessageRepository messageRepo,
                                 IncidentProposalRepository proposalRepo) {
        this.incidentRepo = incidentRepo;
        this.messageRepo = messageRepo;
        this.proposalRepo = proposalRepo;
    }

    /** 指挥中心快照（SSE 建连首帧）：全部未收口事故 + 实时数字（SC-010 数字唯一由 {@link #stats} 现算）。 */
    public record Snapshot(List<Incident> incidents, IncidentStats stats) {
    }

    public Snapshot snapshot(long tenantId, long projectId) {
        return new Snapshot(incidentRepo.findOpenByProject(tenantId, projectId), stats(tenantId, projectId));
    }

    /** 实时数字（SC-010 唯一权威来源）：快照/播报接口共用，永远直算 incident 表当下事实。 */
    public IncidentStats stats(long tenantId, long projectId) {
        return incidentRepo.stats(tenantId, projectId, LocalDate.now().atStartOfDay());
    }

    public String statsJson(IncidentStats stats) {
        try {
            return objectMapper.writeValueAsString(stats);
        } catch (Exception e) {
            return "{}";
        }
    }

    public PageResult<Incident> list(long tenantId, long projectId, List<String> states, Long taskDefId,
                                      int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(200, size));
        List<Incident> items = incidentRepo.findPage(tenantId, projectId, states, taskDefId, (p - 1) * s, s);
        return new PageResult<>(items, items.size(), p, s);
    }

    public record Detail(Incident incident, List<IncidentProposal> proposals, long messageCount) {
    }

    public Detail detail(long tenantId, long projectId, UUID id) {
        Incident inc = incidentRepo.findById(id)
                .filter(i -> i.tenantId() == tenantId && i.projectId() == projectId)
                .orElseThrow(() -> new BizException("incident.not_found", id));
        List<IncidentProposal> proposals = proposalRepo.findByIncident(id);
        long count = messageRepo.countByIncident(id);
        return new Detail(inc, proposals, count);
    }

    public List<IncidentMessage> messages(long tenantId, long projectId, UUID id, long afterSeq, int limit) {
        // 归属核验：非本项目事故一律视为不存在（防越权枚举）
        incidentRepo.findById(id)
                .filter(i -> i.tenantId() == tenantId && i.projectId() == projectId)
                .orElseThrow(() -> new BizException("incident.not_found", id));
        int l = Math.max(1, Math.min(1000, limit));
        return messageRepo.findAfter(id, Math.max(0, afterSeq), l);
    }
}
