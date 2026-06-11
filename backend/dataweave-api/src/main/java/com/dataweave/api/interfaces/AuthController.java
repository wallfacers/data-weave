package com.dataweave.api.interfaces;

import com.dataweave.api.application.AuthService;
import com.dataweave.api.application.AuthService.LoginResult;
import com.dataweave.api.application.AuthService.UserInfo;
import com.dataweave.api.infrastructure.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

/**
 * 认证端点：登录、当前用户。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return ApiResponse.err(400, "username 和 password 不能为空");
        }
        try {
            LoginResult result = authService.login(username, password);
            return ApiResponse.ok(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.err(401, e.getMessage());
        }
    }

    @GetMapping("/me")
    public ApiResponse<?> me(ServerWebExchange exchange) {
        Object userIdAttr = exchange.getAttribute("userId");
        if (userIdAttr == null) {
            return ApiResponse.err(401, "未登录");
        }
        Long userId = (Long) userIdAttr;
        try {
            UserInfo info = authService.me(userId);
            return ApiResponse.ok(info);
        } catch (IllegalArgumentException e) {
            return ApiResponse.err(404, e.getMessage());
        }
    }
}
