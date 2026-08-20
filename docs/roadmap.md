# jcordis —— Java 21 移植路线图

> 目标：以 Java 21 重新实现 Cordis（TypeScript 元框架），保留其"时空可组合性"核心思想，同时适配 JVM 生态。
> 前置文档：[cordis-analysis.md](./cordis-analysis.md)
> 状态：规划阶段（尚未开始编码）

---

## 0. 总体策略

### 0.1 移植原则

1. **保留核心价值，不逐行翻译**：移植的是*概念与语义*（可逆效应、响应式依赖、统一上下文、声明式加载），不是语法。
2. **利用 Java 21 新特性**：virtual threads（虚拟线程）、record、sealed interface、pattern matching、`SequencedCollection`、`StructuredTaskScope`。
3. **借力成熟生态**：Jackson（配置解析）、SLF4J（日志门面）、Spring Expression Language 或 JEXL（受限表达式）、ScheduledExecutorService（定时器）。
4. **先核心后外围**：Core → Loader → 周边插件 → 工具链。
5. **测试驱动**：移植过程中对照 Cordis 自己的 30 个测试文件，逐个翻译为 JUnit 5 测试。

### 0.2 与 Cordis 的命名对应

| Cordis 包 | jcordis 模块 | 包名建议 |
|---|---|---|
| packages/core | jcordis-core | `io.jcordis.core` |
| packages/loader | jcordis-loader | `io.jcordis.loader` |
| packages/timer | jcordis-plugin-timer | `io.jcordis.timer` |
| packages/logger-console | jcordis-plugin-logger-console | `io.jcordis.logger.console` |
| packages/include | jcordis-plugin-include | `io.jcordis.include` |
| packages/group | jcordis-plugin-group | `io.jcordis.group` |
| packages/utils | jcordis-utils | `io.jcordis.util` |
| packages/hmr | jcordis-plugin-hmr（可选） | `io.jcordis.hmr` |
| packages/create | jcordis-cli / maven-archetype | — |

### 0.3 关键技术决策（需在 Phase 0 确认）

| 决策点 | 推荐 | 理由 |
|---|---|---|
| 构建工具 | **Maven**（多模块） | 生态最成熟、企业级默认；亦可 Gradle |
| Java 版本 | 21 (LTS) | 项目已定 |
| 配置格式 | JSON + YAML（Jackson） | 与 Cordis include 对应 |
| 日志 | 自研轻量 Exporter 体系（对齐 Cordis）| 保留 exporter 语义；可选桥接 SLF4J |
| 测试 | JUnit 5 + AssertJ | 标准 |
| 表达式求值 | **SpEL（受限）或纯数据插值** | Cordis 用 eval，Java 必须安全化 |
| 插件加载 | 编译期引用 + `ServiceLoader`（SPI） | 简单可靠；动态编译见 Phase 4 |

---

## Phase 1 — 项目脚手架与 CI（第 1 周）

**目标**：多模块 Maven 工程骨架就位，可编译、可测试、可持续集成。

### 交付物

```
jcordis/
├── pom.xml                    # 父 POM（Java 21、依赖管理、插件管理）
├── jcordis-core/              # 核心框架（空骨架 + 首个测试）
├── jcordis-loader/            # 加载器（空骨架）
├── jcordis-plugin-timer/      # 定时器
├── jcordis-plugin-logger-console/
├── jcordis-plugin-include/
├── jcordis-plugin-group/
├── jcordis-utils/
├── jcordis-cli/               # CLI / 脚手架（后期）
└── .github/workflows/         # CI（mvn verify + 覆盖率）
```

### 任务清单

- [ ] 父 POM：`maven.compiler.release=21`、`project.build.sourceEncoding=UTF-8`、junit-jupiter 5.10+、assertj、maven-surefire-plugin
- [ ] 建立 8 个模块的骨架 pom（依赖方向：loader→core，timer/logger-console/utils→core，include/group→loader）
- [ ] `jcordis-core` 建立包结构（见 Phase 2）与一个冒烟测试
- [ ] GitHub Actions：`mvn -B verify` 于 JDK 21（temurin）
- [ ] `.editorconfig`、checkstyle/spotless（可选，建议 spotless + google-java-format）

