package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * JDBC 驱动隔离类加载器（datasource-driver-isolation 核心）。
 *
 * <p>按 {@link DriverJar#getSha256()} 缓存一个 {@code URLClassLoader} + {@link Driver} 实例：
 * 同一 sha256 被多个数据源引用时复用，不同版本各自独立 ClassLoader——实现多版本驱动按数据源共存
 * （如数据源 A 用 ojdbc6、B 用 ojdbc11）。
 *
 * <p>加载时将 jar 字节复制为临时副本再加载，避免原文件锁（Windows 尤甚）；
 * 通过 {@code Class.forName(driverClass, true, cl)} 取 {@link Driver} 实例后，直接 {@link Driver#connect}
 * 建连——绕过 {@code DriverManager.getConnection} 的 ClassLoader 校验，否则隔离加载的驱动会被判
 * {@code "No suitable driver"}。
 *
 * <p>单 jar 隔离的局限：上传 jar 若有外部传递依赖，需打包为 standalone/shaded jar（文档说明）。
 */
@Component
public class IsolatedDriverLoader {

    /** 缓存上限：超过则驱逐最久未访问（LRU），关闭其 ClassLoader 释放 Metaspace/文件锁。 */
    private static final int MAX_CACHED = 64;

    private final DriverJarStorage storage;
    private final Map<String, LoadedDriver> cache;

    public IsolatedDriverLoader(DriverJarStorage storage) {
        this.storage = storage;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LoadedDriver> eldest) {
                if (size() > MAX_CACHED) {
                    eldest.getValue().close();
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * 用数据源绑定的上传 jar 隔离加载并直接 {@code driver.connect} 建连，绕过 {@code DriverManager}。
     *
     * @throws RuntimeException 驱动内容缺失、加载失败、或驱动不识别该 jdbcUrl
     */
    public Connection connect(DriverJar jar, String jdbcUrl, Properties props) {
        Driver driver = resolve(jar);
        try {
            Connection conn = driver.connect(jdbcUrl, props);
            if (conn == null) {
                throw new RuntimeException("隔离驱动 " + jar.getDriverClass()
                        + " 无法识别 jdbcUrl: " + jdbcUrl);
            }
            return conn;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("隔离驱动建连失败: " + e.getMessage(), e);
        }
    }

    private Driver resolve(DriverJar jar) {
        // 缓存键用 storageKey（= sha256.jar，唯一）；worker 端构造的临时 DriverJar 无 sha256，按 storageKey 复用
        LoadedDriver loaded = cache.get(jar.getStorageKey());
        if (loaded != null) {
            return loaded.driver();
        }
        return cache.computeIfAbsent(jar.getStorageKey(), s -> load(jar)).driver();
    }

    private LoadedDriver load(DriverJar jar) {
        try {
            byte[] bytes = storage.get(jar.getStorageKey());
            if (bytes == null) {
                throw new BizException("datasource.driver_missing", jar.getStorageKey());
            }
            // 复制为临时副本，避免原文件锁；进程退出时清理
            Path temp = Files.createTempFile("dw-driver-", ".jar");
            temp.toFile().deleteOnExit();
            Files.write(temp, bytes);
            // 父 ClassLoader = 应用 CL，使隔离驱动可复用 JDK/应用类（java.sql.* 等）
            URLClassLoader cl = new URLClassLoader(new URL[]{temp.toUri().toURL()}, getClass().getClassLoader());
            String driverClass = jar.getDriverClass();
            Class<?> cls = Class.forName(driverClass, true, cl);
            Driver driver = (Driver) cls.getDeclaredConstructor().newInstance();
            return new LoadedDriver(driver, cl);
        } catch (Exception e) {
            throw new RuntimeException("隔离加载驱动失败: " + jar.getDriverClass()
                    + " (sha256=" + jar.getSha256() + "): " + e.getMessage(), e);
        }
    }

    private record LoadedDriver(Driver driver, URLClassLoader classLoader) {
        void close() {
            try {
                classLoader.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
