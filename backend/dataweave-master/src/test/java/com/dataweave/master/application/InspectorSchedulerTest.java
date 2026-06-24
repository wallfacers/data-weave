package com.dataweave.master.application;

import com.dataweave.master.domain.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主动发现调度：遍历所有 Inspector（含新增的第二个，验证可插拔）、去重落库收集新建、单个巡检器抛错隔离。
 */
class InspectorSchedulerTest {

    private Finding f(String source, String targetId) {
        Finding x = new Finding();
        x.setSource(source);
        x.setTargetType("TASK_INSTANCE");
        x.setTargetId(targetId);
        return x;
    }

    private Inspector stub(String source, List<Finding> out) {
        return new Inspector() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public List<Finding> inspect() {
                return out;
            }
        };
    }

    @Test
    void runOnce_iteratesAllInspectors_andCollectsOnlyNewlyRecorded() {
        FindingService findingService = mock(FindingService.class);
        Finding a = f("TASK_FAILURE", "inst-a");
        Finding b = f("DATA_QUALITY", "tbl-b");
        // a 新建、b 命中去重（返回 empty）
        when(findingService.recordIfNew(a)).thenReturn(Optional.of(a));
        when(findingService.recordIfNew(b)).thenReturn(Optional.empty());

        // 两个 Inspector：第二个 DATA_QUALITY 即"新增巡检器自动纳入"的体现
        InspectorScheduler scheduler = new InspectorScheduler(
                List.of(stub("TASK_FAILURE", List.of(a)), stub("DATA_QUALITY", List.of(b))),
                findingService);

        List<Finding> created = scheduler.runOnce();

        assertThat(created).containsExactly(a);
    }

    @Test
    void runOnce_isolatesThrowingInspector_othersStillRun() {
        FindingService findingService = mock(FindingService.class);
        Finding good = f("TASK_FAILURE", "inst-ok");
        when(findingService.recordIfNew(good)).thenReturn(Optional.of(good));

        Inspector boom = new Inspector() {
            @Override
            public String source() {
                return "BOOM";
            }

            @Override
            public List<Finding> inspect() {
                throw new IllegalStateException("inspector exploded");
            }
        };

        InspectorScheduler scheduler = new InspectorScheduler(
                List.of(boom, stub("TASK_FAILURE", List.of(good))),
                findingService);

        List<Finding> created = scheduler.runOnce();

        assertThat(created).containsExactly(good);
    }
}
