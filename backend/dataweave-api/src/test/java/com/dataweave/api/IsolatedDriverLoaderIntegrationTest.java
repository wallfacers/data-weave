package com.dataweave.api;

import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.infrastructure.DriverJarStorage;
import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import org.h2.Driver;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.sql.Connection;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IsolatedDriverLoader 真实隔离加载实证（datasource-driver-isolation 4.4）：
 * 从上传 jar 字节经隔离 {@code URLClassLoader} 加载真实 driver（H2），并 {@code Driver.connect} 建连内存库，
 * 证明「绕过 DriverManager + 隔离 CL」机制端到端工作（即便 classpath 已有同驱动，隔离加载亦独立生效）。
 */
class IsolatedDriverLoaderIntegrationTest {

    @Test
    void loadsH2FromUploadedJarBytes_andConnectsInMemoryDb() throws Exception {
        byte[] h2Jar = classpathJarOf(Driver.class);
        DriverJarStorage storage = mock(DriverJarStorage.class);
        when(storage.get(eq("h2.jar"))).thenReturn(h2Jar);
        IsolatedDriverLoader loader = new IsolatedDriverLoader(storage);

        DriverJar jar = new DriverJar();
        jar.setStorageKey("h2.jar");
        jar.setDriverClass("org.h2.Driver");

        try (Connection conn = loader.connect(jar, "jdbc:h2:mem:iso_test;DB_CLOSE_DELAY=-1", new Properties())) {
            assertThat(conn).isNotNull();
            assertThat(conn.getMetaData().getDriverName()).contains("H2");
        }
    }

    /** 从 marker 类的 code source 读取其 jar 字节（模拟上传的 jar 内容）。 */
    private static byte[] classpathJarOf(Class<?> marker) throws Exception {
        URL url = marker.getProtectionDomain().getCodeSource().getLocation();
        if (url == null) {
            throw new IllegalStateException("无法定位 " + marker + " 的 jar");
        }
        try (java.io.InputStream in = url.openStream()) {
            return in.readAllBytes();
        }
    }
}
