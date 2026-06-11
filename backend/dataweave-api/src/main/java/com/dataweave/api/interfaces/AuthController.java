package com.dataweave.api.interfaces;

import com.dataweave.api.application.AuthService;
import com.dataweave.api.application.AuthService.LoginResult;
import com.dataweave.api.application.AuthService.UserInfo;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "username 和 password 不能为空"));
        }
        try {
            LoginResult result = authService.login(username, password);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(ServerWebExchange exchange) {
        Object userIdAttr = exchange.getAttribute("userId");
        if (userIdAttr == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        Long userId = (Long) userIdAttr;
        try {
            UserInfo info = authService.me(userId);
            return ResponseEntity.ok(info);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
