package com.dataweave.api.infrastructure;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对齐 071 的跨方言实现：Guard 改为 {@code queryForList(ALL_VERSIONS_SQL)} + Java 侧
 * {@link SchemaVersionGuard#maxSemver} 取最大（替代 PG 专用 string_to_array 排序，H2 兼容）。
 */
class SchemaVersionGuardTest {

    @Test
    void shouldPassWhenVersionMatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("0.8.0", SchemaVersionGuard.EXPECTED_VERSION));

        SchemaVersionGuard guard = new SchemaVersionGuard(jdbc);
        assertThatCode(guard::run).doesNotThrowAnyException();
    }

    @Test
    void shouldThrowWhenVersionMismatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("0.8.0"));

        SchemaVersionGuard guard = new SchemaVersionGuard(jdbc);
        assertThatThrownBy(guard::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0.8.0")
                .hasMessageContaining(SchemaVersionGuard.EXPECTED_VERSION);
    }

    @Test
    void shouldThrowWhenNoVersionRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        SchemaVersionGuard guard = new SchemaVersionGuard(jdbc);
        assertThatThrownBy(guard::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema_version 表为空");
    }

    @Test
    void shouldFailWhenDbUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        SchemaVersionGuard guard = new SchemaVersionGuard(jdbc);
        assertThatThrownBy(guard::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法读取 schema_version");
    }

    /** semver 语义与旧 PG string_to_array 排序等价：数值比较（20 > 8），非字典序；非法行跳过。 */
    @Test
    void maxSemverPicksNumericMaxAndSkipsGarbage() {
        assertThat(SchemaVersionGuard.maxSemver(List.of("0.8.0", "0.21.0", "0.20.0"))).isEqualTo("0.21.0");
        assertThat(SchemaVersionGuard.maxSemver(List.of("0.9.0", "0.10.0"))).isEqualTo("0.10.0");
        assertThat(SchemaVersionGuard.maxSemver(List.of("garbage", "0.21.0"))).isEqualTo("0.21.0");
    }
}
