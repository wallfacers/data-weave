# Contract: MasterRegistry（master 成员与分片，Batch B）

为 >10k 工作流提供「活 master 集合 + 本机序号」,使每个 master 只预读自己分片。仅 Batch B 引入;Batch A 不依赖(退化为全量预读,cron_fire 兜底去重)。

## MasterRegistry

```java
public interface MasterRegistry {
    /** 启动时自注册:upsert master_nodes(master_code = hostname+"-"+pid, incarnation, status=ONLINE)。 */
    void register();

    /** 周期续约:更新 last_heartbeat;并由任一 master 将超时(默认 30s)成员标记 OFFLINE。 */
    void heartbeat();

    /** 当前活 master 集合(status=ONLINE AND last_heartbeat > now-threshold)，按 master_code 稳定排序。 */
    List<String> activeMasters();

    /** 本机在活集合中的序号 [0, n)，与 activeMasters() 排序一致。 */
    int myShardIndex();
}
```

## 分片规则

- `activeMasterCount = activeMasters().size()`(下界 1)。
- `shard(workflowId) = Math.floorMod(hash(workflowId), activeMasterCount)`。
- 本 master 预读条件追加:`MOD(id, :count) = :index`(或等价 Java 侧过滤)。
- **漂移期安全**: master 上/下线导致活集合变化的瞬间,某触发点可能短暂被两个或零个 master 预读 —— 被两个 → `cron_fire` 唯一键保证只触发一次;短暂零 → 下一轮 15s 扫描 + 过期点 delay=0 立即补,延迟有界(满足 SC-002/SC-007 的零重复/零漏)。
- **稳定性建议**: hash 用 `workflow_id` 的稳定散列(非 `Object.hashCode`),避免重启后分片漂移。

## 心跳与超时

- 续约周期默认 10s,超时阈值默认 30s(对齐 `worker_nodes`/`FleetService` 惯例)。
- 配置:`scheduler.master-heartbeat-ms`(默认 10000)、`scheduler.master-offline-threshold-sec`(默认 30)。
- 清理超时成员幂等;任一 master 可执行,无主从。

## 不变量(测试须覆盖)

- ≥10k 工作流、≥3 master:全量触发点**零重复、零漏**;各 master 负责工作流数 ≈ 总数 / 活 master 数(`dw.cron.shard.workflows` 验证单机负载随分片下降)。
- master 上线/下线触发重平衡:漂移窗口内不丢触发(靠 cron_fire 兜底)、不重复触发。
