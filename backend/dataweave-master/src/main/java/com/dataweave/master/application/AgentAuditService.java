package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AgentActionRepository;
import com.dataweave.master.domain.AgentRun;
import com.dataweave.master.domain.AgentRunRepository;
import com.dataweave.master.domain.AgentSession;
import com.dataweave.master.domain.AgentSessionRepository;
import com.dataweave.master.domain.AgentStep;
import com.dataweave.master.domain.AgentStepRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Agent 审计落库与回放服务。审计四表（session/run/step/action）的写入与按 run/session 的回放查询。
 *
 * <p>两模式共用：mock 模式由 AguiOrchestrator 调用记一条 run + 若干 step；workhorse 模式由桥接层消费 SSE 写入。
 */
@Service
public class AgentAuditService {

    private final AgentSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final AgentActionRepository actionRepository;

    public AgentAuditService(AgentSessionRepository sessionRepository,
                             AgentRunRepository runRepository,
                             AgentStepRepository stepRepository,
                             AgentActionRepository actionRepository) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.actionRepository = actionRepository;
    }

    /** 按 conversationId 取或建会话；workhorseSessionId 可后补。 */
    public AgentSession getOrCreateSession(String conversationId, String mode, String workhorseSessionId) {
        Optional<AgentSession> existing = sessionRepository.findFirstByConversationIdOrderByIdDesc(conversationId);
        if (existing.isPresent()) {
            AgentSession s = existing.get();
            if (workhorseSessionId != null && s.getWorkhorseSessionId() == null) {
                s.setWorkhorseSessionId(workhorseSessionId);
                s.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(s);
            }
            return s;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentSession s = new AgentSession();
        s.setTenantId(1L);
        s.setProjectId(1L);
        s.setConversationId(conversationId);
        s.setWorkhorseSessionId(workhorseSessionId);
        s.setMode(mode);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        s.setDeleted(0);
        s.setVersion(0);
        return sessionRepository.save(s);
    }

    public AgentRun startRun(Long sessionId, String runKey, String triggerType, String userMessage) {
        LocalDateTime now = LocalDateTime.now();
        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setRunKey(runKey);
        run.setTriggerType(triggerType);
        run.setUserMessage(truncate(userMessage, 4000));
        run.setState("RUNNING");
        run.setStartedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return runRepository.save(run);
    }

    public void finishRun(Long runId, String state) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setState(state);
            run.setFinishedAt(LocalDateTime.now());
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
        });
    }

    public Optional<AgentStep> getStep(Long stepId) {
        return stepRepository.findById(stepId);
    }

    public AgentStep recordStep(AgentStep step) {
        LocalDateTime now = LocalDateTime.now();
        if (step.getCreatedAt() == null) {
            step.setCreatedAt(now);
        }
        step.setUpdatedAt(now);
        return stepRepository.save(step);
    }

    public AgentAction recordAction(AgentAction action) {
        LocalDateTime now = LocalDateTime.now();
        if (action.getCreatedAt() == null) {
            action.setCreatedAt(now);
        }
        action.setUpdatedAt(now);
        return actionRepository.save(action);
    }

    /** 按 run 回放：时间有序的 step 与 action。 */
    public RunReplay replayRun(Long runId) {
        AgentRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return null;
        }
        List<AgentStep> steps = stepRepository.findByRunIdOrderBySeqAsc(runId);
        List<Long> stepIds = steps.stream().map(AgentStep::getId).toList();
        List<AgentAction> actions = stepIds.isEmpty()
                ? Collections.emptyList()
                : actionRepository.findByStepIdInOrderByIdAsc(stepIds);
        return new RunReplay(run, steps, actions);
    }

    /** 按会话回放：该对话的全部 run 及其 step/action。 */
    public List<RunReplay> replaySession(Long sessionId) {
        return runRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(run -> replayRun(run.getId()))
                .toList();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 单次运行的完整回放视图。
     *
     * @param run     运行记录
     * @param steps   时间有序步骤序列
     * @param actions 该运行所有步骤关联的副作用操作
     */
    public record RunReplay(AgentRun run, List<AgentStep> steps, List<AgentAction> actions) {
    }
}
