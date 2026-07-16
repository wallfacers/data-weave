package com.dataweave.api.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等补灌「关键 seed」——修复 docker 旧库缺 data.sql 后续新增 seed 的隐蔽故障。
 *
 * <p>背景：distributed 部署 PG 持久卷 {@code spring.sql.init.mode=never}，schema.sql 靠
 * IF NOT EXISTS 升级表结构，但 data.sql 的 seed 不会在旧库重灌。069/071 等 feature 后续在
 * data.sql 新增的 seed（incident 处置 policy、巡检例程）在旧库上缺失，导致：
 * <ul>
 *   <li>巡检自动处置工具（incident_rerun 等）落入 PolicyEngine 默认 L2 闸门 → 全部 PENDING 不执行，
 *       自动处置闭环被静默阻断（巡检表面在工作，实际没解决问题，最终转人工）；</li>
 *   <li>管家巡检例程表空 → PatrolScheduler 无例程可跑，管家从不履职。</li>
 * </ul>
 *
 * <p>此 reifier 在 CommandLineRunner 阶段（DataSource 就绪、spring.sql.init 已跑完后）幂等补灌这些
 * 关键 seed：已存在（deleted=0）则跳过，缺失才补。新库（mode=always 已灌全 data.sql）零补灌，
 * 旧库（mode=never 缺后续 seed）自动修复。
 *
 * <p>维护规则：新增「feature 后加、旧库可能缺」的关键 seed 时，在此追加 reify 方法；与 {@code data.sql}
 * 对应行的 id 在注释标注，改其一务必同步另一处。幂等跨方言（CURRENT_TIMESTAMP + count 存在性判断）。
 */
@Component
public class CriticalSeedReifier implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CriticalSeedReifier.class);

    private final JdbcTemplate jdbc;

    public CriticalSeedReifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        int rules = reifyPolicyRules();
        int routines = reifyPatrolRoutines();
        if (rules > 0 || routines > 0) {
            log.info("[CriticalSeedReifier] 旧库 seed 修复：补灌 policy_rules {} 条、patrol_routine {} 条",
                    rules, routines);
        }
    }

    /**
     * 069 智能运维处置工具 + 071 管家打断工具的 policy 级别（对应 data.sql id 56-62）。
     * 缺失会导致 incident 自动处置被默认 L2 闸门阻断。按 (match_type, pattern) 幂等。
     */
    private int reifyPolicyRules() {
        int n = 0;
        n += reifyRule("TOOL", "incident_rerun",             "L1", "智能运维自动重跑（瞬态故障自愈）",        20); // data.sql 56
        n += reifyRule("TOOL", "incident_adjust_resources",  "L1", "智能运维调资源后重跑（护栏内自愈）",      20); // 57
        n += reifyRule("TOOL", "incident_resume_checkpoint", "L1", "智能运维检查点续跑（实时任务自愈）",      20); // 58
        n += reifyRule("TOOL", "incident_reverify",          "L1", "智能运维复验（人工处理后触发）",          20); // 59
        n += reifyRule("TOOL", "incident_publish_fix",       "L3", "智能运维发布代码修复（需人审确认）",      30); // 60
        n += reifyRule("TOOL", "incident_agent_cancel",      "L0", "打断事故 Agent 输出轮次（直执行+留痕）",  10); // 61
        n += reifyRule("TOOL", "companion_chat_cancel",      "L0", "打断管家会话流式输出（直执行+留痕）",    10); // 62
        return n;
    }

    private int reifyRule(String matchType, String pattern, String level, String desc, int sortOrder) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM policy_rules WHERE match_type = ? AND pattern = ? AND deleted = 0",
                Integer.class, matchType, pattern);
        if (exists != null && exists > 0) {
            return 0;
        }
        jdbc.update("INSERT INTO policy_rules (match_type, pattern, condition_expr, base_level, description, "
                + "enabled, sort_order, created_by, updated_by, created_at, updated_at, deleted, version) "
                + "VALUES (?, ?, NULL, ?, ?, 1, ?, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                matchType, pattern, level, desc, sortOrder);
        return 1;
    }

    /**
     * 071 管家四领域默认巡检例程（对应 data.sql id 1-4，tenant=1/project=1）。
     * 缺失会导致管家巡检从不履职。按 (tenant_id, project_id, domain) 幂等。
     */
    private int reifyPatrolRoutines() {
        int n = 0;
        n += reifyRoutine("TASK_FAILURE", "0 */15 * * * *"); // data.sql 1
        n += reifyRoutine("MACHINE",      "0 */30 * * * *"); // 2
        n += reifyRoutine("DATA_QUALITY", "0 0 * * * *");    // 3
        n += reifyRoutine("CODE_QUALITY", "0 0 2 * * *");    // 4
        return n;
    }

    private int reifyRoutine(String domain, String cron) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM patrol_routine WHERE tenant_id = 1 AND project_id = 1 AND domain = ? AND deleted = 0",
                Integer.class, domain);
        if (exists != null && exists > 0) {
            return 0;
        }
        jdbc.update("INSERT INTO patrol_routine (tenant_id, project_id, domain, enabled, cron_expression, scope_json, "
                + "timeout_seconds, created_by, updated_by, created_at, updated_at, deleted, version) "
                + "VALUES (1, 1, ?, 1, ?, NULL, 120, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                domain, cron);
        return 1;
    }
}
