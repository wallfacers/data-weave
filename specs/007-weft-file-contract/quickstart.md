# Quickstart: 文件化定义契约(007-weft-file-contract)

## 一个完整示例项目树

```
analytics/
├── project.yaml
├── tags.yaml
├── _folder.yaml
├── orders/
│   ├── _folder.yaml
│   ├── orders_etl.task.yaml
│   ├── orders_etl.sql
│   ├── notify.task.yaml
│   ├── notify.sh
│   └── daily_orders.flow.yaml
└── staging/
    └── _folder.yaml          # 空类目:只有标记文件
```

**project.yaml**
```yaml
formatVersion: 1
code: analytics
name: 数据分析项目
```

**tags.yaml**
```yaml
formatVersion: 1
tags:
  - name: critical
    color: "#ff3b30"
  - name: nightly
    color: "#3366cc"
```

**orders/_folder.yaml**
```yaml
name: 订单域
sortOrder: 10
```

**orders/orders_etl.task.yaml**(身份 = slug `orders_etl`;脚本在 `orders_etl.sql`)
```yaml
formatVersion: 1
name: 订单 ETL
type: SQL
description: 每日订单宽表抽取
priority: 5
timeoutSec: 600
retryMax: 2
datasource: warehouse_main
targetDatasource: mart_orders
params:
  bizDate: ${yyyyMMdd}
  mode: full
tags:
  - critical
  - nightly
```

**orders/orders_etl.sql**(脚本体,原生 SQL,不转义)
```sql
INSERT INTO mart_orders.daily
SELECT order_date, SUM(amount)
FROM warehouse_main.orders
WHERE order_date = '${bizDate}'
GROUP BY order_date;
```

**orders/daily_orders.flow.yaml**(节点按 key 升序、边 STRONG 省略)
```yaml
formatVersion: 1
name: 每日订单流
schedule:
  type: CRON
  cron: "0 0 2 * * ?"
priority: 5
preemptible: true
timeoutSec: 3600
tags:
  - nightly
nodes:
  - key: n_etl
    type: TASK
    task: orders/orders_etl
    name: 抽取
    pos: [200, 120]
  - key: n_notify
    type: TASK
    task: orders/notify
    name: 通知
    pos: [400, 120]
  - key: start
    type: VIRTUAL
    name: 起点
    pos: [60, 120]
edges:
  - from: n_etl
    to: n_notify
  - from: start
    to: n_etl
```

## 验证(B 的测试如何跑)

B 是纯转换库(无 IO、无 Spring),round-trip 用纯单测验证(JUnit 5 + AssertJ):

```bash
# 编译 + 跑 B 的测试(不需 Docker/DB)
cd backend && ./mvnw -q -pl dataweave-master test -Dtest='com.dataweave.master.filecontract.*'
```

核心断言(对应 data-model.md §7):
- **RoundTripTest**:`deserialize(serialize(model))` 语义等价 model(R2);`serialize(deserialize(bundle))` 字节等于 bundle(R3)。
- **DeterminismTest**:同一 model 序列化 100 次逐字节相同(R1);打乱集合顺序产物不变(R4)。
- **GoldenFileTest**:`src/test/resources/filecontract/sample-project/` 金文件树与序列化产物逐字节比对。
- **ErrorHandlingTest**:缺必填字段 / 命名违规 / 悬挂引用 / 脚本超长 → 各得指明文件+定位的明确错误(FR-015)。
- **DiffLocalityTest**:改单字段 → bundle 变更文件数=1(R5/SC-004)。

## API 形状(供 C/D 接线)

```java
FileContract fc = new FileContract();

// pull 侧(C 用):领域模型聚合 → 内存文件树
ProjectFileBundle bundle = fc.serialize(projectExport);   // Map<相对路径, UTF-8 内容>

// push 侧(C 用):内存文件树 → 领域模型聚合(+ 校验错误)
ProjectImport imported = fc.deserialize(bundle);          // 抛 FileContractException(file+locus)
```

B 不碰真实文件系统;`ProjectFileBundle` ↔ 磁盘的读写归 C(服务端)与 D(CLI)。
