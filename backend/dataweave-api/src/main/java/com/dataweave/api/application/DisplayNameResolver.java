package com.dataweave.api.application;

import java.util.concurrent.ConcurrentHashMap;

import com.dataweave.master.domain.User;
import com.dataweave.master.domain.UserRepository;
import org.springframework.stereotype.Service;

/**
 * 070：把登录用户标识解析为显示名（displayName）。JWT/TenantContext 只带 username，displayName 仅存 users 表，
 * 故按 userId 查库并进程内缓存（短 TTL——改名后最多滞后 TTL）。查不到回退到传入的 username。
 *
 * <p>用于事故线程人类消息的 actor_name 落库：actor 存 username（服务端认定、稳定），actor_name 存显示名（可变）。
 */
@Service
public class DisplayNameResolver {

    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    private record Entry(String displayName, long expireAt) {
    }

    private final UserRepository userRepository;
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    public DisplayNameResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 解析显示名；查不到或为空回退 fallbackUsername。 */
    public String resolve(Long userId, String fallbackUsername) {
        if (userId == null) return fallbackUsername;
        long now = System.currentTimeMillis();
        Entry cached = cache.get(userId);
        if (cached != null && cached.expireAt() > now) {
            return cached.displayName() != null ? cached.displayName() : fallbackUsername;
        }
        String resolved = userRepository.findById(userId)
                .map(User::getDisplayName)
                .filter(dn -> dn != null && !dn.isBlank())
                .orElse(fallbackUsername);
        cache.put(userId, new Entry(resolved, now + TTL_MILLIS));
        return resolved;
    }
}
