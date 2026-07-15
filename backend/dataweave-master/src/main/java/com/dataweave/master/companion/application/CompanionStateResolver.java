package com.dataweave.master.companion.application;

import java.util.concurrent.ConcurrentHashMap;

import com.dataweave.master.companion.domain.CompanionEvent;
import com.dataweave.master.companion.domain.CompanionStates;
import com.dataweave.master.companion.infrastructure.JdbcPatrolReportRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolRunRepository;
import org.springframework.stereotype.Component;

/**
 * 管家形态归一（T010）。服务端按优先级 {@code speak > think > alert > patrol > idle} 现算形态
 * （data-model 派生状态节）；前端只渲染不推断，保证多客户端一致（边界用例：多人/多标签）。
 *
 * <ul>
 *   <li>{@code speak}/{@code think} ← {@link CompanionTurnRegistry}（活跃 brain turn）</li>
 *   <li>{@code alert} ← 存在未关闭异常汇报（DANGER/WARN）</li>
 *   <li>{@code patrol} ← 存在 RUNNING 的 patrol_run</li>
 *   <li>{@code idle} ← 其余</li>
 * </ul>
 * {@link #resolveAndNotify} 缓存上次形态，变更时经 {@link CompanionEventPublisher} 发 {@code state} 事件。
 */
@Component
public class CompanionStateResolver {

    private final JdbcPatrolReportRepository reportRepo;
    private final JdbcPatrolRunRepository runRepo;
    private final CompanionTurnRegistry turnRegistry;
    private final CompanionEventPublisher publisher;
    private final ConcurrentHashMap<Long, String> lastState = new ConcurrentHashMap<>();

    public CompanionStateResolver(JdbcPatrolReportRepository reportRepo, JdbcPatrolRunRepository runRepo,
                                  CompanionTurnRegistry turnRegistry, CompanionEventPublisher publisher) {
        this.reportRepo = reportRepo;
        this.runRepo = runRepo;
        this.turnRegistry = turnRegistry;
        this.publisher = publisher;
    }

    /** 现算当前形态（不发事件）。 */
    public String resolve(long tenantId, long projectId) {
        String phase = turnRegistry.phase(projectId);
        if (CompanionStates.SPEAK.equals(phase)) return CompanionStates.SPEAK;
        if (CompanionStates.THINK.equals(phase)) return CompanionStates.THINK;
        if (reportRepo.existsOpenAnomaly(tenantId, projectId)) return CompanionStates.ALERT;
        if (runRepo.existsRunningInProject(tenantId, projectId)) return CompanionStates.PATROL;
        return CompanionStates.IDLE;
    }

    /**
     * 现算形态并按需发 {@code state} 事件（形态变化才发）。返回当前形态。
     * 由巡检起停/汇报增删/对话轮次起止等状态变更点调用。
     */
    public String resolveAndNotify(long tenantId, long projectId) {
        String state = resolve(tenantId, projectId);
        String prev = lastState.put(projectId, state);
        if (prev == null || !prev.equals(state)) {
            publisher.publish(projectId, new CompanionEvent.StateChanged(state, reasonOf(state)));
        }
        return state;
    }

    private static String reasonOf(String state) {
        return switch (state) {
            case CompanionStates.SPEAK -> "brain streaming";
            case CompanionStates.THINK -> "brain thinking";
            case CompanionStates.ALERT -> "open anomaly";
            case CompanionStates.PATROL -> "patrol running";
            default -> "all clear";
        };
    }
}
