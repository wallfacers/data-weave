package com.dataweave.api.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaVersionGuardTest {

    @Test
    void shouldPassWhenVersionMatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("0.20.0");

        SchemaVersionGuard guard = new SchemaVersionGuard(jdbc);
        assertThatCode(guard::run).doesNotThrowAnyException();
    }

    @Test
    void shouldThrowWhenVersionMismatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("0.8.0");

        SchemaVersionGuard guard = new SchemaVersionGuard(jdbc);
        assertThatThrownBy(guard::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0.8.0")
                .hasMessageContaining("0.20.0");
    }

    @Test
    void shouldThrowWhenNoVersionRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(null);

        SchemaVersionGuard guard = new SchemaVersionGuard(jdbc);
        assertThatThrownBy(guard::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema_version 表为空");
    }

    @Test
    void shouldFailWhenDbUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        SchemaVersionGuard guard = new SchemaVersionGuard(jdbc);
        assertThatThrownBy(guard::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法读取 schema_version");
    }
}
