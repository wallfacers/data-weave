package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkerNode;
import org.springframework.stereotype.Component;

/**
 * 规则式失败根因分析（MVP mock）。
 *
 * <p>规则：① 日志含 OOM/OutOfMemory 或节点内存 ≥90% → 判内存不足，建议加内存/迁移/限权重；
 * ② 节点 load 过高或并发争抢 → 判资源争抢；③ 否则给通用排查建议。
 * 真模型替换点：实现 {@link DiagnosisAnalyzer} 接缝即可，无需改 {@link DiagnosisService}。
 */
@Component
public class MockDiagnosisAnalyzer implements DiagnosisAnalyzer {

    @Override
    public Analysis analyze(TaskInstance failed, WorkerNode node, TaskDef task) {
        String taskName = task != null ? task.getName() : ("任务#" + (failed != null ? failed.getTaskId() : "?"));
        String nodeCode = node != null ? node.getNodeCode() : (failed != null ? failed.getWorkerNodeCode() : "unknown");
        double mem = node != null && node.getMem() != null ? node.getMem() : 0;
        double cpu = node != null && node.getCpu() != null ? node.getCpu() : 0;
        double load = node != null && node.getLoadAvg() != null ? node.getLoadAvg() : 0;
        int concurrent = node != null && node.getRunningTasks() != null ? node.getRunningTasks() : 0;
        String log = failed != null && failed.getLog() != null ? failed.getLog() : "";

        boolean oom = log.toLowerCase().contains("outofmemory") || log.toLowerCase().contains("oom")
                || log.contains("heap space") || mem >= 90;

        String title;
        String rootCause;
        String suggestions;

        if (oom) {
            title = taskName + " 失败 · 节点内存不足导致 OOM";
            rootCause = nodeCode + " 内存使用率 " + fmt(mem) + "%，任务触发 OutOfMemoryError 被容器终止"
                    + (concurrent > 1 ? ("；同时段该节点并发运行 " + concurrent + " 个任务，存在资源争抢。") : "。");
            suggestions = "[{\"action\":\"RERUN_MORE_MEMORY\",\"label\":\"调大 executor 内存重跑\"},"
                    + "{\"action\":\"MIGRATE_NODE\",\"label\":\"迁移到空闲节点重跑\"},"
                    + "{\"action\":\"CAP_NODE_WEIGHT\",\"label\":\"为 " + nodeCode + " 设置调度权重上限\"}]";
        } else if (load >= 6 || concurrent >= 3) {
            title = taskName + " 失败 · 节点资源争抢";
            rootCause = nodeCode + " 系统 load " + fmt(load) + "、并发 " + concurrent + " 个任务，CPU "
                    + fmt(cpu) + "%，资源争抢导致任务超时/失败。";
            suggestions = "[{\"action\":\"MIGRATE_NODE\",\"label\":\"迁移到空闲节点重跑\"},"
                    + "{\"action\":\"RERUN\",\"label\":\"错峰后原地重跑\"}]";
        } else {
            title = taskName + " 失败 · 待进一步排查";
            rootCause = "未命中已知资源类根因。" + nodeCode + " 资源水位正常（内存 " + fmt(mem) + "%、CPU "
                    + fmt(cpu) + "%）。建议查看任务日志与上游依赖。";
            suggestions = "[{\"action\":\"RERUN\",\"label\":\"原地重跑\"}]";
        }

        String context = "{\"nodeCode\":\"" + nodeCode + "\",\"nodeMem\":" + fmt(mem)
                + ",\"nodeCpu\":" + fmt(cpu) + ",\"nodeLoad\":" + fmt(load)
                + ",\"concurrentTasks\":" + concurrent + "}";

        return new Analysis(title, rootCause, context, suggestions);
    }

    private String fmt(double v) {
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 10) / 10.0);
    }
}
