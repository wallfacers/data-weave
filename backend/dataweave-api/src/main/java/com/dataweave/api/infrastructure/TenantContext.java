package com.dataweave.api.infrastructure;

/**
 * 多租户上下文：基于 Reactor ThreadLocal 存放当前请求的 tenantId / userId / roles。
 * 由 {@link JwtAuthFilter} 在认证成功后写入，下游 Service / Controller 通过静态方法读取。
 */
public final class TenantContext {

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long tenantId, Long userId, String username) {
        TENANT_ID.set(tenantId);
        USER_ID.set(userId);
        USERNAME.set(username);
    }

    public static Long tenantId() {
        return TENANT_ID.get();
    }

    public static Long userId() {
        return USER_ID.get();
    }

    public static String username() {
        return USERNAME.get();
    }

    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        USERNAME.remove();
    }
}
