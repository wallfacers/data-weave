package com.dataweave.master.application;

import com.dataweave.master.domain.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 主动发现调度器：周期性（{@code @Scheduled} 定时兜底）遍历所有 {@link Inspector} Bean 执行巡检，
 * 把产出的候选 {@link Finding} 交 {@link FindingService#recordIfNew} 去重落库。
 *
 * <p>定时兜底是地基（保证不漏）；失败事件可加速触发（见 {@link #onFailureSignal()}），但不替代定时。
 * 接入新巡检器 = 新增一个 {@link Inspector} Bean，Spring 自动注入到 {@code inspectors} 列表，无需改本类。
 */
@Component
public class InspectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(InspectorScheduler.class);

    private final List<Inspector> inspectors;
    private final FindingService findingService;

    public InspectorScheduler(List<Inspector> inspectors, FindingService findingService) {
        this.inspectors = inspectors;
        this.findingService = findingService;
    }

    /** 定时兜底巡检。 */
    @Scheduled(fixedDelayString = "${proactive.inspect.interval-ms:30000}",
            initialDelayString = "${proactive.inspect.initial-ms:15000}")
    public void scheduledScan() {
        runOnce();
    }

    /**
     * 失败事件加速触发：{@link InstanceStateMachine} CAS→FAILED 后发布 {@link TaskInstanceFailedEvent}，
     * 本监听异步立刻巡检一轮，无需等定时兜底。异步竞态（FAILED 行尚不可见）由下一轮定时兜底补上。
     */
    @Async
    @EventListener
    public void onTaskInstanceFailed(TaskInstanceFailedEvent event) {
        runOnce();
    }

    /**
     * 跑一轮：遍历所有巡检器，去重落库，返回**本轮新建**的 Finding（供主动推送/播报）。
     * 单个巡检器抛错只跳过它，不影响其余巡检器。
     */
    public List<Finding> runOnce() {
        List<Finding> created = new ArrayList<>();
        for (Inspector inspector : inspectors) {
            List<Finding> candidates;
            try {
                candidates = inspector.inspect();
            } catch (RuntimeException e) {
                log.warn("inspector {} 巡检失败，跳过: {}", inspector.source(), e.toString());
                continue;
            }
            for (Finding candidate : candidates) {
                findingService.recordIfNew(candidate).ifPresent(created::add);
            }
        }
        if (!created.isEmpty()) {
            log.info("主动发现：本轮新建 {} 条 Finding", created.size());
        }
        return created;
    }
}
