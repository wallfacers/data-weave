package com.dataweave.master.application.lineage.agent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 053 血缘抽取提示构造（契约 C4）。系统指令强调宁缺毋滥、只输出文本真实出现的表（FR-005）。
 * US3 schema 接地：{@link #buildWithSchema} 注入候选表真实列清单作上下文（FR-016，T028）。
 */
public final class LineageExtractionPrompt {

    private LineageExtractionPrompt() {}

    /** 两协议共用：system 指令 + user 内容（脚本/SQL）。 */
    public record LineagePrompt(String system, String user) {}

    private static final String SYSTEM = """
            你是企业数据平台的血缘抽取助手。从给定的脚本或 SQL 中抽取表级读写关系与（可解析时）字段级派生。

            硬性规则（宁缺毋滥）：
            1. 只输出脚本文本中真实出现的表名（大小写不敏感，限定名 schema.table 或裸名 table 均可）。
               严禁输出文本中找不到的表名——这是防幻觉校验，越界表名会被拒收。
            2. 读/写方向必须能从脚本语义明确判断（INSERT/UPDATE/CREATE/MERGE→写，FROM/JOIN→读）；不确定时不输出。
            3. 字段级派生（columnEdges）仅在能明确判断源列→目标列时输出，否则留空。
            4. confidence 为本次抽取的自评置信度，取 0.0~1.0。

            输出结构（严格遵循）：
            {
              "reads": ["读表名"],
              "writes": ["写表名"],
              "columnEdges": [{"srcTable":"","srcColumn":"","dstTable":"","dstColumn":""}],
              "confidence": 0.0
            }
            """;

    /** 无 schema 上下文的标准提示（向后兼容）。 */
    public static LineagePrompt build(String scriptContent, String taskType) {
        String user = "任务类型：" + taskType + "\n\n脚本/SQL 内容：\n" + scriptContent;
        return new LineagePrompt(SYSTEM, user);
    }

    /**
     * 含 schema 接地的提示（US3/FR-016/T028）。
     * 把真实列清单作为上下文注入 system 指令，约束模型字段边必须落在真实列集合内。
     *
     * @param scriptContent 脚本/SQL 内容
     * @param taskType      任务类型（PYTHON/SHELL/SPARK/SQL）
     * @param tableColumns  表名 → 该表真实列名清单（大小写不敏感匹配用）
     * @return 带 schema 接地上下文的提示
     */
    public static LineagePrompt buildWithSchema(String scriptContent, String taskType,
                                                 Map<String, List<String>> tableColumns) {
        StringBuilder sb = new StringBuilder(SYSTEM);
        if (tableColumns != null && !tableColumns.isEmpty()) {
            sb.append("\n\n── 已知表 Schema（仅以下列真实存在；columnEdges 中列名必须严格来自对应表的此集合，越界列会被平台拒收）──\n");
            for (var entry : tableColumns.entrySet()) {
                String cols = entry.getValue().stream()
                        .filter(c -> c != null && !c.isBlank())
                        .collect(Collectors.joining(", "));
                sb.append("  • ").append(entry.getKey()).append(": [").append(cols).append("]\n");
            }
        }
        String user = "任务类型：" + taskType + "\n\n脚本/SQL 内容：\n" + scriptContent;
        return new LineagePrompt(sb.toString(), user);
    }
}
