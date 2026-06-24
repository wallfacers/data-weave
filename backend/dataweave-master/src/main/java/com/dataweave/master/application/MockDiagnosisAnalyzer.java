package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.i18n.Messages;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 规则式失败根因分析（MVP mock）。
 *
 * <p>规则：① 日志含 OOM/OutOfMemory 或节点内存 ≥90% → 判内存不足，建议加内存/迁移/限权重；
 * ② 节点 load 过高或并发争抢 → 判资源争抢；③ 否则给通用排查建议。
 * 真模型替换点：实现 {@link DiagnosisAnalyzer} 接缝即可，无需改 {@link DiagnosisService}。
 *
 * <p>产出文案（title / rootCause / suggestions.label）按传入的 locale 经 {@link Messages} 本地化，
 * 用户在诊断卡 UI 看到对应语种。
 */
@Component
public class MockDiagnosisAnalyzer implements DiagnosisAnalyzer {

    private final Messages messages;

    public MockDiagnosisAnalyzer(Messages messages) {
        this.messages = messages;
    }

    @Override
    public Analysis analyze(TaskInstance failed, WorkerNode node, TaskDef task, Telemetry telemetry, Locale locale) {
        Telemetry tel = telemetry != null ? telemetry : Telemetry.EMPTY;
        String taskName = task != null ? task.getName()
                : messages.get("diagnosis.common.task_fallback", locale,
                        failed != null ? String.valueOf(failed.getTaskId()) : "?");
        String nodeCode = node != null ? node.getNodeCode()
                : (failed != null && failed.getWorkerNodeCode() != null ? failed.getWorkerNodeCode() : "unknown");
        double mem = node != null && node.getMem() != null ? node.getMem() : 0;
        double cpu = node != null && node.getCpu() != null ? node.getCpu() : 0;
        double load = node != null && node.getLoadAvg() != null ? node.getLoadAvg() : 0;
        // 真采集（live-telemetry）：并发争抢数取 master 端按节点聚合，不再信任 worker 上报。
        int concurrent = tel.concurrentTasks();
        int history7d = tel.failureCount7d();
        String log = failed != null && failed.getLog() != null ? failed.getLog() : "";

        boolean oom = log.toLowerCase().contains("outofmemory") || log.toLowerCase().contains("oom")
                || log.contains("heap space") || mem >= 90;

        String title;
        String rootCause;
        String suggestions;

        if (oom) {
            title = messages.get("diagnosis.oom.title", locale, taskName);
            rootCause = concurrent > 1
                    ? messages.get("diagnosis.oom.root_cause_with_contention", locale, nodeCode, fmt(mem), concurrent)
                    : messages.get("diagnosis.oom.root_cause", locale, nodeCode, fmt(mem));
            suggestions = "[{\"action\":\"RERUN_MORE_MEMORY\",\"label\":\""
                    + messages.get("diagnosis.fix.rerun_more_memory", locale) + "\"},"
                    + "{\"action\":\"MIGRATE_NODE\",\"label\":\""
                    + messages.get("diagnosis.fix.migrate_node", locale) + "\"},"
                    + "{\"action\":\"CAP_NODE_WEIGHT\",\"label\":\""
                    + escapeJson(messages.get("diagnosis.fix.cap_node_weight", locale, nodeCode)) + "\"}]";
        } else if (load >= 6 || concurrent >= 3) {
            title = messages.get("diagnosis.contention.title", locale, taskName);
            rootCause = messages.get("diagnosis.contention.root_cause", locale, nodeCode, fmt(load), concurrent, fmt(cpu));
            suggestions = "[{\"action\":\"MIGRATE_NODE\",\"label\":\""
                    + messages.get("diagnosis.fix.migrate_node", locale) + "\"},"
                    + "{\"action\":\"RERUN\",\"label\":\""
                    + messages.get("diagnosis.fix.rerun_offset", locale) + "\"}]";
        } else {
            title = messages.get("diagnosis.unknown.title", locale, taskName);
            rootCause = messages.get("diagnosis.unknown.root_cause", locale, nodeCode, fmt(mem), fmt(cpu));
            suggestions = "[{\"action\":\"RERUN\",\"label\":\""
                    + messages.get("diagnosis.fix.rerun_in_place", locale) + "\"}]";
        }

        String context = "{\"nodeCode\":\"" + nodeCode + "\",\"nodeMem\":" + fmt(mem)
                + ",\"nodeCpu\":" + fmt(cpu) + ",\"nodeLoad\":" + fmt(load)
                + ",\"concurrentTasks\":" + concurrent
                + ",\"history7d\":" + history7d + "}";

        return new Analysis(title, rootCause, context, suggestions);
    }

    private String fmt(double v) {
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    /** 转义 JSON 字符串值的双引号与反斜杠。 */
    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
