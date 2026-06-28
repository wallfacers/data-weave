package com.dataweave.api;

import com.dataweave.master.domain.MasterNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * markOffline 是 MasterRegistry 后台 sweep（@Scheduled）调用的修改型查询。
 * 它是 UPDATE，必须带 @Modifying —— 否则 Spring Data JDBC 按查询执行，H2 抛
 * "Method is only allowed for a query"（SQLState 90002），sweep 每轮失败（仅日志）。
 * 本测试用唯一 master_code 隔离：插一个超时 ONLINE + 一个新鲜 ONLINE，断言
 * 仅超时的被标 OFFLINE。无 @Modifying 时此调用直接抛异常 → 测试红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class MasterNodeMarkOfflineTest {

    private static final String STALE = "master-markoffline-stale";
    private static final String FRESH = "master-markoffline-fresh";

    @Autowired
    private MasterNodeRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    private void insert(String code, LocalDateTime lastHeartbeat) {
        jdbc.update(
                "INSERT INTO master_nodes (master_code, master_uri, incarnation, status, "
                        + "last_heartbeat, created_at, updated_at) VALUES (?,?,?, 'ONLINE', ?, ?, ?)",
                code, "http://localhost:0", 1L, lastHeartbeat,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private String statusOf(String code) {
        return jdbc.queryForObject(
                "SELECT status FROM master_nodes WHERE master_code = ?", String.class, code);
    }

    @Test
    void markOfflineFlipsOnlyStaleMasters() {
        LocalDateTime now = LocalDateTime.now();
        insert(STALE, now.minusMinutes(5));   // 超时
        insert(FRESH, now);                    // 新鲜

        // threshold = 1 分钟前：STALE 的心跳 <= threshold，FRESH 的 > threshold
        repo.markOffline(now.minusMinutes(1), now);

        assertThat(statusOf(STALE)).isEqualTo("OFFLINE");
        assertThat(statusOf(FRESH)).isEqualTo("ONLINE");
    }
}