### 退出标准

`mvn clean verify` 全部通过；CI 绿。

---

## Phase 2 — 核心框架 jcordis-core（第 2-5 周）

**目标**：完整移植 Cordis 核心语义。这是整个项目价值密度最高的部分。

### 2.1 模块结构

```
io.jcordis.core
├── context/       # Context 接口 + ContextImpl
├── fiber/         # Fiber、FiberState、Effect 体系
├── event/         # EventBus、EventOptions、DispatchMode
├── service/       # Service 抽象类、ServiceRegistry、Key/IsolationKey
├── logger/        # Logger、LoggerService、Exporter、Message
├── registry/      # PluginRegistry、Plugin、PluginRuntime
├── reflect/       # 服务解析（对应 Cordis ReflectService）
└── util/          # DisposableList、Traceable、CompletableFutures
```

### 2.2 核心 API 设计草案

```java
// Context —— 统一上下文（接口式 API，替代 Cordis 的 Proxy）
public interface Context {
    Fiber fiber();
    EventBus events();
    LoggerService logger();
    PluginRegistry registry();

    <T> T get(String name);
    <T> T get(ServiceKey<T> key);
    <T> void set(ServiceKey<T> key, T value);

    <T> Disposable provide(ServiceKey<T> key, T value, Predicate<Context> check);
    Context isolate(String name);           // 空间隔离
    Context intercept(String name, Object config);  // 配置拦截
    Context extend(Map<String, Object> meta);

    <T> T plugin(Plugin<T> plugin, T config);       // 返回 Fiber（PromiseLike 语义）
    Disposable effect(EffectRunner runner, String label);
}
```

```java
// Fiber —— 生命周期与可逆效应
public final class Fiber {
    enum State { PENDING, LOADING, ACTIVE, FAILED, DISPOSED, UNLOADING }
    State state();
    int uid();
    CompletableFuture<Fiber> await();       // 等价 then-able
    CompletableFuture<Void> disposeAsync();
    void update(Object config, boolean noSave);
}

// Effect 协议 —— 对应 Cordis 的四种效应返回类型
@FunctionalInterface
public interface EffectRunner {
    EffectResult run(Context ctx);
}
// EffectResult 可携带多个 Disposable（对应 TS 的 Iterable<Disposable>）
public sealed interface EffectResult {
    record Void() implements EffectResult {}
    record Single(Disposable disposable) implements EffectResult {}
    record Multiple(List<Disposable> disposables) implements EffectResult {}
}
```

```java
// 事件总线 —— 五种派发模式
public final class EventBus {
    void emit(String name, Object... args);
    CompletableFuture<Void> parallel(String name, Object... args); // 聚合错误
    <T> CompletableFuture<T> serial(String name, Object... args);  // 短路
    <T> T bail(String name, Object... args);                        // 同步短路
    <T> T waterfall(String name, Object... args, Consumer<Object> next);
    Disposable on(String name, Listener listener, EventOptions options);
}
```

```java
// 服务注册表 —— 空间可组合性
public final class ServiceRegistry {
    // 服务键：类型化 Key（替代 JS symbol）
    record ServiceKey<T>(String name) {}
    <T> void provide(ServiceKey<T> key, T impl, Fiber fiber);
    void notify(List<ServiceKey<?>> changed);  // 触发依赖方 epoch 重算与重载
    <T> T resolve(Context ctx, ServiceKey<T> key);
}
```

### 2.3 移植要点与差异处理

