package com.dataweave.api.infrastructure;

import com.dataweave.master.domain.User;
import com.dataweave.master.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时扫描 users 表，将 {@code {plain}xxx} 格式的明文密码编码为 BCrypt。
 * 仅处理以 {@code {plain}} 开头的行，已编码的行不动。
 *
 * <p>种子数据（data.sql）用 {@code {plain}xxx} 标记可读明文，此 initializer 负责在首次启动后
 * 把它们替换为 BCrypt hash，保证生产环境密码安全。
 */
@Component
public class PasswordInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordInitializer.class);
    private static final String PLAIN_PREFIX = "{plain}";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        List<User> users = userRepository.findByTenantId(1L);
        int updated = 0;
        for (User user : users) {
            if (user.getPasswordHash() != null && user.getPasswordHash().startsWith(PLAIN_PREFIX)) {
                String raw = user.getPasswordHash().substring(PLAIN_PREFIX.length());
                user.setPasswordHash(passwordEncoder.encode(raw));
                userRepository.save(user);
                updated++;
            }
        }
        if (updated > 0) {
            log.info("PasswordInitializer: 已将 {} 个用户的明文密码编码为 BCrypt", updated);
        }
    }
}
