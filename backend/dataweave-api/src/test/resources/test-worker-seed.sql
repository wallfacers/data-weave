-- 调度 E2E 测试专用 worker 种子。
-- 背景:ec7868e(2026-07-02)移除了 data.sql 的 worker_nodes 假数据(fleet 页空状态),此后节点只经
-- Worker 进程 HTTP 心跳注册——@SpringBootTest MOCK 环境无监听端口,注册不可能发生,
-- worker_nodes 恒空 → SchedulerKernel.claimAndMark 无 ONLINE 空槽直接空转 → 一切认领/执行 E2E 超时。
-- 依赖调度闭环的测试类通过 @Sql 引入本种子自备 ONLINE worker(测试自持前置,不回灌产品 data.sql)。
-- 幂等:同码先删再插(默认 BEFORE_TEST_METHOD 每个用例执行)。
DELETE FROM worker_nodes WHERE node_code = 'test-worker-1';
INSERT INTO worker_nodes (node_code, host, ip, status, max_concurrent_tasks, reserved_test_slots,
                          last_heartbeat, created_at, updated_at, deleted, version)
VALUES ('test-worker-1', 'localhost', '127.0.0.1', 'ONLINE', 8, 0,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0);