| Cordis 机制 | jcordis 实现 | 差异说明 |
|---|---|---|
| Proxy 属性解析 | `get(String)` / `get(ServiceKey<T>)` 显式方法 | 无法模拟透明属性访问，改为方法调用 |
| 原型链 context 继承 | `ContextImpl.parent` 委托链 | 语义等价（属性查找沿 parent 上溯） |
| `ctx.effect()` 四形态 | `EffectResult`（Void/Single/Multiple）+ `EffectRunner` | 生成器 yield 多个 dispose → `Multiple` |
| epoch 字符串拼接 | `long epochHash`（uid 序列的 hash） | 语义等价 |
| `internal/update` waterfall | 内置事件 + `EventBus.waterfall` | 不变 |
| `isolate` symbol 表 | `Map<String, ServiceKey<?>>` + 每上下文一份 | symbol 唯一性 → Key 对象 equals 唯一 |
| `WeakRef<Fiber>` | `WeakReference<Fiber>` | 直接映射 |
| `PromiseLike<Fiber>` | `fiber.await()` 返回 `CompletableFuture<Fiber>` | 语义等价 |
| callable logger | `ctx.logger().named("x").info(...)` | 方法链替代函数调用 |
| traceable 隐式上下文 | **不做**（ThreadLocal 方案有泄漏风险） | 明确取舍：Java 生态靠参数传递 |

### 2.4 测试计划（对照 Cordis 测试翻译）

| Cordis 测试 | jcordis 测试 | 覆盖点 |
|---|---|---|
| events.spec.ts | EventBusTest | 五种派发模式、prepend、过滤 |
| fiber.spec.ts | FiberTest | 状态机、效应收集、逆序 dispose、重载 |
| plugin.spec.ts | PluginRegistryTest | 三种插件形态、注入、PromiseLike |
| service.spec.ts | ServiceTest | provide/get/check、config 合并 |
| isolate.spec.ts | IsolationTest | 隔离域、intercept 链 |
| reflect.spec.ts | ServiceRegistryTest | 服务解析、notify、重载触发 |
| dispose.spec.ts | DisposalTest | 全量清理、部分 dispose |
| logger.spec.ts | LoggerTest | 格式化、级别、exporter |
| decorator.spec.ts | (适配) | @Inject 装饰器 → 构造器注入 |
| invoke.spec.ts | (适配) | callable → 方法链 |

### 退出标准

- 全部核心测试通过（对照 Cordis core 12 个测试文件翻译覆盖率 > 90%）。
- 一个"插件加载-提供服务-依赖响应-卸载回滚"的端到端演示测试通过。

---

## Phase 3 — 加载器 jcordis-loader（第 6-8 周）

**目标**：声明式插件树 + 配置调和 + 隔离域。

### 3.1 模块结构

```
io.jcordis.loader
├── Entry          # 条目（id/name/config/group/disabled/inject）
├── EntryTree      # 树（root、store、resolve/entries/await）
├── EntryGroup     # 组（create/remove/update/stop）
├── Loader         # Loader 服务（监听 internal/update、plugin 事件）
├── config/
│   ├── ConfigParser   # JSON/YAML 解析（Jackson）
│   └── ConfigResolver # 插值解析（见 3.3）
└── isolate/       # LocalRealm / GlobalRealm / RealmManager
```

### 3.2 移植要点

| Cordis | jcordis | 说明 |
|---|---|---|
| `tree.import(name)` 动态 import | `Loader.resolvePlugin(name)`：先查 `builtins`（SPI 注册），再 `Class.forName` | 见 3.4 |
| `entry.fiber.update(config)` 热更新 | 相同（调 Fiber.update） | 配置变化自动重启插件 |
| `internal/update` 写回 Entry | 相同 | 插件自更新配置持久化 |
| 配置 `write()` 到文件 | `ConfigStore` 接口（内存/文件实现） | Include 插件提供文件实现 |
| isolate 7 步切换 | 简化：重新计算 isolate 图 → 生成 diff → 重载 → 通知 | 5 步即可（去掉原型操作） |

### 3.3 配置求值 —— 关键安全决策

**Cordis 使用 `with(ctx){eval()}` 执行任意 JS。Java 有 3 个选项：**

| 方案 | 能力 | 风险 | 推荐度 |
|---|---|---|---|
| A. 纯数据插值（占位符 `${a.b}` + Map 查找） | 仅取值 | 无 | ⭐⭐⭐ 默认 |
| B. SpEL（Spring Expression Language） | 完整表达式 | 需沙箱化（`SimpleEvaluationContext`） | ⭐⭐ 进阶 |
| C. JEXL / MVEL | 表达式 | 同 B | ⭐ 慎用 |

