package com.dataweave.master.application;

import com.dataweave.master.application.DatasourceResolver.ResolvedConnection;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasourceResolverTest {

    @Mock DatasourceRepository datasourceRepository;
    @Mock DatasourceEncryptor encryptor;

    DatasourceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DatasourceResolver(datasourceRepository, encryptor, new ObjectMapper());
    }

    @Test
    void resolve_sql_returnsJdbcRef() {
        Datasource ds = sampleDatasource();
        when(datasourceRepository.findById(1L)).thenReturn(Optional.of(ds));
        when(encryptor.decrypt("enc_pw")).thenReturn("plain_pw");

        ResolvedConnection rc = resolver.resolve(1L, "SQL");

        assertThat(rc).isNotNull();
        assertThat(rc.jdbcUrl()).isEqualTo("jdbc:mysql://10.0.0.20:3306/shop");
        assertThat(rc.username()).isEqualTo("app");
        assertThat(rc.password()).isEqualTo("plain_pw");
        assertThat(rc.shellEnvVars()).isNull();
        assertThat(rc.pythonConfigPath()).isNull();
    }

    @Test
    void resolve_shell_returnsEnvVars() {
        Datasource ds = sampleDatasource();
        when(datasourceRepository.findById(1L)).thenReturn(Optional.of(ds));
        when(encryptor.decrypt("enc_pw")).thenReturn("plain_pw");

        ResolvedConnection rc = resolver.resolve(1L, "SHELL");

        assertThat(rc).isNotNull();
        Map<String, String> env = rc.shellEnvVars();
        assertThat(env).isNotNull();
        assertThat(env.get("DW_DS_HOST")).isEqualTo("10.0.0.20");
        assertThat(env.get("DW_DS_PORT")).isEqualTo("3306");
        assertThat(env.get("DW_DS_DATABASE")).isEqualTo("shop");
        assertThat(env.get("DW_DS_USER")).isEqualTo("app");
        assertThat(env.get("DW_DS_PASSWORD")).isEqualTo("plain_pw");
        assertThat(env.get("DW_DS_TYPE")).isEqualTo("MYSQL");
        assertThat(env.get("DW_DS_URL")).isEqualTo("jdbc:mysql://10.0.0.20:3306/shop");
    }

    @Test
    void resolve_python_createsJsonFile() throws Exception {
        Datasource ds = sampleDatasource();
        when(datasourceRepository.findById(1L)).thenReturn(Optional.of(ds));
        when(encryptor.decrypt("enc_pw")).thenReturn("plain_pw");

        ResolvedConnection rc = resolver.resolve(1L, "PYTHON");

        assertThat(rc).isNotNull();
        assertThat(rc.pythonConfigPath()).isNotNull();

        // Verify JSON file content
        Path path = Path.of(rc.pythonConfigPath());
        assertThat(Files.exists(path)).isTrue();
        String json = Files.readString(path);
        assertThat(json).contains("\"type\":\"MYSQL\"");
        assertThat(json).contains("\"host\":\"10.0.0.20\"");
        assertThat(json).contains("\"password\":\"plain_pw\"");

        // Cleanup
        resolver.cleanup(rc.pythonConfigPath());
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void resolve_nullDatasourceId_returnsNull() {
        assertThat(resolver.resolve(null, "SQL")).isNull();
    }

    @Test
    void cleanup_nullPath_noError() {
        resolver.cleanup(null); // should not throw
    }

    private static Datasource sampleDatasource() {
        Datasource ds = new Datasource();
        ds.setId(1L);
        ds.setTenantId(1L);
        ds.setProjectId(1L);
        ds.setName("orders_mysql");
        ds.setTypeCode("MYSQL");
        ds.setHost("10.0.0.20");
        ds.setPort(3306);
        ds.setDatabaseName("shop");
        ds.setJdbcUrl("jdbc:mysql://10.0.0.20:3306/shop");
        ds.setUsername("app");
        ds.setPasswordEnc("enc_pw");
        ds.setStatus("ACTIVE");
        ds.setDeleted(0);
        return ds;
    }
}
