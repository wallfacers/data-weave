package com.dataweave.master.infrastructure;

import com.dataweave.master.application.ConnectionTester;
import com.dataweave.master.application.ConnectionTesterFactory;
import com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;
import com.dataweave.master.domain.Datasource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTesterTest {

    @Test
    void jdbcTester_supportsJdbcTypes() {
        JdbcConnectionTester tester = new JdbcConnectionTester();
        assertThat(tester.supports("MYSQL")).isTrue();
        assertThat(tester.supports("POSTGRES")).isTrue();
        assertThat(tester.supports("CLICKHOUSE")).isTrue();
        assertThat(tester.supports("STARROCKS")).isTrue();
        assertThat(tester.supports("DORIS")).isTrue();
        assertThat(tester.supports("HIVE")).isTrue();
        assertThat(tester.supports("ORACLE")).isTrue();
        assertThat(tester.supports("SQLSERVER")).isTrue();
        assertThat(tester.supports("MARIADB")).isTrue();
        assertThat(tester.supports("DB2")).isTrue();
        assertThat(tester.supports("IMPALA")).isTrue();
        assertThat(tester.supports("MONGODB")).isFalse();
        assertThat(tester.supports("REDIS")).isFalse();
        assertThat(tester.supports("S3")).isFalse();
    }

    @Test
    void unsupportedTester_returnsNotYetSupported() {
        UnsupportedConnectionTester tester = new UnsupportedConnectionTester();
        Datasource ds = new Datasource();
        ds.setTypeCode("MONGODB");

        ConnectionTestResult result = tester.test(ds, null);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("MONGODB");
        assertThat(result.message()).contains("暂未实现");
    }

    @Test
    void jdbcTester_driverMissing_returnsFriendlyError() {
        JdbcConnectionTester tester = new JdbcConnectionTester();
        Datasource ds = new Datasource();
        ds.setTypeCode("ORACLE");
        ds.setHost("10.0.0.1");
        ds.setPort(1521);
        ds.setDatabaseName("ORCL");
        ds.setJdbcUrl("jdbc:oracle:thin:@10.0.0.1:1521:ORCL");

        // Oracle driver is not on the classpath
        ConnectionTestResult result = tester.test(ds, "pw");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("驱动未安装");
    }

    @Test
    void factory_prefersJdbcOverUnsupported() {
        JdbcConnectionTester jdbc = new JdbcConnectionTester();
        UnsupportedConnectionTester fallback = new UnsupportedConnectionTester();
        ConnectionTesterFactory factory = new ConnectionTesterFactory(List.of(fallback, jdbc));

        ConnectionTester tester = factory.getTester("MYSQL");
        assertThat(tester).isInstanceOf(JdbcConnectionTester.class);
    }

    @Test
    void factory_fallsBackToUnsupported() {
        JdbcConnectionTester jdbc = new JdbcConnectionTester();
        UnsupportedConnectionTester fallback = new UnsupportedConnectionTester();
        ConnectionTesterFactory factory = new ConnectionTesterFactory(List.of(jdbc, fallback));

        ConnectionTester tester = factory.getTester("MONGODB");
        assertThat(tester).isInstanceOf(UnsupportedConnectionTester.class);
    }
}