**决策**：默认方案 A（安全、零依赖），预留 `ExpressionEvaluator` 接口，未来可插拔 SpEL。文档中明确此差异。

### 3.4 插件解析策略

```
Phase 3 默认：编译期依赖 + ServiceLoader
  ├─ loader 内置插件（group 等）走 SPI 注册表
  └─ 用户插件：classpath 上的类名映射（name → FQCN 配置映射表）

Phase 4 增强（可选）：
  ├─ 动态编译：javax.tools.JavaCompiler 编译 .java 源 → URLClassLoader 加载
  └─ 动态 jar：扫描 jar 目录 + URLClassLoader（对应 Cordis 的 npm 包热装）
```

### 退出标准

- 配置 YAML → Entry 树 → 插件实例化 → 服务提供 → 依赖注入全链路跑通。
- `isolate` 测试：LocalRealm（#）、GlobalRealm（@）行为与 Cordis 一致。
- 配置热更新：修改配置文件 → 树 diff → 相关 Fiber 重载。
- include/group 两个插件模块移植完成（Phase 3.5，若时间允许并入本阶段）。

---

## Phase 4 — 周边插件 + 工具（第 9-10 周）

| 模块 | 移植内容 | 工作量 |
|---|---|---|
| jcordis-plugin-timer | timeout/interval/throttle/debounce，基于 `ScheduledExecutorService` + effect 自动清理 | 小 |
| jcordis-plugin-logger-console | 控制台 exporter + ANSI 颜色（JANSI 或自绘） | 小 |
| jcordis-plugin-include | YAML/JSON 配置树 + patches + 原子写（Java `Files.move` ATOMIC_MOVE） | 中 |
| jcordis-plugin-group | 重导出 Group | 极小 |
| jcordis-utils | DisposableList、List（effect 化集合） | 小 |

### 4.1 Timer 设计要点

```java
public class TimerService extends Service {
    ScheduledExecutorService scheduler;  // 虚拟线程友好：Executors.newVirtualThreadPerTaskExecutor() 或计划线程池
    Disposable timeout(Runnable cb, Duration delay);            // ctx.effect 包裹
    CompletableFuture<Void> timeout(Duration delay);            // Promise 形态
    Disposable interval(Runnable cb, Duration delay);
    Stream<Instant> interval(Duration delay);                   // 无回调形态（对应 AsyncIterable）
    WithDispose<Runnable> throttle(Runnable cb, Duration delay);
    WithDispose<Runnable> debounce(Runnable cb, Duration delay);
}
```

### 4.2 Logger-Console 设计要点

- `ConsoleExporter`：输出到 stdout/stderr，ANSI 256 色（对照 Cordis `c256` 色板），名称哈希 → 色号。
- `%s %d %o %C` printf 格式化用 `String.format` + 自定义 Formatter 表。
- 与 SLF4J 的关系：提供可选桥接（`LoggerService` 可注册一个 SLF4J 转发 exporter），但核心保持自研 exporter 语义。

### 退出标准

- 每个插件模块有对应测试（timer 语义对齐 Cordis timer.spec.ts；include 对齐 patch.spec.ts）。
- 一个 `include → loader → timer/logger` 组合的集成测试通过。

---

## Phase 5 — CLI 与脚手架（第 11 周，可选）

| 方案 | 说明 | 推荐度 |
|---|---|---|
| A. jcordis-cli（对应 create-cordis） | 交互式生成项目：`jcordis create my-app`，模板来自 git 仓库或内嵌 | ⭐⭐⭐ |
| B. Maven Archetype | `mvn archetype:generate -DarchetypeGroupId=io.jcordis` | ⭐⭐ |
| C. 两者都做 | CLI 内部调用 archetype 或模板引擎 | ⭐ |

模板内容：`pom.xml` + `jcordis.yml` + 一个示例插件 + 入口类。

---

## Phase 6 — HMR 等价物（第 12 周，高风险，可裁剪）

**Cordis 的 HMR 依赖 V8 模块系统内部状态，Java 无法直接对应。** 提供三个递减方案：

