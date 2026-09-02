# jcordis 性能基准

> 信息性数据（非断言）：`PerfBenchmarkTest`（jcordis-core 测试）输出 ns/op。
> 重新基准：在代表性机器上 `mvn -pl jcordis-core test -Dtest=PerfBenchmarkTest`，surefire 报告中取 `[perf]` 行。

## 实测数据

**环境**：Windows 10 / JDK 21 (Temurin) / 本机（2026-09-01，`PerfBenchmarkTest`）

| 操作 | ns/op | M ops/s |
|---|---|---|
| `fiber create + dispose`（插件创建+销毁全生命周期） | 23 590 | 0.04 |
| `event emit`（10 个监听器） | 677 | 1.48 |
| `effect register + dispose` | 604 | 1.66 |
| `service get`（已提供） | 674 | 1.48 |
| `logger format`（%s/%d 占位符 + 换行截断） | 4 561 | 0.22 |

## JMH 校准数据

**运行**：`mvn -Pbenchmark -pl jcordis-core test -Dtest=JmhRunnerTest`（fork=1，warmup 2×1s，measurement 3×1s）

| 基准 | ops/s | ns/op（约） |
|---|---|---|
| `serviceGet` | 49 926 912 | 0.02 |
| `eventEmitTenListeners` | 22 338 433 | 0.045 |
| `effectRegisterDispose` | 6 086 670 | 0.16 |
| `loggerFormat` | 1 562 641 | 0.64 |
| `fiberCreateAndDispose` | 86 551 | 11.6 |

> JMH 校准值显著优于轻量基准（消除 JIT 预热/分配噪声、状态共享），作为相对基线更可靠。
> 差异解读：轻量基准包含暖机不足的首次成本；JMH 为稳态吞吐。

## 解读

- **fiber create + dispose 最重**（~24μs）：包含 uid 分配、依赖解析（checkImpl）、
  生命周期 effect 注册、插件体执行、`internal/plugin`/`internal/status` 事件、
  逆序处置——这是"创建即启动"的完整语义成本，按插件数量级线性扩展，非热点路径。
- **事件/效应/服务访问均为亚微秒级**：COW 列表 + CHM + Monitor 锁在低竞争下开销可忽略。
- **logger format 占位符解析**为毫秒级以下（4.6μs），含正则匹配——单条日志成本可接受；
  若为极端高吞吐日志，可考虑预编译 Pattern（当前每次 format 重新编译）。

## 与 Cordis（Node.js）对比（定性）

| 操作 | Cordis（JS 单线程） | jcordis（JVM） |
|---|---|---|
| 事件派发 | 微秒级（直接调用） | 亚微秒级（COW 快照）——同级 |
| 插件创建 | 微秒级（无类型系统开销） | ~24μs（含类型/事件/依赖解析）——数量级差距，但对应用级插件数（10~100）无感知 |
| 服务访问 | 属性访问（Proxy） | 方法调用（显式 get）——jcordis 更快（无 Proxy 拦截） |

> jcordis 的显式方法调用（替代 JS Proxy 动态属性）在服务访问热路径上**优于** Cordis 的 Proxy 拦截。
