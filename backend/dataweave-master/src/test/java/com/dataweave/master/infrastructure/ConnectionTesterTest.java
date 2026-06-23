package com.dataweave.master.infrastructure;

import com.dataweave.master.application.ConnectionTesterFactory;
import com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceType;
import com.dataweave.master.domain.DatasourceTypeRepository;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.i18n.Messages;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 连通测试单测（datasource-driver-isolation）：覆盖 supports / 驱动来源优先级 / driver_missing /
 * factory 路由。文案经真实 {@link Messages}（ResourceBundleMessageSource 读 messages.properties）按 locale 本地化。
 */
class ConnectionTesterTest {

    /** 内置路径的 tester：repository 均 mock，supports/内置连通路径不实际依赖它们。 */
    private JdbcConnectionTester newBuiltinTester() {
        return new JdbcConnectionTester(
                mock(DatasourceTypeRepository.class),
                mock(DriverJarRepository.class),
                mock(IsolatedDriverLoader.class),
                testMessages());
    }

    /** 真实 Messages（读 classpath 的 messages.properties，中文 base）。 */
    private static Messages testMessages() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        return new Messages(ms);
    }

    @Test
    void jdbcTester_supportsJdbcTypes() {
        JdbcConnectionTester tester = newBuiltinTester();
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
    void unsupportedTester_returnsLocalizedNotYetSupported() {
        UnsupportedConnectionTester tester = new UnsupportedConnectionTester(testMessages());
        Datasource ds = new Datasource();
        ds.setTypeCode("MONGODB");

        ConnectionTestResult result = tester.test(ds, null, Locale.SIMPLIFIED_CHINESE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("MONGODB");
        assertThat(result.message()).contains("暂未实现");
    }

    @Test
    void jdbcTester_driverMissing_returnsLocalizedError() {
        // resolveDriver 读 datasource_types.driver 字段；DB 声明一个不存在的 driver class → 内置路径 Class.forName 失败
        DatasourceTypeRepository typeRepo = mock(DatasourceTypeRepository.class);
        DatasourceType fakeType = new DatasourceType();
        fakeType.setDriver("com.nonexistent.FakeDriver");
        org.mockito.Mockito.when(typeRepo.findByCode("ORACLE")).thenReturn(Optional.of(fakeType));

        JdbcConnectionTester tester = new JdbcConnectionTester(
                typeRepo, mock(DriverJarRepository.class), mock(IsolatedDriverLoader.class), testMessages());
        Datasource ds = new Datasource();
        ds.setTypeCode("ORACLE");
        ds.setJdbcUrl("jdbc:oracle:thin:@10.0.0.1:1521:ORCL");

        ConnectionTestResult result = tester.test(ds, "pw", Locale.SIMPLIFIED_CHINESE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("驱动未安装");
    }

    @Test
    void factory_prefersJdbcOverUnsupported() {
        JdbcConnectionTester jdbc = newBuiltinTester();
        UnsupportedConnectionTester fallback = new UnsupportedConnectionTester(testMessages());
        ConnectionTesterFactory factory = new ConnectionTesterFactory(List.of(fallback, jdbc));

        assertThat(factory.getTester("MYSQL")).isInstanceOf(JdbcConnectionTester.class);
    }

    @Test
    void factory_fallsBackToUnsupported() {
        JdbcConnectionTester jdbc = newBuiltinTester();
        UnsupportedConnectionTester fallback = new UnsupportedConnectionTester(testMessages());
        ConnectionTesterFactory factory = new ConnectionTesterFactory(List.of(jdbc, fallback));

        assertThat(factory.getTester("MONGODB")).isInstanceOf(UnsupportedConnectionTester.class);
    }
}
