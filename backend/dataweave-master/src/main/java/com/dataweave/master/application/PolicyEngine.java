package com.dataweave.master.application;

import com.dataweave.master.domain.PolicyRule;
import com.dataweave.master.domain.PolicyRuleRepository;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 副作用分级引擎（细闸）。数据驱动（policy_rules）定基础等级，再按资源归属、环境、数量阈值与命令注入抬升。
 *
 * <p>分级维度 = 爆炸半径 × 可逆性 × 资源归属 × 环境（design D3）。归属/环境判定只有平台做得到，故放这里。
 * {@link #decide} 为纯函数（除可选的归属 DB 解析外不产生副作用），便于单测分级矩阵。
 *
 * <p>裁决理由（{@code reasons.add}）经 {@link Messages} 按 UI locale 本地化，供闸门回显与审计留痕；
 * 调用方可经 {@link #decide(ActionRequest, Locale)} 显式传入 locale，缺省走中文默认（{@link Messages#DEFAULT_LOCALE}）。
 */
@Service
public class PolicyEngine {

    private final PolicyRuleRepository ruleRepository;
    private final TaskInstanceRepository instanceRepository;
    private final TaskDefRepository taskDefRepository;
    private final Messages messages;
    private final String defaultEnvironment;
    private final int batchThreshold;

    public PolicyEngine(PolicyRuleRepository ruleRepository,
                        TaskInstanceRepository instanceRepository,
                        TaskDefRepository taskDefRepository,
                        Messages messages,
                        @Value("${policy.environment:dev}") String defaultEnvironment,
                        @Value("${policy.batch-threshold:50}") int batchThreshold) {
        this.ruleRepository = ruleRepository;
        this.instanceRepository = instanceRepository;
        this.taskDefRepository = taskDefRepository;
        this.messages = messages;
        this.defaultEnvironment = defaultEnvironment;
        this.batchThreshold = batchThreshold;
    }

    /** 默认 locale（中文）裁决：便于内部/测试无 locale 上下文调用。 */
    public PolicyDecision decide(ActionRequest req) {
        return decide(req, Messages.DEFAULT_LOCALE);
    }

    /** 按指定 locale 本地化裁决理由（UI 场景传 UI locale，Agent 场景传 agent locale）。 */
    public PolicyDecision decide(ActionRequest req, Locale locale) {
        List<String> reasons = new ArrayList<>();
        List<PolicyRule> rules = ruleRepository.findByEnabledOrderBySortOrderAscIdAsc(1);

        // command 仅在 node_exec/shell 路径作为命令串解析；其余动作的 command 为不透明执行负载
        boolean shellPath = req.command() != null && !req.command().isBlank()
                && (req.toolName() == null
                    || "node_exec".equals(req.toolName())
                    || "NODE_EXEC".equalsIgnoreCase(req.actionType()));

        // 1) 基础等级
        BaseMatch base = baseLevel(req, rules, shellPath, locale);
        PolicyLevel level = base.level;
        reasons.add(messages.get("policy.reason.base_level", locale, level, base.reason));

        // L4 永久拒绝：禁止项优先，不再抬升
        if (level == PolicyLevel.L4) {
            return new PolicyDecision(PolicyLevel.L4, PolicyDecision.Outcome.REJECTED, false, false, reasons);
        }

        // 2) 命令注入解析（重定向/分隔/子命令 → 至少 L2；管道不升级）
        boolean injection = false;
        if (shellPath) {
            injection = hasInjection(req.command());
            if (injection) {
                PolicyLevel bumped = level.max(PolicyLevel.L2);
                if (bumped != level) {
                    reasons.add(messages.get("policy.reason.injection", locale, bumped));
                }
                level = bumped;
            }
        }

        // 3) 资源归属抬升：非本平台资源的 L1 → L2
        if (level == PolicyLevel.L1) {
            Boolean owned = resolveOwnership(req);
            if (Boolean.FALSE.equals(owned)) {
                level = level.max(PolicyLevel.L2);
                reasons.add(messages.get("policy.reason.foreign", locale));
            }
        }

        // 4) 环境抬升：prod 环境的 L1 → L2
        String env = req.environment() != null ? req.environment() : defaultEnvironment;
        if (level == PolicyLevel.L1 && "prod".equalsIgnoreCase(env)) {
            level = level.max(PolicyLevel.L2);
            reasons.add(messages.get("policy.reason.prod_env", locale));
        }

        // 5) 数量阈值抬升：批量 > N 的 L1 → L2
        if (level == PolicyLevel.L1 && req.batchCount() > batchThreshold) {
            level = level.max(PolicyLevel.L2);
            reasons.add(messages.get("policy.reason.batch_over", locale, req.batchCount(), batchThreshold));
        }

        boolean requiresConfirmation = level == PolicyLevel.L3;
        return new PolicyDecision(level, PolicyDecision.outcomeFor(level),
                requiresConfirmation, injection, reasons);
    }

    // ---- 基础等级：shell 路径走 CMD_PREFIX，否则 toolName 走 TOOL ----
    private BaseMatch baseLevel(ActionRequest req, List<PolicyRule> rules, boolean shellPath, Locale locale) {
        if (shellPath) {
            String first = firstCommand(req.command());
            // 规则已按 sort_order 升序：禁止项（小 sort_order）优先匹配
            for (PolicyRule r : rules) {
                if ("CMD_PREFIX".equals(r.getMatchType()) && matchesPrefix(first, r.getPattern())) {
                    return new BaseMatch(PolicyLevel.parse(r.getBaseLevel()),
                            messages.get("policy.reason.base_cmd_prefix", locale, r.getPattern()));
                }
            }
            return new BaseMatch(PolicyLevel.L2, messages.get("policy.reason.base_cmd_no_match", locale));
        }
        if (req.toolName() != null && !req.toolName().isBlank()) {
            for (PolicyRule r : rules) {
                if ("TOOL".equals(r.getMatchType()) && req.toolName().equals(r.getPattern())) {
                    return new BaseMatch(PolicyLevel.parse(r.getBaseLevel()),
                            messages.get("policy.reason.base_tool_hit", locale, r.getPattern()));
                }
            }
            return new BaseMatch(PolicyLevel.L2, messages.get("policy.reason.base_tool_no_match", locale));
        }
        return new BaseMatch(PolicyLevel.L2, messages.get("policy.reason.base_no_tool", locale));
    }

    /** 取命令串首个命令（管道/注入前），用于前缀匹配。 */
    private String firstCommand(String command) {
        String c = command.trim();
        // 在首个管道/分隔符处截断，仅取首命令片段
        int cut = c.length();
        for (String sep : new String[]{"|", ";", "&&", "||", ">", "<", "$(", "`"}) {
            int idx = c.indexOf(sep);
            if (idx >= 0) {
                cut = Math.min(cut, idx);
            }
        }
        return c.substring(0, cut).trim();
    }

    /** 命令前缀匹配：按词边界，避免 "dfx" 命中 "df"。 */
    private boolean matchesPrefix(String firstCommand, String pattern) {
        String fc = firstCommand.trim();
        String p = pattern.trim();
        return fc.equals(p) || fc.startsWith(p + " ");
    }

    /** 注入检测：重定向 > >>、命令分隔 ; && ||、子命令 $() 反引号 → true；单纯管道 | 不算。 */
    private boolean hasInjection(String command) {
        String c = command;
        if (c.contains(">") || c.contains("<")) {
            return true;
        }
        if (c.contains(";") || c.contains("&&") || c.contains("||")) {
            return true;
        }
        if (c.contains("$(") || c.contains("`")) {
            return true;
        }
        // 后台执行 & （排除 && 已在上面处理）
        if (c.matches(".*[^&]&($|[^&]).*")) {
            return true;
        }
        return false;
    }

    /** 归属解析：显式传入优先；否则按 targetType 查库（本平台实例/任务存在 → 归属）。 */
    private Boolean resolveOwnership(ActionRequest req) {
        if (req.ownedByPlatform() != null) {
            return req.ownedByPlatform();
        }
        String type = req.targetType();
        String id = req.targetId();
        if (type == null || id == null) {
            return null;
        }
        // 实例类目标主键为 UUIDv7；任务类仍为 Long。
        if ("TASK_INSTANCE".equalsIgnoreCase(type)) {
            java.util.UUID uuid = parseUuid(id);
            return uuid == null ? null : instanceRepository.findById(uuid).isPresent();
        }
        if ("TASK".equalsIgnoreCase(type)) {
            Long pk = parseLong(id);
            return pk == null ? null : taskDefRepository.findById(pk).isPresent();
        }
        return null;
    }

    private Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private java.util.UUID parseUuid(String s) {
        try {
            return java.util.UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record BaseMatch(PolicyLevel level, String reason) {
    }
}
