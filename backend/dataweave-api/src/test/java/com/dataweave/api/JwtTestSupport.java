package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import java.util.List;

/**
 * 全栈 HTTP 测试的 JWT 鉴权辅助。
 *
 * <p>{@code JwtAuthFilter} 拦截所有非白名单 {@code /api/**}，全栈 {@code @SpringBootTest}
 * 的 {@code WebTestClient} 须带有效 Bearer JWT 才能过闸。{@code JwtUtil.parse} 仅验签名 + 过期、
 * 不查库，故测试用同一 {@code JwtUtil} bean（同配置 secret）签发一枚 token 即可，无需 seed 用户。
 *
 * <p>身份固定 userId=1 / tenantId=1（对齐种子 admin），角色给全集，避免授权分级误判。
 */
public final class JwtTestSupport {

    public static final long USER_ID = 1L;
    public static final long TENANT_ID = 1L;
    public static final String USERNAME = "tester";

    private JwtTestSupport() {
    }

    /** 用注入的 {@link JwtUtil} 签发测试 token，返回完整的 {@code Bearer xxx} 头值。 */
    public static String bearer(JwtUtil jwtUtil) {
        String token = jwtUtil.generate(USER_ID, TENANT_ID, USERNAME, List.of("ADMIN", "OWNER"));
        return "Bearer " + token;
    }
}
