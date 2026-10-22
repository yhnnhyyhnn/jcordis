# jcordis 性能基准

> 信息性数据（非断言）：`PerfBenchmarkTest`（jcordis-core 测试）输出 ns/op。
> 重新基准：在代表性机器上 `mvn -pl jcordis-core test -Dtest=PerfBenchmarkTest`，surefire 报告中取 `[perf]` 行。

## 实测数据（2026-10-22，当前 1.0.4-SNAPSHOT）

**环境**：Windows 10 / JDK 21（Temurin）/ 与 09-01 基线同机（`PerfBenchmarkTest`）

| 操作 | ns/op | M ops/s | 对比 09-01 基线 |
|---|---|---|---|
| `fiber create + dispose` | 4 426 | 0.23 | **23 590 → 5.3× 快**（销毁同步化，去 commonPool 提交+join） |
| `event emit`（10 个监听器） | 614 | 1.63 | 677 → 持平略优 |
| `effect register + dispose` | 434 | 2.30 | 604 → 快 28% |
| `service get`（已提供） | 602 | 1.66 | 674 → 持平略优（含 fiber 链行走 + 隔离键检查） |
| `logger format` | 5 243 | 0.19 | 4 561 → +15%（%o JSON 化与 exporter formatter 间接层） |

## JMH 校准数据（2026-10-22）

**运行**：`mvn -Pbenchmark -pl jcordis-core test -Dtest=JmhRunnerTest`（fork=1，Cnt=3；误差较宽，方向可信）

| 基准 | ops/s | 对比 09-01 基线 |
|---|---|---|
| `serviceGet` | 94 031 475 | 49 926 912 → **1.9×** |
| `eventEmitTenListeners` | 22 312 477 | 22 338 433 → 持平 |
| `effectRegisterDispose` | 5 376 746 | 6 086 670 → 误差内持平 |
| `loggerFormat` | 1 601 822 | 1 562 641 → 持平（JSON 化无回退） |
| `fiberCreateAndDispose` | 1 137 298 | 86 551 → **13×** |

## 变化解读（09-01 → 10-22）

- **fiber create + dispose 提升一个数量级**（JMH 13×、轻量 5.3×）：2026-10 的销毁同步化修复（plugin 销毁不再强制 ForkJoinPool.commonPool + join）消除了每次生命周期的两次池任务提交/等待——这既是正确性修复（消除嵌套级联饥饿死锁面），也是本周期最大的性能收益
- **serviceGet 提升约 2×**（JMH）：`localValue` 快路径命中（fiber 本地解析快照优先于全局 store 查询）
- **logger format +15%**（轻量基准）：%o 的 JSON 渲染与 exporter 自定义 formatter 的间接层——绝对值仍在 5μs 级，JMH 稳态持平
- 事件/效应持平

## 09-01 原始基线（保留对照）

| 操作 | ns/op（轻量） | ops/s（JMH） |
|---|---|---|
| `fiber create + dispose` | 23 590 | 86 551 |
| `event emit`（10 个监听器） | 677 | 22 338 433 |
| `effect register + dispose` | 604 | 6 086 670 |
| `service get`（已提供） | 674 | 49 926 912 |
| `logger format` | 4 561 | 1 562 641 |

## 通用解读

- **fiber create + dispose 最重**（~4.4μs）：包含 uid 分配、依赖解析（checkImpl）、生命周期 effect 注册、插件体执行、`internal/plugin`/`internal/status` 事件、逆序处置——"创建即启动"的完整语义成本，按插件数量级线性扩展，非热点路径
- **事件/效应/服务访问均为亚微秒级**：COW 列表 + CHM + Monitor 锁在低竞争下开销可忽略
- **logger format**：含正则匹配（每次 format 重新编译 Pattern）；极端高吞吐日志可预编译

## 与 Cordis（Node.js）对比（定性）

| 操作 | Cordis（JS 单线程） | jcordis（JVM） |
|---|---|---|
| 事件派发 | 微秒级（直接调用） | 亚微秒级（COW 快照）——同级 |
| 插件创建 | 微秒级 | ~4.4μs（含类型/事件/依赖解析）——同级（同步化后） |
| 服务访问 | 属性访问（Proxy） | 方法调用（显式 get）——jcordis 更快（无 Proxy 拦截） |

> jcordis 的显式方法调用（替代 JS Proxy 动态属性）在服务访问热路径上**优于** Cordis 的 Proxy 拦截。
