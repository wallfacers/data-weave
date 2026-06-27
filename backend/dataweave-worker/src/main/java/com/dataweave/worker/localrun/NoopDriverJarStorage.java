package com.dataweave.worker.localrun;

import com.dataweave.master.infrastructure.DriverJarStorage;

/**
 * 本地 runner 的空驱动存储。
 *
 * <p>本地 SQL 连接走内置 JDBC 驱动（CLI 解析的 {@code DataSourceRef} 不带 {@code driverJarId}），
 * {@code SqlTaskExecutor.openConnection} 走 {@code DriverManager.getConnection} 分支，
 * {@code IsolatedDriverLoader.connect} 永不被触发。本类存在仅为满足
 * {@code new SqlTaskExecutor(new IsolatedDriverLoader(...))} 的构造签名，不引入对 master
 * 存储后端 / filecontract 的真实运行期耦合。
 */
class NoopDriverJarStorage implements DriverJarStorage {
    @Override public String put(String storageKey, byte[] content) { return storageKey; }
    @Override public byte[] get(String storageKey) { return null; }
    @Override public void delete(String storageKey) { /* no-op */ }
    @Override public String type() { return "LOCAL_NOOP"; }
}