| 方案 | 机制 | 能力 | 复杂度 |
|---|---|---|---|
| A. 容器重建（推荐） | 监听 classpath/配置文件变更 → 构建新 Context（根）+ 重新加载全部插件 → 原子切换引用 | 全量热重载 | 中 |
| B. 隔离 ClassLoader | 每插件一个 URLClassLoader，卸载 = 关闭 CL + 重建 | 单插件热替换（限无静态状态插件） | 高 |
| C. JRebel / DCEVM 集成 | 字节码热替换 | 方法级 | 外部依赖 |

**决策建议**：Phase 6 仅实现方案 A（开发体验已足够好），方案 B/C 作为 stretch goal。若不实现 HMR，则 **Phase 6 可整体裁剪**，并不影响核心价值。

---

## Phase 7 — 文档、示例与发布（第 13 周）

- [ ] 移植 Cordis 论文的核心概念到 Java 语境（`docs/` 扩充）
- [ ] 3 个示例应用：
  1. `examples/hello-world`：最小插件
  2. `examples/service-graph`：服务提供/依赖/隔离域演示
  3. `examples/config-app`：YAML 配置 + include + group
- [ ] 发布准备：Maven Central 坐标、LICENSE、javadoc
- [ ] 与 Cordis 行为差异对照表（`docs/compatibility.md`）—— 明确"移植了什么、改了什么、砍了什么"

---

## 里程碑总览

| 里程碑 | 时间 | 内容 | 交付 |
|---|---|---|---|
| M1 | 第 1 周 | 脚手架 + CI | 8 模块空工程、CI 绿 |
| M2 | 第 2-5 周 | **jcordis-core** | Context/Fiber/Events/Service/Registry/Logger 全测试通过 |
| M3 | 第 6-8 周 | **jcordis-loader** + include/group | 声明式配置树 + 隔离域 + 热更新 |
| M4 | 第 9-10 周 | timer / logger-console / utils | 插件全家桶 + 集成测试 |
| M5 | 第 11 周 | jcordis-cli | 脚手架可用 |
| M6 | 第 12 周 | HMR 等价物（方案 A） | 开发模式热重载 |
| M7 | 第 13 周 | 文档 + 示例 + 发布 | 可对外使用 |

> 关键路径：M2（core）→ M3（loader）。M2 完成后即可并行推进 timer/logger-console/utils（依赖仅 core）。
> 裁剪策略：M5/M6 均可在时间紧张时跳过；M3 中的 include 可后置。

---

## 风险登记册

| 风险 | 影响 | 缓解 |
|---|---|---|
| Java 无动态属性访问，Context API 形态差异大 | 高 | 提前在 M2 用接口式 API 冻结设计；用 `ServiceKey<T>` 保持类型安全 |
| 配置表达式求值安全化 | 中 | 默认纯数据插值；预留 Evaluator 接口 |
| Fiber 重载并发模型（虚拟线程 vs 事件循环） | 中 | 用 `StructuredTaskScope` + `CompletableFuture` 复刻串行 inertia 语义；写并发测试 |
| HMR 无法移植 | 中 | 降级为容器重建方案；明确定位差异 |
| 插件动态加载（jar/编译）复杂度 | 中 | Phase 3 先用编译期依赖，动态加载列为增强项 |
| 团队对 Cordis 语义理解偏差 | 中 | 严格对照 Cordis 测试翻译；保留 `docs/compatibility.md` |

---

## 附录：Phase 2 详细任务分解（M2）

```
W1: Context 接口 + ContextImpl + ServiceKey + 基本服务注册
    - 测试：context 创建、extend、isolate、intercept
W2: Fiber + Effect 体系（状态机、EffectResult、逆序 dispose、epoch）
    - 测试：fiber.spec 全部翻译
W3: EventBus 五种模式 + 内部事件（internal/update 等）
    - 测试：events.spec 全部翻译
W4: PluginRegistry + 服务响应式通知（provide/notify/重载）
    - 测试：plugin/service/isolate/reflect spec 翻译
W5: Logger + DisposableList + 集成演示
    - 测试：logger.spec + 端到端演示
```

每周末做一次"对照 Cordis 源码逐文件 review"，确保语义未漂移。
