package com.dataweave.master.application.incident;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 确定性凭据故障前置指纹（US1/research R4）：日志命中典型鉴权失败模式即直接判 CONFIG_CREDENTIAL，
 * 免 LLM 误判、零成本、零延迟——对齐方向文档「playbook 打底、LLM 兜长尾」的成本分层思想（唯一硬指纹）。
 */
public final class CredentialFingerprint {

    private static final List<Pattern> PATTERNS = List.of(
            // 通用鉴权失败措辞（英文，覆盖多数 JDBC 驱动/HTTP 客户端错误消息）
            Pattern.compile("(?i)authentication\\s+failed"),
            Pattern.compile("(?i)access\\s+denied\\s+for\\s+user"),
            Pattern.compile("(?i)invalid\\s+(username|password|credentials)"),
            Pattern.compile("(?i)login\\s+failed\\s+for\\s+user"),
            Pattern.compile("(?i)incorrect\\s+password"),
            Pattern.compile("(?i)authentication\\s+error"),
            Pattern.compile("(?i)unauthorized(?!.{0,20}resource)"),
            Pattern.compile("(?i)\\b401\\b.{0,40}(unauthorized|auth)"),
            Pattern.compile("(?i)permission\\s+denied.{0,40}(user|role|password)"),
            // 常见数据库驱动特征码
            Pattern.compile("(?i)ORA-01017"),                       // Oracle: invalid username/password
            Pattern.compile("(?i)ERROR\\s+1045.{0,20}Access\\s+denied"), // MySQL
            Pattern.compile("(?i)FATAL:.{0,40}password\\s+authentication\\s+failed"), // PostgreSQL
            // 中文常见措辞（部分中文驱动/网关错误消息）
            Pattern.compile("用户名或密码错误"),
            Pattern.compile("鉴权失败"),
            Pattern.compile("认证失败")
    );

    private CredentialFingerprint() {
    }

    /** 日志文本命中任一确定性凭据故障模式即返回 true。 */
    public static boolean matches(String logText) {
        if (logText == null || logText.isEmpty()) return false;
        for (Pattern p : PATTERNS) {
            if (p.matcher(logText).find()) {
                return true;
            }
        }
        return false;
    }
}
