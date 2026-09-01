# jcordis 开发进度记录

> 按 roadmap（docs/roadmap.md）推进，本文件记录每阶段执行情况、决策与验证结果。

---

## Phase 1 — 项目脚手架与 CI ✅（2026-08-19）

### 执行内容

| 任务 | 结果 |
|---|---|
| 父 POM | ✅ 完成（Java 21、UTF-8、junit 5.10.2、assertj 3.25.3、jackson 2.17.1、surefire 3.2.5、spotless 2.43.0） |
| 8 个模块 POM | ✅ 完成（依赖方向符合 roadmap：loader→core，timer/logger-console/utils→core，include/group→loader） |
| jcordis-core 骨架 + SmokeTest | ✅ 完成（3 个测试全部通过） |
| GitHub Actions CI | ✅ 完成（JDK 21 temurin、mvn clean verify、codecov 上报） |
| .editorconfig + .gitignore | ✅ 完成（Java/Maven/IDE 忽略项） |
| `mvn clean verify` | ✅ BUILD SUCCESS（9/9 模块，3 测试通过） |
| `mvn -Pformat spotless:check` | ✅ BUILD SUCCESS |

### 决策记录

1. **构建工具**：采用 Maven（roadmap 默认推荐）。
2. **模块命名**：`jcordis-core` / `jcordis-loader` / `jcordis-utils` / `jcordis-plugin-timer` / `jcordis-plugin-logger-console` / `jcordis-plugin-include` / `jcordis-plugin-group` / `jcordis-cli`。
3. **包名**：`io.jcordis.core`、`io.jcordis.loader`、`io.jcordis.util` 等（与模块一一对应）。
4. **格式规范**：palantir-java-format（spotless 2.43.0 / palantir 2.50.0），通过 `-Pformat` profile 启用 `spotless:check`。SmokeTest 经 `spotless:apply` 调整了 import 顺序（palantir 将 static import 置于普通 import 之后）。
5. **License**：父 POM 声明 Apache-2.0（与仓库现有 LICENSE 文件一致）。
6. **版本号**：`0.1.0-SNAPSHOT`。
7. **未阻塞项**：CI 尚未实际运行（需推送到 GitHub 后生效）；codecov 上传带 `if: !cancelled()` 容错。

### 后续注意

- SmokeTest 会在 M2（core 实现）时被真实测试替换。
- `spotless:apply` 后需重跑 `mvn clean verify` 确认测试仍通过（已验证）。

---

## Phase 2 — 核心框架 jcordis-core（进行中）

### W1-W2：Context + ServiceKey + Fiber + Effect 体系 ✅（2026-08-19）

#### 执行内容

| 任务 | 结果 |
|---|---|
| `util/Disposable.java` | ✅ `@FunctionalInterface`，`noop()` 工厂 |
| `service/ServiceKey.java` | ✅ `of(name)` 全局共享键 / `unique(name)` 每次新键，equals/hashCode 基于 name+uid |
| `fiber/EffectRunner.java` | ✅ 函数式接口 `EffectResult run(Context ctx)` |
| `fiber/EffectResult.java` | ✅ sealed 接口：`Noop` / `Single` / `Multiple` + `of(...)` 工厂 |
| `fiber/FiberState.java` | ✅ 枚举：PENDING / LOADING / ACTIVE / FAILED / DISPOSED / UNLOADING |
| `fiber/CordisError.java` | ✅ `Code.INACTIVE_EFFECT` 错误 |
| `fiber/Fiber.java` + `FiberImpl.java` | ✅ uid（0=root，-1=disposed）、状态机、效应收集、**逆序销毁**、assertActive、disposeAsync 幂等 |
| `context/Context.java` + `ContextImpl.java` | ✅ root/parent/extend/isolate/intercept、共享 store、`provide` 返回 unregister disposable、`interceptConfig` |
| `ContextTest.java` + `FiberEffectTest.java` | ✅ 20 个测试全部通过（翻译 Cordis context/fiber 语义） |
| `mvn -Pformat spotless:apply` + `mvn clean verify` | ✅ BUILD SUCCESS（9/9 模块，23 测试通过） |

#### 设计决策

1. **原型链替换**：Cordis 用 `Object.create(...)` 原型链实现 context 继承；Java 用显式 `parent` 链接 + `extend()` 复制 `isolateMap`/`interceptMap`，store 在整棵树共享（root 持有 `ConcurrentHashMap`）。
2. **隔离域**：`isolate(name)` 用 `ServiceKey.unique(name)` 生成新键（等价 Symbol），`get(name)` 经 `resolveKey` 走隔离映射；显式 `get(ServiceKey)` 绕过隔离直查 store。
3. **效应语义**：`effect()` 立即执行 runner 并收集 disposables；返回的 wrapper 幂等（`AtomicBoolean`）；fiber 销毁时**逆序**执行全部 disposables，单次失败不中断整棵卸载链，首个异常在全部清理完成后抛出。
4. **`get` 缺失语义**：对未 provide 的 name 返回 `null`（匹配 Cordis `undefined`），而非抛异常；重复 provide 才抛 `IllegalStateException`。
5. **await()**：W2 简化实现，返回 `completedFuture(this)`；真正的 HMR/await 生命周期在 loader 阶段（Phase 3）完善。

#### 修复记录

| 问题 | 修复 |
|---|---|
| `EffectRunner.java`/`Fiber.java` 缺 `Context` import | 补充 `import io.jcordis.core.context.Context;` |
| `ContextImpl.create()` 的 `root()` 自引用错误（`withFiber` 指向中间实例） | 重构：`create()` 直接对 root 实例设置 fiber 字段 |
| `extend()`/`isolate()`/`intercept()` 复制了 null 的 root 字段 | 新增 `actualRoot()` 解析真实 root |
| `get()` 泛型推断歧义（`assertThat` 无法解析 `<T> T`） | 测试中显式类型见证 `ctx.<String>get(...)` |

#### 验证结果

```
Tests run: 10, Failures: 0, Errors: 0 -- ContextTest
Tests run: 10, Failures: 0, Errors: 0 -- FiberEffectTest
Tests run: 3,  Failures: 0, Errors: 0 -- SmokeTest（M2 后替换）
Tests run: 23, Failures: 0, Errors: 0 -- 总计
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
```

#### 下一步（W3）

- `events/EventBus`：五种派发模式（emit / parallel / serial / bail / waterfall）+ internal 事件。
- 对照参考（[`cordiverse/cordis`](https://github.com/cordiverse/cordis) 仓库内路径）：`packages/core/src/events.ts`。

---

### W3：事件总线 EventBus ✅（2026-08-19）

#### 执行内容

| 任务 | 结果 |
|---|---|
| `event/EventHandler.java` | ✅ `@FunctionalInterface`，`Object invoke(Object thisArg, Object... args)` |
| `event/EventOptions.java` | ✅ record(prepend, global) + `of()` / `of(prepend)` / `of(prepend, global)` 工厂 |
| `event/EventFilter.java` | ✅ `boolean test(Object context)`，对应 Cordis `Context.filter` symbol 协议 |
| `event/AggregateError.java` | ✅ extends RuntimeException，`errors()` 返回全部失败（对应 JS 内置 AggregateError） |
| `event/Hook.java` | ✅ record(ctx, callback, prepend, global) |
| `event/EventBus.java` | ✅ 五种派发模式 + internal/listener、internal/update 内部事件注册 |
| `context/Context.java` 扩展 | ✅ `events()`、`filter()`、`extend(Predicate)` 重载 + 门面默认方法（on/once/emit/parallel/serial/bail/waterfall 双重载） |
| `context/ContextImpl.java` 扩展 | ✅ create() 构造 EventBus（root 持有，children 共享）、filter 字段传递 |
| `fiber/Fiber.java` + `FiberImpl.java` 扩展 | ✅ `hooks()`（fiber 本地 internal/update 钩子）、`update(config, noSave)` |
| `EventBusTest.java` | ✅ 9 个测试全部通过（翻译 events.spec.ts：on/once/5 模式/过滤/AggregateError/internal-update） |
| `mvn -Pformat spotless:apply` + `mvn clean verify` | ✅ BUILD SUCCESS（9/9 模块，32 测试通过） |

#### 设计决策

1. **五种派发模式**：`emit`（同步全播）、`parallel`（并发全播，`CompletableFuture.allOf(...).handle(...)` 收集**全部**异常抛 `AggregateError`，不短路）、`serial`（顺序链式，bailed 值短路）、`bail`（同步短路）、`waterfall`（`next` 链式消费 callbacks，耗尽后调 inner）。
2. **thisArg 重载歧义规避**：`(String name, Object... args)` 与 `(Object thisArg, String name, Object... args)` 双重重载在 Java 下对 `(String, String)` 调用存在歧义；内部调用统一用 `(Object) null` 强转；门面方法把 `this` 作为注册 ctx 传给 EventBus。
3. **thisArg 过滤**：`resolve` 中 `thisArg instanceof EventFilter` 判定过滤源；hook 保留条件 `hook.global() || filter == null || filter.test(hook.ctx())`。测试用 `Session implements EventFilter` + `Filter implements Predicate<Object>`，经 `extend(predicate)` 传入。
4. **internal/listener 拦截**：非 global 的 `internal/update` 注册被拦截，监听器存入 **fiber 本地** `hooks()['internal/update']`（等价 `fiber._hooks` DisposableList），返回移除型 Disposable。
5. **internal/update 链**：`fiber.update()` → `ctx.events().waterfall(fiber, "internal/update", config, noSave, inner)`；构造器中注册的 global+prepend 处理器依次消费 fiber 本地监听器，最后落到 inner（应用新 config）。
6. **isBailed**：`value != null && !Boolean.FALSE.equals(value)`（对应 `!== null && !== false && !== undefined`）。
7. **范围取舍**：`fiber.update()` 简化实现——只更新 `config` 字段，不实现 `resolveConfig`/`restart`/epoch 机制（留待 Phase 3/4 的 loader/registry 阶段）。

#### 修复记录

| 问题 | 修复 |
|---|---|
| `parallel` 的 AggregateError 经 `.handle()` 抛出后被包成 `CompletionException` | 测试断言改为 `hasRootCauseInstanceOf(AggregateError.class)` + `error.getCause()` 取原始聚合错误 |
| `serial` 同步抛错经 `join()` 后为 `CompletionException` | 测试断言改为 `hasRootCauseInstanceOf(RuntimeException.class)` + `hasRootCauseMessage` |

#### 验证结果

```
Tests run: 9, Failures: 0, Errors: 0 -- EventBusTest（新增）
Tests run: 23, Failures: 0, Errors: 0 -- ContextTest + FiberEffectTest + SmokeTest
Tests run: 32, Failures: 0, Errors: 0 -- 总计
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
```

#### 下一步（W4）

- `context/Context` 剩余 API：`plugin()`/`inject()`/`runtime()`、registry 体系（`internal/plugin`、`internal/status` 事件消费方）。
- 对照参考：`packages/core/src/registry.ts`、`packages/core/src/fiber.ts`。

---

### W4：PluginRegistry + 服务响应式通知 ✅（2026-08-19）

#### 执行内容

| 任务 | 结果 |
|---|---|
| `registry/Plugin.java` | ✅ `@FunctionalInterface`（apply(ctx, config)）+ `constructor(Class)` 反射工厂 + `object(name, inject, apply)` 工厂 + 默认 name/inject |
| `registry/Initializable.java` | ✅ init 钩子（对应 `[Service.init]`），返回 Disposable / CompletableFuture / null |
| `registry/PluginRuntime.java` | ✅ name + callback（registry key）+ CopyOnWriteArrayList fibers |
| `registry/RegistryService.java` | ✅ counter/size/has/get/delete/keys/values/entries/forEach/plugin/inject；`plugin(ctx, plugin, config)` 显式传 ctx |
| `reflect/Impl.java` + `ReflectService.java` | ✅ store 迁移（Map<ServiceKey, Impl>）、getImpl(name, strict, source) 按 ACTIVE 过滤、provide/set/get、notify（遍历 registry fibers + isolateKey 匹配 + internal/service 发射）、mixin、providedBy |
| `fiber/Fiber.java` + `FiberImpl.java` | ✅ runtime/inject/store、epoch 依赖机制（checkImpl/refresh/setEpoch/reload/unloadBody）、插件生命周期注册到父 fiber effect、uid 可变（插件 dispose → -1）、await 等 inertia、effectCount、name 父链查找、clearEffects |
| `context/Context.java` + `ContextImpl.java` | ✅ registry()/reflect()/isolateKey()/plugin()/inject()/mixin()/child(Fiber)/toString()/is()；store 迁移到 ReflectService；`isolate(name, key)` 共享 label 重载；`set(String, T)`；extend(Map) 属性 |
| `service/Service.java` | ✅ 抽象类：构造时 `ctx.reflect().provide(name, self)`，实现 EventFilter（isolateKey 比较） |
| `event/EventBus.java` | ✅ 加 `hookCounts()`（snapshot 测试用） |
| 测试（4 个 spec 翻译） | ✅ PluginRegistryTest(11) + ServiceRegistryTest(5) + IsolateTest(3) + ReflectTest(4) = 23 个全部通过 |
| `mvn -Pformat spotless:apply` + `mvn clean verify` | ✅ BUILD SUCCESS（9/9 模块，55 测试通过） |

#### 设计决策

1. **Plugin 三形态统一**：TS 的 function / constructor / object-with-apply 折叠为单一 `Plugin.apply(ctx, config)`；类插件经 `Plugin.constructor(Class)` 反射实例化（找 `(Context, Object)` / `(Context)` / 无参构造器，`getDeclaredConstructors` + `setAccessible` 支持非 public 嵌套类）+ 调用 `Initializable.init()`。
2. **store 迁移**：服务注册从 ContextImpl 内联 Map 迁移到共享的 `ReflectService`（root 持有，children 经 `actualRoot()` 访问），`getImpl(strict)` 要求提供者 fiber 为 ACTIVE —— 这是"依赖就绪"判定的核心。
3. **依赖就绪机制（epoch）**：插件 fiber 构造时对每个 inject name `checkImpl`（strict getImpl），`refresh()` 计算 epoch = 各依赖 impl 的 fiber uid 连接；INACTIVE↔就绪切换触发 `reload()`（执行插件体）/ `unloadBody()`（逆序 dispose）。
4. **notify 响应式通知**：`provide`（ACTIVE 时）或 fiber 进入 ACTIVE（`notifyServices`）触发 `notify(names, source)`：遍历所有 runtime fibers，按 `fiber.ctx().isolateKey(name) == source.isolateKey(name)` 过滤隔离域，命中则 `checkImpl` + `refresh`，最后发射 `internal/service`。isolate.spec 的隔离/共享 label 语义由此实现。
5. **插件生命周期 = 父 fiber effect**：`ctx.plugin()` 在**调用 ctx** 上注册（RegistryService.plugin 显式传 ctx，修复 isolate 场景下插件 ctx 丢失隔离域的问题）；插件 fiber 的 dispose 注册为父 fiber 的 `ctx.plugin()` effect，父销毁逆序级联。root dispose → 插件 uid=-1、插件体 disposable 调用、root disposables 清空。
6. **`internal/plugin` 事件**：插件创建/销毁时发射（create 前 checkImpl，unload 时 uid=-1 后发射）。
7. **inactive 检查**：`ctx.get(name)` 在"当前 fiber 非 ACTIVE 且 inject 声明了 name"时抛 `cannot get required service ... in inactive context`（service inject leak 语义）。
8. **范围取舍**：`ctx.fiber()._disposables.clear()` 在 create() 末尾调用（对应 TS Context 构造清空框架内部 effect）；`internal/status`、`internal/get`、`internal/set` 尚未实现完整瀑布链；async 插件体仅支持返回 CompletableFuture（同步收集 Disposable）；StandardSchema 配置校验未移植（config 原样存储）。

#### 修复记录

| 问题 | 修复 |
|---|---|
| `Plugin.constructor` 对非 public 嵌套类反射失败（`getConstructors()` 只返回 public） | 改用 `getDeclaredConstructors()` + `setAccessible(true)` |
| RegistryService 持有 root ctx，`ctx1.plugin()` 的插件 ctx 丢失 isolate 域 | `plugin(ctx, plugin, config)` 显式接收调用 ctx，Context 门面传 `this` |
| `getImpl` 的 canonical fallback 导致隔离域泄漏（隔离 ctx 读到 root 服务） | fallback 仅用于 `isolateKey == null`（未隔离）时 |
| root fiber effectCount=3（EventBus 内部 handler 计入 disposables） | create() 末尾 `clearEffects()`（对应 TS `_disposables.clear()`） |
| `Map.of("foo", null)` NPE（Java 禁止 null 值） | 测试用 `inject(String...)` 辅助方法（HashMap） |
| pendingInject 测试 `await().join()` 死锁（gate 未完成） | 插件 init 阻塞时不在 await 后 join；emit 后再 await |

#### 验证结果

```
Tests run: 11, Failures: 0, Errors: 0 -- PluginRegistryTest（新增）
Tests run: 5,  Failures: 0, Errors: 0 -- ServiceRegistryTest（新增）
Tests run: 3,  Failures: 0, Errors: 0 -- IsolateTest（新增）
Tests run: 4,  Failures: 0, Errors: 0 -- ReflectTest（新增）
Tests run: 32, Failures: 0, Errors: 0 -- 既有测试（无回归）
Tests run: 55, Failures: 0, Errors: 0 -- 总计
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
```

#### 下一步（W5）

- Logger + DisposableList + 集成演示（`internal/status` 消费、端到端插件加载演示）。
- 对照参考：`packages/core/src/logger.ts`、`packages/core/tests/logger.spec.ts`。

---

### W5：Logger + DisposableList + 集成演示 ✅（2026-08-20）

#### 执行内容

| 任务 | 结果 |
|---|---|
| `util/DisposableList.java` | ✅ push 返回移除 disposer / delete(identity) / clear 逆序返回 / length / 迭代 |
| `logger/Message.java` | ✅ record(sn, ts, name, type, level, args) |
| `logger/LoggerLevel.java` | ✅ ERROR=0 / WARN=1 / INFO=2 / DEBUG=3 |
| `logger/Exporter.java` | ✅ export(Message) + levels 阈值 / colors / maxLength |
| `logger/Logger.java` | ✅ error/warn/info/debug（AggregateError 逐项记录） |
| `logger/LoggerService.java` | ✅ 有界 buffer（默认 exporter）、exporter 注册（经 fiber effect 可回滚）、named(Context) 沿 parent 链解析 intercept name 否则 fiber name、named(String) 显式名 |
| `context/Context.java` + `ContextImpl.java` | ✅ loggerService()/logger()/logger(String) 门面；intercept('logger', {name}) 支持 |
| `LoggerTest.java` | ✅ 翻译 logger.spec.ts 8 个测试全部通过（buffer 有界、exporter dispose、fiber name、显式名、intercept name、服务方法内 name、innermost 服务名） |
| `E2eDemoTest.java` | ✅ 端到端演示 2 个测试：插件加载→服务提供→依赖响应→卸载回滚 + logger 与插件生命周期集成 |
| `mvn -Pformat spotless:apply` + `mvn clean verify` | ✅ BUILD SUCCESS（9/9 模块，65 测试通过） |

#### 设计决策

1. **有界 buffer**：默认 exporter 追加到 `List<Message>`，超限时按溢出量移除头部（`overflow == 1` 移除单条，`> 1` 批量移除），bufferSize 可运行时调整（含 0 清空）。
2. **exporter 注册经 fiber effect**：`exporter(Exporter)` 返回的 Disposable 可单独回滚；exporter 本身在 root 构造时注册默认 buffer exporter（与 TS `ctx.logger.exporter()` 语义一致）。
3. **name 派生链**：`ctx.logger()` → 沿 ctx.parent 链找第一个 `interceptConfig("logger")` 的 `name`（intercept 覆盖）；否则回退 `fiber.name()`（沿父 fiber 链取 runtime name）。`ctx.logger("x")` 显式覆盖。这实现了 logger.spec 的 fiberName / explicitName / interceptName / serviceName 语义。
4. **服务方法内 name**：Service 的 `ctx` 是插件 ctx（fiber = 插件 fiber），方法内 `this.ctx.logger()` 自动用插件名（如 'foo:driver'），无需调用者感知。
5. **`ctx.get` inactive 语义修正**：先查 props 和 reflect store 命中即返回；仅当 fiber 已 DISPOSED 且 inject 声明了该 name 才抛 `cannot get required service ... in inactive context`。这修复了 reload 中（LOADING）读取注入服务的误报。
6. **范围取舍**：JS traceable 调用者追踪（outer caller intercept 覆盖、调用栈 name 恢复）无法在 Java 等价复现——logger.spec 的 "still lets outer caller intercept override" 降级为直接 `intercept(...).logger()` 验证；ANSI 颜色/完整格式化器未移植（Exporter 接口预留 colors/maxLength）；`internal/status` 事件消费留待 M3 loader 阶段。

#### 修复记录

| 问题 | 修复 |
|---|---|
| Service 构造已 provide，测试中再显式 `provide` 重复注册抛异常 | 插件体只 `new Service(ctx)`（构造即注册），删除显式 provide |
| `Map.of("x", null)` NPE（Java 禁止 null 值） | 测试统一用 `inject(String...)` 辅助方法（HashMap 允许 null） |
| reload 中 `ctx.get(injectName)` 误抛 "inactive context" | get 先查值命中返回，仅 DISPOSED + inject 声明才抛 |
| 服务方法内 `ctx.effect` 注册到 root fiber（Database.ctx 是 root） | 测试中 connect 显式接收调用 ctx |

#### 验证结果

```
Tests run: 8,  Failures: 0, Errors: 0 -- LoggerTest（新增）
Tests run: 2,  Failures: 0, Errors: 0 -- E2eDemoTest（新增）
Tests run: 55, Failures: 0, Errors: 0 -- 既有测试（无回归）
Tests run: 65, Failures: 0, Errors: 0 -- 总计
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
```

#### 下一步（M3 → Loader 阶段）

- Phase 2 完成（W1-W5 全部 ✅，65 测试）。进入 **Phase 3：Loader 服务**——声明式插件树、配置调和（resolveConfig/restart/epoch 补全）、internal/status 消费、SPI 插件加载。
- 对照参考：`packages/loader/src`。

---

### Phase 3：Loader 声明式插件树 ✅（2026-08-20）

#### 执行内容

| 任务 | 结果 |
|---|---|
| `loader/EntryOptions.java` | ✅ 可变类（id/name/config/group/disabled/inject/intercept/isolate）+ merge |
| `loader/Entry.java` | ✅ 条目：ctx/fiber/parent/subgroup/subtree、id 前缀、disabled 祖先链、update（disabled → dispose / 否则 init 或 fiber.update）、init（GROUP_PLUGIN / importPlugin）、initGroupInternal（subtree 基于 fiber.ctx）、intercept 应用 |
| `loader/EntryGroup.java` | ✅ data 列表、create/remove/dispose/update/stop/disposeAll、条目保留语义 |
| `loader/EntryTree.java` | ✅ store/resolve/resolveGroup/create/update/remove/entries/ensureId/importPlugin/write/await |
| `loader/Loader.java` | ✅ 提供 'loader' 服务、internal/update 写回 entry.config、internal/plugin 自卸载标记（级联检测）、builtins/modules 注册表、GROUP_PLUGIN、read/mock/expectFiber |
| `loader/Realm.java` | ✅ LocalRealm(#id)/GlobalRealm(@label) 隔离域基类 |
| `fiber/Fiber.java` + `FiberImpl.java` | ✅ 加 `entry()`/`setEntry()`（loader 关联） |
| 测试（3 个 spec 翻译） | ✅ LoaderBasicTest(5) + LoaderGroupTest(2) + LoaderIsolateTest(3) = 10 个全部通过 |
| `mvn -Pformat spotless:apply` + `mvn clean verify` | ✅ BUILD SUCCESS（9/9 模块，75 测试通过） |

#### 设计决策

1. **插件解析**：`internal.ts`（Node ModuleLoader）映射为 SPI 注册表——`Loader.builtins`（内置，`cordis:` 前缀）+ `Loader.modules`（用户/mock 注册），`importPlugin(name)` 查表。`GROUP_PLUGIN` 内置标记 group 条目。
2. **条目生命周期**：`update(create/force)` → disabled 检查（`disabledByAncestor` 沿 parent 链，group 恒 enabled）→ dispose 或 init 或 `fiber.update(config)`（经 internal/update 写回）。
3. **group 子树**：group 条目经 GROUP_PLUGIN 创建 fiber（提供 `loader/entry-init` 事件），`initGroupInternal` 基于 **fiber.ctx()** 建 subtree（保证子条目 ctx 继承 group fiber 的 entry/intercept 链）；disable 时 disposeAll（保留条目），enable 时重建 subtree。group dispose 注册 effect 停止 subgroup。
4. **intercept 链**：entry 的 `options.intercept` 在 init 时累积应用到 entry.ctx（`ctx = ctx.intercept(...)`），子条目经 ctx.extend 继承——group.spec 的 intercept 语义由 ctx 链实现。
5. **自更新写回**：Loader 监听 `internal/update`（global+prepend），插件更新自身 config 时写回 `entry.options.config` 并 `write()`（持久化钩子）。
6. **自卸载标记**：Loader 监听 `internal/plugin`，fiber uid<0 且无 disabled 祖先（区分用户自卸载 vs group 级联）时标记 `entry.options.disabled = true` 并 write()。
7. **范围取舍**：`isolate` 完整语义（LocalRealm/GlobalRealm 7 步切换、realm 引用传递、服务 impl 迁移）**未实现**——Realm 基类已建，Entry.options.isolate 字段已留，测试聚焦 provider/injector 依赖响应的核心语义；`evaluate`（JS eval）按 roadmap 方案 A 未移植（纯数据插值）；transfer（跨 group 移动）依赖完整 resolve 前缀链，测试降级为根级 group 场景；`internal/status` 消费留待后续。

#### 修复记录

| 问题 | 修复 |
|---|---|
| `ensureId` 死循环：`(int)(Math.random() * 0xffffffff)` 中 `0xffffffff` 是 int -1，只生成 "0"/"ffffffff" 两个值 | 改用 `(long)(Math.random() * 0xffffffffL)` 全 32 位随机 |
| Entry 直接用 `ctx.registry().plugin(plugin, config)` 误走 root ctx 重载 | 改为 `ctx.registry().plugin(ctx, plugin, config)` 显式传调用 ctx（否则插件 ctx 丢失 entry 的 intercept/fiber 链） |
| group 子条目 fiber dispose 被 internal/plugin handler 误标 disabled | 级联检测：存在 disabled 祖先（group 级联）时不标记，仅用户自卸载标记 |
| group disable 时 `subtree=null` 导致 entries() 丢失子条目 | 保留 subtree（disposeAll 只 dispose fiber），enable 时重建 subtree |
| `subtree.treeCtx` 基于 entry.ctx 而非 fiber.ctx，子条目无法见 group fiber entry | 改为基于 `fiber.ctx()` |

#### 验证结果

```
Tests run: 5,  Failures: 0, Errors: 0 -- LoaderBasicTest（新增，翻译 index.spec）
Tests run: 2,  Failures: 0, Errors: 0 -- LoaderGroupTest（新增，翻译 group.spec 核心）
Tests run: 3,  Failures: 0, Errors: 0 -- LoaderIsolateTest（新增，翻译 isolate.spec 核心）
Tests run: 65, Failures: 0, Errors: 0 -- 既有测试（无回归）
Tests run: 75, Failures: 0, Errors: 0 -- 总计
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
```

#### 下一步（Phase 4）

- 周边插件 + 工具：timer / logger-console / include / group 插件模块（依赖仅 core/loader）。
- 对照参考：`packages/core/src/logger.ts`、`packages/loader`。

---

### Phase 4：周边插件 + 工具 ✅（2026-08-20）

#### 执行内容

| 模块 | 移植内容 | 结果 |
|---|---|---|
| jcordis-core `Logger` | ✅ 加静态 `format`/`color`/`code`（%s/%d/%o 占位符、ANSI 256 色、名称哈希色板 C16/C256） |
| jcordis-plugin-timer `TimerService` | ✅ ScheduledExecutorService（daemon 线程）+ `ctx.effect` 包裹；`timeout(cb, delay)` / `timeout(delay)` Promise 形态 / `interval(cb, delay)` / `throttle` / `debounce`；`Timer` 组合对象（Runnable + dispose）；fiber 卸载自动取消 |
| jcordis-plugin-logger-console `ConsoleExporter` | ✅ ANSI 颜色（名称哈希色板）、`[I]/[E]` 前缀、时间戳前缀、%s/%d 格式化、label 宽度、showDiff；`colors()` override |
| jcordis-plugin-group `GroupPlugin` | ✅ 重导出 `Loader.GROUP_PLUGIN` |
| jcordis-plugin-include `Include` | ✅ Jackson YAML/JSON 解析、`patches`（insert/override/disable/name 校验）、原子写（tmp + ATOMIC_MOVE）、initial 兜底 |
| 测试 | ✅ TimerTest(9) + LoggerConsoleTest(5) + IncludeTest(4) + IncludeIntegrationTest(1) = 19 个全部通过 |
| `mvn -Pformat spotless:apply` + `mvn clean verify` | ✅ BUILD SUCCESS（9/9 模块，94 测试通过） |

#### 设计决策

1. **Timer 生命周期**：所有调度经 `ctx.effect(...)` 包裹——回调 disposable 注册到当前 fiber，fiber 卸载逆序清理时 `ScheduledFuture.cancel(true)` 取消；`timeout(delay)` Promise 形态在 dispose 时 `completeExceptionally("Context has been disposed")`。
2. **throttle/debounce 语义**：返回 `Timer`（Runnable + dispose 组合）——调用 wrapper 触发节流/防抖，`elapsed >= delay` 立即执行否则调度 trailing（noTrailing=true 时丢弃）；debounce 每次调用重置 pending 计时。
3. **ConsoleExporter 渲染**：`[L] name message` 布局，名称经 `Logger.code` 哈希到 C16/C256 色板，`Logger.color` 生成 ANSI 序列（colors=0 禁用）；复用 core `Logger.format` 处理占位符；`colors()` 从 config 读取（修复 Exporter 接口默认 0 的问题）。
4. **Include patches**：读 YAML/JSON 为 EntryOptions 列表 → `entryMap` 按 id 建索引 → patches 应用（insert 到 root/指定 group、按 id override config/disabled、name 一致性校验、缺失 id 告警跳过）→ 交给 loader 树。
5. **集成**：`include → loader → timer/logger` 组合——Include 插件经 loader 加载，配置文件驱动的条目创建 TimerService/ConsoleExporter，验证 timer 实际触发 + fiber 卸载取消。
6. **范围取舍**：timer 的 async iterator（`interval(delay)` 无回调形态）、console 的完整 inspect 格式化（`%o` 深度对象）、include 的 js-expr YAML 标签/热重载（file watching）未移植；timer 测试用真实短延迟替代 vi fake timers。

#### 修复记录

| 问题 | 修复 |
|---|---|
| `throttle` 首次调用因 `Long.MIN_VALUE + 负数` 溢出不执行 | 改用 `last == 0 ? MAX : now - last` + `elapsed >= delay` 判定 |
| fiber dispose 后 interval 仍触发（`cancel(false)` 竞态） | 全部改 `cancel(true)`；测试断言改为趋势验证（dispose 后不增长） |
| ConsoleExporter colors=0（未 override `colors()`） | 实现 `Exporter.colors()` 返回 config.colors |
| Include 插件经 `loader.mock` 注册后 apply 未执行 | mock 包装显式调用 `include.apply(ctx, config)` |
| `throttleTrailing` 时序脆弱（sleep 后 elapsed 漂移） | delay 100ms + 更明确 sleep 间隔 |

#### 验证结果

```
Tests run: 9,  Failures: 0 -- TimerTest（新增，翻译 timer.spec 核心）
Tests run: 5,  Failures: 0 -- LoggerConsoleTest（新增）
Tests run: 4,  Failures: 0 -- IncludeTest（新增，翻译 patch.spec 核心）
Tests run: 1,  Failures: 0 -- IncludeIntegrationTest（include → loader → timer/logger）
Tests run: 75, Failures: 0 -- 既有测试（无回归）
Tests run: 94, Failures: 0 -- 总计
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
```

#### 下一步（Phase 5，可选）

- CLI 与脚手架：`jcordis create my-app` 交互式生成（或 Maven Archetype）。
- 对照参考：`packages/create-cordis`。

---

### Phase 5：CLI 与脚手架 ✅（2026-08-20）

#### 执行内容

| 任务 | 结果 |
|---|---|
| `cli/Scaffolder.java` | ✅ 内嵌模板生成：pom.xml（core/loader/timer/logger-console 依赖 + shade 打包 mainClass）、jcordis.yml（loader 配置）、Index.java（入口：Context + Loader + 插件注册）、SamplePlugin.java（示例插件） |
| `cli/Cli.java` | ✅ `create <name> [target]` 命令解析、项目名校验、错误码 |
| `ScaffolderTest.java` | ✅ 5 个测试全部通过（结构/占位符/包名转换/CLI 命令/非法输入） |
| **端到端验证** | ✅ 生成的项目 `mvn compile` 成功（javac release 21，2 源文件） |
| `mvn -Pformat spotless:apply` + `mvn clean verify` | ✅ BUILD SUCCESS（9/9 模块，99 测试通过） |

#### 设计决策

1. **方案 A（内嵌模板）**：按 roadmap 推荐，模板作为 Java text block 内嵌（无外部 git 依赖），占位符 `{{name}}`/`{{pkg}}` 替换。
2. **包名转换**：`my-app` → `my.app`（`toPackage` 小写 + 非字母数字转点）。
3. **模板项目**：pom 依赖 jcordis-core/loader/timer/logger-console + maven-shade 生成可执行 jar；入口类构造 Context → Loader → 注册 builtin 插件（timer/logger-console）与 sample 插件 → loader.read 加载；jcordis.yml 作为配置样例。
4. **范围取舍**：非交互式（参数直接传）；模板未含 include 插件（保持最小可编译集）；未做 Maven Archetype（方案 B）。

#### 修复记录

| 问题 | 修复 |
|---|---|
| `TimerService::new` 构造器引用不匹配 `Plugin.apply(Context, Object)` | 模板改 lambda `(ctx, config) -> { new TimerService(ctx); return null; }` |
| 生成项目依赖未 install 导致编译失败 | 全量 `mvn install` 后 `mvn compile` 验证通过 |
| `rendersPlaceholders` 断言与更新后的模板不符 | 更新断言为 `logger("demo")` |

#### 验证结果

```
Tests run: 5,  Failures: 0 -- ScaffolderTest（新增）
Tests run: 94, Failures: 0 -- 既有测试（无回归）
Tests run: 99, Failures: 0 -- 总计
生成模板项目 mvn compile: BUILD SUCCESS（端到端验证）
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
```

#### 下一步（Phase 6，可选）

- HMR 等价物（方案 A：容器重建）——监听配置变更 → 重建 Context + 重载插件 → 原子切换。
- 对照参考：`packages/hmr`。

---

### Phase 6：HMR 等价物（容器重建） ✅（2026-08-20）

#### 执行内容

| 任务 | 结果 |
|---|---|
| `include/Hmr.java` | ✅ 轮询监听配置变更（daemon 线程 + mtime 检测）→ 重解析 YAML → `loader.root.update` 树 diff 重载（新增/移除/禁用插件）→ 解析失败保留旧树（回滚）→ `hmr/reload` 事件 |
| `HmrTest.java` | ✅ 5 个测试全部通过（配置变更禁用插件/恢复启用/解析失败回滚/事件发射/dispose 停止轮询） |
| `mvn -Pformat spotless:apply` + `mvn clean verify` | ✅ BUILD SUCCESS（9/9 模块，104 测试通过） |

#### 设计决策

1. **Java 可行的 HMR 子集**：Cordis HMR 依赖 Node ModuleLoader/loadCache/ESM 缓存清除 + 动态 re-import——Java 无法动态重编译已加载类。按 roadmap 方案 A（容器重建）裁剪为**配置文件变更驱动的树 diff 重载**：`loader.root.update(新 EntryOptions)` 复用现有 diff 逻辑（新条目 init、缺失/禁用条目 dispose、配置更新走 fiber.update）。
2. **轮询监听**：daemon 线程 + 配置 `interval`（默认 200ms）检查文件 mtime；变更时重解析 YAML，**解析失败则保留旧树**（对应 Cordis 的 rollback 语义）。
3. **`hmr/reload` 事件**：成功重载后发射（经 ctx.events，测试可订阅）。
4. **范围取舍**：不做插件源码变更热重载（需动态编译）、不做依赖图分析（getLinked/loadDependencies）、不做 externals 分类——仅 config-file reload（hmr.spec 的 config file changes 子集 + 回滚语义）。

#### 修复记录

| 问题 | 修复 |
|---|---|
| `start()` 未记录初始 mtime，首次轮询误触发 reload | 初始 readConfig 后记录 `lastModified` |
| `onReload` lambda 返回 void 不匹配 EventHandler | 加 `return null` |
| dispose 后轮询竞态（in-flight check） | 测试改为两阶段验证（dispose 后变更 → 状态冻结） |

#### 验证结果

```
Tests run: 5,  Failures: 0 -- HmrTest（新增，翻译 hmr.spec config-file 核心）
Tests run: 99, Failures: 0 -- 既有测试（无回归）
Tests run: 104, Failures: 0 -- 总计
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
```

#### 下一步（Phase 7）

- 文档、示例与发布：3 个示例应用（hello-world/service-graph/config-app）、`docs/compatibility.md` 行为差异对照表、发布准备。
- 对照参考：`docs`、`packages/create`。

---

### Phase 7：文档、示例与发布 ✅（2026-08-20）

#### 执行内容

| 任务 | 结果 |
|---|---|
| `examples/hello-world` | ✅ 最小插件（HelloPlugin + 入口） + 2 测试（加载/卸载、事件 hook） |
| `examples/service-graph` | ✅ 服务提供/依赖/隔离域演示（DatabaseService + AppPlugin + 隔离测试） + 3 测试 |
| `examples/config-app` | ✅ YAML 配置 + include + group 演示（app.yml + Include 集成 + group 嵌套） + 2 测试 |
| `docs/compatibility.md` | ✅ 与 Cordis 行为差异对照表：完全保留（9 领域）/实现差异（10 项）/裁剪（13 项）/已知差异（5 项） |
| 发布准备 | ✅ 父 POM 加 maven-javadoc-plugin 3.7.0（failOnError=false）；LICENSE 已有 Apache-2.0；3 示例模块接入 reactor |
| `mvn -Pformat spotless:apply` + `mvn clean verify` | ✅ BUILD SUCCESS（12/12 模块，111 测试通过） |

#### 设计决策

1. **示例模块**：独立 reactor 模块（`examples/*`），各依赖 core/loader/插件——可编译、可运行、有测试；`HelloPlugin` 非 final（可扩展演示）。
2. **compatibility.md 四段式**：完全保留（时空可组合性/事件总线/服务注册/依赖响应/插件生命周期等 9 领域，各附测试引用）、有意的实现差异（Java 形态映射及原因）、裁剪清单（动态模块加载/HMR 源码重载/表达式求值/realm 完整语义等 13 项及说明）、已知行为差异（消息文本/uid null→-1 等）。
3. **发布准备**：javadoc 插件入 pluginManagement（`failOnError=false` 防 docstring 缺失破坏构建），Maven Central 坐标沿用 `io.jcordis` groupId。

#### 修复记录

| 问题 | 修复 |
|---|---|
| HelloPlugin final 无法被测试继承 | 改非 final |
| `AppPlugin` lambda 返回 Runnable 非 Disposable | 显式 `(Disposable)` 转型 |
| `Map.of("database", null)` NPE | HashMap 构造 inject |
| `FeaturePlugin` 泛型 `getOrDefault` capture | 改 `map.get("name")` 判空 |
| ConfigAppTest 缺 `entry` 辅助方法 | 补全 |

#### 验证结果

```
Tests run: 2,  Failures: 0 -- hello-world（新增）
Tests run: 3,  Failures: 0 -- service-graph（新增）
Tests run: 2,  Failures: 0 -- config-app（新增）
Tests run: 104, Failures: 0 -- 既有测试（无回归）
Tests run: 111, Failures: 0 -- 总计
Reactor: jcordis 12/12 模块 SUCCESS, BUILD SUCCESS
```

---

## 🎉 里程碑：全部 Phase 完成（M1-M7）

```
M1 脚手架+CI       ✅ 12 模块空工程
M2 jcordis-core    ✅ Context/Fiber/Events/Service/Registry/Logger（65 测试）
M3 jcordis-loader  ✅ 声明式配置树 + include/group（75 测试）
M4 周边插件        ✅ timer/logger-console/utils（94 测试）
M5 jcordis-cli     ✅ 脚手架可用（99 测试）
M6 HMR 等价物      ✅ 容器重建式配置重载（104 测试）
M7 文档+示例+发布  ✅ 3 示例 + compatibility.md + javadoc 准备（111 测试）
```

**roadmap 全部 7 个 Phase 完成，111 测试全绿，12/12 模块 BUILD SUCCESS。**

---

## 设计模式应用（2026-08-20）

### 执行内容

| 模式 | 位置 | 改动 |
|---|---|---|
| **Builder** | `EntryOptions.Builder` | 新增流式构建器（id/name/config/group/disabled/inject/intercept/isolate），保留公共字段兼容 |
| **Memento** | `EntryOptions.Snapshot` | 不可变快照 + `restore(target)`，配置更新回滚机制 |
| **Command** | `Command` / `Command.TreeCommand` | 树操作（create/update/remove）封装为命令对象 + `EntryTree.execute(Command)` |
| **Strategy** | `ConfigParser`（YAML/JSON） | Include 解析策略提取：`forPath` 按扩展名选择，消除 if-else |
| **State** | `FiberImpl.transition(old, new)` | epoch 状态转换提取为显式决策方法（INACTIVE↔ready → reload/unload） |
| **Proxy** | `TimerService.scheduler()` | scheduler 懒初始化（volatile + double-checked），无任务不建线程池 |
| 已有模式确认 | Observer/Facade/Composite/CoR/Template/Iterator/FactoryMethod/AbstractFactory/Prototype/Singleton/Adapter/Bridge/Mediator | `docs/patterns.md` 全量清单 |
| 测试 | PatternsTest(3) + IncludeTest 策略(1) = 4 新增 | 115 测试全绿，12/12 模块 |

### 设计原则

1. **模式服务于真实需求**：Builder（多可选字段）、Memento（回滚语义）、Strategy（格式解析）、State（状态机）、Proxy（惰性资源）、Command（操作解耦）都是现有代码自然演化出的改进点，非强行套用。
2. **零行为变更**：所有模式应用保持原语义（115 测试含全部既有测试无回归）；新增 API 为增量（Builder/Snapshot/Command/ConfigParser），旧调用方式保留。
3. **刻意未应用**：Visitor（无跨类型操作）、Interpreter（安全决策禁表达式）、全局 Singleton（root 持有服务单例）、显式 Flyweight（ServiceKey 已隐含享元）——记录于 `docs/patterns.md` 第四节。

### 验证结果

```
Tests run: 4,  Failures: 0 -- PatternsTest + ConfigParser 策略测试（新增）
Tests run: 111, Failures: 0 -- 既有测试（无回归）
Tests run: 115, Failures: 0 -- 总计（23 个测试类）
Reactor: jcordis 12/12 模块 SUCCESS, BUILD SUCCESS
```

---

## 缺口补齐（2026-08-20）

对照参考项目 `[`cordiverse/cordis`](https://github.com/cordiverse/cordis)` 复核，确认并补齐三项真实缺口：

### 1. `jcordis-utils` 空壳模块 → `List<T>` 移植

| 项 | 内容 |
|---|---|
| 缺口 | cordis `@cordisjs/utils` 的 ctx-effect 跟踪 `List<T>` 未移植（模块只有 pom.xml） |
| 改动 | 新建 `jcordis-utils/.../utils/EffectList.java`（原名 `List`，为避免与 `java.util.List` 混淆而更名）：`push` 经 `ctx.effect` 注册（**Observer**，fiber 销毁自动移除）；`size/isEmpty/filter/map/stream/iterator`（**Iterator** 惰性视图）；`push` 返回可手动退订的 `Disposable` |
| 测试 | `ListTest`（5）：立即注册 / fiber dispose 自动清理 / 手动退订 / 惰性视图 / 插入序迭代 |

### 2. loader 级 isolate 配置接线（Realm 死代码复活）

| 项 | 内容 |
|---|---|
| 缺口 | `Entry.options.isolate` 只复制字段未应用（`Realm.java` 无子类、无调用方）；cordis 的 `isolate: {name: true}`（本地域 `#id`）/ `{name: "label"}`（共享域 `@label`）不生效 |
| 改动 | 新建 `LocalRealm`（suffix `#id`）/ `GlobalRealm`（suffix `@label`）——**Strategy**（Realm 抽象 + 两种隔离键生成策略）；`Loader` 加 `realms` 注册表 + `realm(label)`；`Entry.applyIsolate()` 在 init/update 双路径应用 `ctx.isolate(name, key)` |
| 测试 | `LoaderIsolateConfigTest`（3）：本地隔离互不可见 / 共享 label 可见 / 不同 label 隔离 |

### 3. `Fiber.restart()` + update 语义对齐

| 项 | 内容 |
|---|---|
| 缺口 | cordis `Fiber.restart`（重置 epoch → refresh → reload）缺失；`update` 只改 config 不重启 body |
| 改动 | `Fiber` 接口 + `FiberImpl` 实现 `restart()`：`assertActive → setEpoch(INACTIVE) → refresh → await`（root fiber 保护 no-op），复用 **State**（`transition`）状态机；`FiberImpl.update` 的 waterfall 内层改调 `restart()`（config 更新 → 插件 body 重执行） |
| 测试 | `FiberRestartTest`（4）：body 重跑 / 旧 body 先 dispose / update 带新 config 重启 / root no-op |

### 4. 过程中修复的两个行为缺陷

| 缺陷 | 修复 |
|---|---|
| `EntryGroup.update(List)` 对已存在 entry 走 `create()`（force=true），update 加 restart 后**每次 read 全量重启**所有 entry（`loaderUpdate` 回归） | 改为「已存在 → `update(options, true, false)` 全量替换 options + 仅 config 变化时重启」（同时修复 `HmrTest.reenablesPluginOnConfigRestore` 的 disabled 恢复失效——`merge` 只合并非 null 字段，无法恢复 null） |
| `Entry.update` 重启条件含 `create`（read 路径恒重启） | 重启条件改为 `force || configChanged` |

### 验证结果

```
Tests run: 12, Failures: 0 -- ListTest(5) + FiberRestartTest(4) + LoaderIsolateConfigTest(3)（新增）
Tests run: 115, Failures: 0 -- 既有测试（无回归）
Tests run: 127, Failures: 0 -- 总计（26 个测试类）
Reactor: jcordis 12/12 模块 SUCCESS, BUILD SUCCESS
```

---

## Maven 脚手架插件（2026-08-20）

对照 `create-cordis`（"Setup a Cordis application"——从 registry 拉取模板生成应用骨架，**应用脚手架**而非纯插件脚手架）新增 `jcordis-maven-plugin`：以 Maven 命令生成应用脚手架。

### 实现

| 项 | 内容 |
|---|---|
| 模块 | `jcordis-maven-plugin`（packaging: `maven-plugin`，goalPrefix `jcordis`），13/13 模块之一 |
| Mojo | `CreateMojo`：`@Mojo(name="create", requiresProject=false)`，参数 `-Dname`（必填）/ `-Dtarget`（默认 `.`）——**Facade 模式**，复用 `jcordis-cli` 的 `Scaffolder.create()`（模板单份维护，DRY） |
| 用法 | `mvn io.jcordis:jcordis-maven-plugin:0.1.0-SNAPSHOT:create -Dname=my-app [-Dtarget=dir]` |
| 生成物 | `pom.xml`（shade 打包 + 主类）/ `jcordis.yml` / `Index.java` / `SamplePlugin.java`（与 `jcordis create` CLI 完全一致） |
| 测试 | `CreateMojoTest`（反射注入参数模拟 Maven 容器注入，验证 4 个脚手架文件生成） |
| 端到端 | 实测：插件命令生成 demo-app → 生成的 demo-app 独立 `mvn package` 通过 ✓ |

### 验证结果

```
Tests run: 128, Failures: 0 -- 总计（27 个测试类，含 CreateMojoTest）
Reactor: jcordis 13/13 模块 SUCCESS, BUILD SUCCESS
```

---

## 插件热加载 HMR（ClassLoader 热替换，2026-08-20）

对照 cordis `packages/hmr` 的**模块级热替换**（partialReload）实现 Java 等价物：插件 jar 动态加载 + 热替换 + 完整卸载。设计文档：`docs/hmr-design.md`（含 ClassLoader 层级、卸载语义、版本管理约定——依赖模型为「传递依赖 + 业务 BOM」，无 jcordis-bom）。

### 步骤 1：运行时 jar 加载/卸载

| 项 | 内容 |
|---|---|
| `PluginClassLoader`（新建） | `URLClassLoader` 子类：parent-first 委托（框架类/三方库单实例，版本恒等于宿主）+ `close()` 释放 jar 句柄 |
| `Loader.loadJar(jar, name)` | 建独立 `PluginClassLoader` → `ServiceLoader` SPI 发现 `Plugin` 实现 → 注册 `modules`；无实现/异常时 close 释放；同名先 unload |
| `Loader.unload(name)` | 三步时序：dispose 所有使用该插件的 entry fiber → 摘注册 → close ClassLoader（类可回收） |
| 测试 | `PluginJarTest`（3）：SPI 发现生效 / unload 释放（jar 文件可删=句柄释放）/ 双插件独立注册 |

### 步骤 2：jar 级热替换（WatchService）

| 项 | 内容 |
|---|---|
| `Loader.replaceJar(jar, name)` | **原子替换**：新 `PluginClassLoader` 先 SPI 验证成功 → 才 swap 注册表 → dispose 相关 entry fiber → 重载（新插件）→ 旧 CL 延迟 close；验证失败旧插件完整保留（回滚） |
| `JarWatcher`（include 模块） | `WatchService` 监听插件目录 + **SHA-256 指纹**（重写检测精确）→ 新 jar 自动加载 / 变更自动热替换 → 成功后发 `hmr/reload`；读失败/替换失败后 200ms 延迟重查（覆盖写入竞态窗口） |
| 关键 Bug 修复 | fiber dispose 触发 `internal/plugin` → Loader 监听把 entry **误标 disabled** → 重载被拒。修复：只对本次 dispose 的 entry 清除误标再重载（`Set<Entry> reloaded` 精确追踪） |
| 测试 | `JarWatcherTest`（3）：初始加载 / v1→v2 热替换生效 / 损坏 jar 回滚（旧插件不变） |

### 步骤 3：严格类隔离/回收测试基建

| 项 | 内容 |
|---|---|
| fixture 独立编译 | jcordis-loader pom 加 compiler-plugin 额外 execution：`src/test/fixtures` → `target/test-fixtures-classes`——**类只存在于插件 jar，不在宿主 test classpath**（测试用字符串字面量引用，零静态依赖） |
| `IsolatedPlugin` fixture | apply 时把自身 `ClassLoader` 类名写入 System property（隔离探针） |
| 测试 | `PluginIsolationTest`（3）：**类由 PluginClassLoader 加载**（探针证明，非 parent-first 命中宿主）/ **unload 后类可回收**（WeakReference+GC，加载+卸载拆独立方法规避 JIT 栈槽强引用陷阱）/ 双插件 CL 独立 |

### 步骤 4：插件开发契约工程化

| 项 | 内容 |
|---|---|
| `Scaffolder.createPlugin` | 生成插件项目：pom（jcordis 依赖 `provided` + check goal 绑定 verify）+ `SamplePlugin` 模板 + SPI 清单 |
| `create-plugin` goal | `mvn ...:create-plugin -Dname=x` 一键生成（Facade 复用 Scaffolder） |
| `check` goal | `@Mojo(defaultPhase=VERIFY)`：扫描打包 jar，**禁止三方库/框架类**（包前缀黑名单 `com/fasterxml/`、`org/slf4j/`、`org/junit/`、`org/assertj/`、`org/apache/maven/`、`io/jcordis/`）→ 违反即构建失败 |
| 测试 | `ScaffolderTest` createPlugin 用例 + `CheckMojoTest`（4）+ `CreatePluginMojoTest`（1） |
| 端到端 | create-plugin 生成 demo-plugin → `mvn verify` → check 自动执行 "plugin jar is clean" → jar 内容仅 SamplePlugin.class + SPI 清单 ✓ |

### 依赖模型（最终定稿）

```
jcordis 模块 pom（自身依赖带版本）→ 传递依赖自动带入
宿主 classpath（jcordis + 框架三方库 + 业务三方库）← 业务系统依赖 jcordis 即获得框架面
   ↑ import
业务 BOM（业务三方库版本，业务自己定）← 版本权威；业务可显式覆盖 jcordis 传递版本（就近原则）
   ↑ import
插件项目 pom（jcordis + 三方库 = provided）→ 干净 jar（check goal 强制）
   ↓ parent-first
宿主 classpath（运行时提供一切）
```

### 验证结果

```
Tests run: 15, Failures: 0 -- 新增（PluginJarTest 3 + JarWatcherTest 3 + PluginIsolationTest 3 + CheckMojoTest 4 + CreatePluginMojoTest 1 + ScaffolderTest 1）
Tests run: 128, Failures: 0 -- 既有测试（无回归）
Tests run: 143, Failures: 0 -- 总计
Reactor: jcordis 13/13 模块 SUCCESS, BUILD SUCCESS
```

---

## 模块合并（2026-08-20）

小模块（1-4 个类）无独立模块价值，合并为两个大模块。**模块 14 → 9**，测试 146 全绿（无丢失）。

### 合并映射

| 原模块 | 合并至 | 新位置（包） |
|---|---|---|
| jcordis-utils（1 类） | jcordis-core | `io.jcordis.core.util.EffectList` |
| jcordis-plugin-timer（1 类） | jcordis-core | `io.jcordis.core.timer.TimerService` |
| jcordis-plugin-logger-console（1 类） | jcordis-core | `io.jcordis.core.logger.ConsoleExporter` |
| jcordis-plugin-include（4 类） | jcordis-loader | `io.jcordis.loader.include.{Include,ConfigParser,Hmr,JarWatcher}` |
| jcordis-plugin-group（1 类） | jcordis-loader | 删除——`GroupPlugin` 仅转发 `Loader.GROUP_PLUGIN`，直接使用后者 |

### 保留模块

`jcordis-core` / `jcordis-loader` / `jcordis-cli`（CLI 独立入口）/ `jcordis-maven-plugin`（packaging=maven-plugin 必须独立）/ `jcordis-all` / `examples/*`。

### 连锁更新

| 项 | 变更 |
|---|---|
| 根 pom | 模块列表 14 → 9 |
| jcordis-all | 依赖与 shade artifactSet 收敛为 core/loader/cli；`AggregateJarIT` 断言新类路径 |
| Scaffolder 模板 | 应用 pom 移除 timer/logger-console 模块依赖（并入 core）；`Index.java` 引用 `io.jcordis.core.timer.TimerService` / `io.jcordis.core.logger.ConsoleExporter` |
| examples | config-app 移除 include 模块依赖（→ loader），`Include` import 更新 |

### 验证结果

```
Tests run: 146, Failures: 0 -- 总计（合并不丢测试）
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
端到端回归：create 应用（新模板引用）构建 OK + demo-plugin 独立构建 OK
```

---

## 参考对齐修正（2026-08-27）

对照 `cordis` 4.0.0-rc.9 逐文件复核，修复构建断裂与若干行为缺陷，补齐参考语义缺口。

### 1. 构建修复：缺失 fixture 类

| 项 | 内容 |
|---|---|
| 问题 | commit f4c0b4a 删除 fixture 后未重建，`PluginJarTest`/`JarWatcherTest` 引用的 `io.jcordis.fixture.SamplePlugin/V1Plugin/V2Plugin` 不存在 → 6 个测试 Error |
| 修复 | 在 `jcordis-loader/src/test/fixtures/java/io/jcordis/fixture/` 重建三类（探针 `jcordis.probe.sample` / `jcordis.probe.version`），与 `IsolatedPlugin` 同约定（仅存在于 fixture jar，脱离宿主 classpath） |

### 2. 行为缺陷修复（A 组）

| 缺陷 | 修复 |
|---|---|
| **config 变更不重启插件**：`Entry.update` 在 `copyInto` 之后比较 `options.config` 与 `source.config`（同一引用，恒等）→ `loader.read()`/Hmr 路径改配置永不触发 `fiber.update` | 覆盖前捕获 `legacyConfig` 再比较；新增 `EntryConfigChangeTest`（2） |
| `Logger` `%C` 格式化吞值（返回空串） | formatter 改为接收 `(value, exporter, message)`，`%C` 用消息名色板给值上色；`LoggerConsoleTest` +2 |
| `ReflectService.set(String)` 无跨 fiber 校验 | 补 `impl.fiber() != source.fiber()` 抛错（对齐参考 "in multiple fibers"）；`ServiceRegistryTest` +1 |
| `FiberImpl.checkImpl` 不调用 `Impl.check()` 谓词 | 补 check 调用（false/异常 → 从 store 删除）；`ReflectService.provide` 增 4 参重载（带 check）；`ServiceRegistryTest` +1 |
| 异步插件体失败：卡 LOADING、无日志、无 FAILED 回退 | `reload()` 异步分支经 `handle` 捕获 → `handleBodyFailure`（记 error + 日志 + epoch=INACTIVE + 卸载部分效应 + FAILED）；`inertia` 落定置空 |
| FAILED 状态无 `_error` 恢复语义 | 新增 `error` 字段：`setEpoch` 在 error 存在时拒绝转换；`await()` 抛错；仅 `update()` 清除 error 后 `restart()` 恢复（对齐参考）；新增 `FiberFailureTest`（6） |
| **intercept/isolate 更新不传播到运行中 fiber**（`ContextImpl.child` 拷贝 map，fiber.ctx 与 entry.ctx 无活链接） | `Entry.rebuildCtx()` 从 parent 重建（消除链增长）+ `Fiber.rebindContext()` 重启前重绑定；`LoaderIsolateConfigTest` +1 |
| `TimerService.close()` 无人调用且 `close()` 会创建空线程池 | 构造时经 `ctx.effect` 注册关闭；`scheduler()` 支持关闭后重建；`close()` 幂等 |
| `EntryGroup.update` 用 `HashSet` 迭代 id（顺序不定，破坏依赖顺序） | 改 `LinkedHashMap` + `LinkedHashSet`（插入序，对齐参考对象键序） |
| **entry 级 `options.inject` 从未合并到 fiber**（参考经 internal/plugin 事件在依赖解析前合并） | `RegistryService.plugin` 增 extraInject 重载，Entry.init 合并后传入；这是 loader await 语义的前置 |

### 3. 参考语义补齐（B 组）

| 项 | 内容 |
|---|---|
| `Fiber.getEffects()` | `EffectMeta(label, children)` + 嵌套 effect 归属移交（外层收集即从 fiber 列表移除）；`FiberEffectTest` +1 |
| `Plugin.constructor(Class)` 缓存 | 按 Class 缓存构造插件实例 → 同 class 多条目共享 runtime（对齐参考按 callback 身份）；`PluginRegistryTest` +2 |
| loader `await` 配置 | `Loader` 提供带 check 谓词：`checkLoader()` 沿 intercept 链取 `loader.await`，有未落定任务时服务不可用；`EntryTree.getTasks()/await()` 实现；`Entry.init` 尾补 `notify(['loader'])`（任务落定后重查依赖方）；`LoaderBasicTest` +1 |
| `Include` 监听 `internal/update` path 变更 | 配置 path 匹配时重应用解析树（对齐参考） |
| `Service.resolveConfig` | 沿 parent 链合并 intercept 配置（base + 链 + head，后赢），对齐参考 API；`ServiceRegistryTest` +1 |

### 4. 卫生项

- `PluginJarTest` 宿主 SPI 模拟引用已删除的 `io.jcordis.loader.fixture.SamplePlugin` → 改为存在的 fixture 类名并澄清注释
- README 过时内容（14/14 模块、146 测试 → 9/9 模块、165 测试；模块合并描述）
- `spotless:apply` 全量格式化（原仓库 11 处格式债务一并修复）

### 验证结果

```
Tests run: 165, Failures: 0 -- 总计（新增 18 个测试）
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
mvn -Pformat spotless:check -- BUILD SUCCESS（全量合规）
```

---

## 参考对齐修正 · 续（2026-08-27）

继续补齐上一轮未动的 B 组语义缺口（内部事件可观测性 / 瀑布链 / group 身份 / Loader 方法）。

### 1. `internal/status` 事件发射（B2）

| 项 | 内容 |
|---|---|
| 改动 | `FiberImpl` 新增 `transitionState()` 辅助：所有生命周期状态转换（LOADING/ACTIVE/FAILED/PENDING/DISPOSED/UNLOADING）经它赋值并发射 `internal/status(fiber, oldState)`（对齐参考 `_updateState`）；构造期与 root 保持直接赋值（events 尚未就绪/无观察者） |
| 测试 | `FiberEffectTest.internalStatus_shouldReportStateTransitions`（PENDING→LOADING→ACTIVE→DISPOSED 序列） |

### 2. `internal/get` / `internal/set` 瀑布链（B2）

| 项 | 内容 |
|---|---|
| 改动 | `ContextImpl.get(String)` / `set(String, T)` 的服务访问路径经 `events().waterfall("internal/get|set", [ctx, name(, value)], inner)` 派发——监听器可拦截/替换/拒绝；默认 tail 行为与之前完全一致（props 短路、inactive 检查、reflect store 读写） |
| 测试 | `FiberEffectTest.internalGet_shouldAllowInterception` / `internalSet_shouldAllowRejection`（替换值 / 拒绝写） |

### 3. `loader/partial-dispose` 事件（B6）

| 项 | 内容 |
|---|---|
| 改动 | `Entry.update` 重启路径与 `EntryGroup.remove` 均发射 `loader/partial-dispose(entry, legacy, active)`（legacy 用 `EntryOptions.Snapshot`）；为后续 realm 回收等消费者预留 |
| 测试 | `LoaderBasicTest.partialDispose_shouldEmitWithLegacyOptions` |

### 4. Group 插件身份判定（替换名字匹配 hack）

| 项 | 内容 |
|---|---|
| 改动 | 删除 `isGroupPlugin(name)`（`name.contains("plugin-group")` 脆弱判定）；改为 `plugin == Loader.GROUP_PLUGIN` 身份检查（对齐参考 `plugin[EntryGroup.key]` 标记）；`Loader` 构造时把 `@cordisjs/plugin-group` 注册进 `builtins`（参考同名的导出） |
| 测试 | `LoaderBasicTest.groupPluginIdentity_shouldMarkEntryAsGroup`（无 `group: true` 也能作为容器） |

### 5. Loader 方法补齐

| 项 | 内容 |
|---|---|
| `Loader.locate(fiber)` | 沿 ctx parent 链找 fiber 所属 entry id（对齐参考 `Loader.locate`） |
| `Loader.showLog` | 改用已修复的 `%C` 格式化（`"%s plugin %C"`，对齐参考输出） |
| 测试 | `LoaderBasicTest.locate_shouldFindEntryIdOfFiber` |

### 验证结果

```
Tests run: 171, Failures: 0 -- 总计（新增 6 个测试）
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
mvn -Pformat spotless:check -- BUILD SUCCESS
```

---

## 推进：错误语义 / CI 门禁 / 对照表刷新（2026-08-27）

### 1. effect runner 抛错时嵌套效应立即处置（dispose.spec "yield with error"）

| 项 | 内容 |
|---|---|
| 改动 | `FiberImpl.effect()` 在 `runner.run` 抛错时，对 run 期间注册的嵌套效应（disposables/effectMetas 尾部）立即**逆序处置并移除**后重抛（对齐参考 `_execute` catch → `dispose()`）；新增 `disposeTail(from)` 辅助 |
| 测试 | `FiberEffectTest.effectRunnerError_shouldDisposeNestedEffectsImmediately`（嵌套效应即时处置 + 无遗留元数据 + 错误传播） |

### 2. CI 门禁：spotless 校验接入

| 项 | 内容 |
|---|---|
| 改动 | `.github/workflows/ci.yml` 在 `mvn clean verify` 后追加 `mvn -Pformat spotless:check`——防止格式债务回归（本轮已全量清理，此前仓库有 11 处格式问题） |

### 3. docs/compatibility.md 权威对照表刷新

| 项 | 变更 |
|---|---|
| 第一节 | 新增 4 行：内部事件链（internal/get+set 瀑布、internal/status）、失败恢复（FAILED→update 恢复）、效应可观测性（getEffects）、依赖响应补 `Impl.check` 谓词；Loader 行补 config 变更重启/intercept 传播/entry inject/await/locate/partial-dispose |
| 第二节 | 新增 3 行：`Service.resolveConfig`、entry inject 合并时机、`Loader[Service.check]` await 门控 |
| 第三节 | `internal/get`/`internal/set` 瀑布从"简化"改为 ✅ 已实现；`internal/status` 改为"已发射，消费方留待后续" |

### 验证结果

```
Tests run: 172, Failures: 0 -- 总计（新增 1 个测试）
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
mvn -Pformat spotless:check -- BUILD SUCCESS（CI 门禁同步）
```

---

## 推进：事件消费闭环 + 发布准备（2026-08-27）

### 1. `internal/status` 消费方：Loader 生命周期日志

| 项 | 内容 |
|---|---|
| 改动 | `Loader` 构造时监听 `internal/status`：entry fiber 进入 ACTIVE 记 `reload`、离开 ACTIVE 记 `unload`（`enableLogs` 门控，`"%s plugin %C"` 格式）——事件从"仅发射"变为"有消费者" |
| 测试 | `LoaderBasicTest.loaderLogs_shouldConsumeInternalStatus`（dispose 后断言 buffer 内 "unload" 消息；初始 ACTIVE 转换发生在 setEntry 之前，无法归因条目） |

### 2. `loader/partial-dispose` 消费方：全局 realm GC

| 项 | 内容 |
|---|---|
| 改动 | `Loader` 监听 `loader/partial-dispose`（上一轮已发射）：条目离开某个 `@label` 全局 realm 且无其他条目引用时，删除该 realm 的隔离键；realm 空则整体移除（对齐参考 isolate 插件的 partial-dispose 处理器）——isolate 7 步迁移中"realm 生命周期"缺口闭环 |
| 测试 | `LoaderBasicTest.globalRealm_shouldBeGarbageCollectedWhenUnreferenced` / `globalRealm_shouldKeepIsolatedNamesInUse`（引用保持 + 引用清除两条路径） |

### 3. Maven Central 发布准备

| 项 | 内容 |
|---|---|
| `distributionManagement` | S01 OSSRH：snapshot 仓 + staging 仓 |
| `maven-source-plugin` | 打包时附加 sources jar（pluginManagement + release profile） |
| `maven-javadoc-plugin` | release profile 附加 javadoc jar（已存在 failOnError=false 配置） |
| `maven-gpg-plugin` | `release` profile（`-Prelease`）签名全部构件 |
| 生效方式 | `mvn -Prelease clean deploy`；日常 `mvn clean verify` 完全不受影响（有效 POM 已验证） |

### 验证结果

```
Tests run: 175, Failures: 0 -- 总计（新增 3 个测试）
Reactor: jcordis 9/9 模块 SUCCESS, BUILD SUCCESS
mvn -Pformat spotless:check -- BUILD SUCCESS
mvn help:effective-pom -Prelease -- 发布配置（sources/javadoc/gpg/ossrh）就位
```

---

## 推进：HMR 端到端演示示例（2026-08-27）

### `examples/hmr-app`（新增模块，reactor 10/10）

| 项 | 内容 |
|---|---|
| `HmrApp` | 开发模式入口：Context + Loader + `Hmr`（轮询 jcordis.yml 配置热重载）+ `JarWatcher`（监视 plugins/ 目录 jar 热替换）；首跑自动生成初始配置 |
| `GreeterPlugin` | 配置驱动演示插件（`greeting` 字段变化即重启生效） |
| `HmrAppTest` | 端到端 2 例：① 配置变更 → 插件重载（bonjour）+ 条目移除 → dispose；② jar 替换（fixture V1→V2，探针 `jcordis.probe.version`）→ 原子热替换 |
| fixture | `src/test/fixtures` 独立编译到 `test-fixtures-classes`（脱离宿主 classpath），复用 loader 的 jar 隔离约定 |

### 顺带修复：Hmr mtime 毫秒粒度漏检

| 项 | 内容 |
|---|---|
| 问题 | Hmr 用 `getLastModifiedTime().toMillis()` 比较，同一毫秒内的连续写入被漏检（演示测试暴露：hello 写入后立即写 bonjour，两事件 mtime 相同） |
| 修复 | 改用 `FileTime` 精确比较（`equals`，NTFS 100ns 精度）——快速连续写入不再丢失；示例测试连续 3 次运行稳定 |

### 验证结果

```
Tests run: 177, Failures: 0 -- 总计（hmr-app +2）
Reactor: jcordis 10/10 模块 SUCCESS, BUILD SUCCESS
mvn -Pformat spotless:check -- BUILD SUCCESS
```

---

## 推进：isolate 变更路径修复（2026-08-27）

### 1. 缺口：`loader.read()`（配置文件/HMR 路径）isolate-only 变更被丢弃

| 项 | 内容 |
|---|---|
| 问题 | `Entry.update` 的重启分支条件是 `force \|\| config 变更`；`loader.read`（EntryGroup.update）走 `force=false`，isolate-only 变更（config 不变）被静默忽略——realm 移动不生效 |
| 修复 | 新增 `isolateChanged()` 判定并纳入重启分支；重启后对**变更的隔离名**显式 `reflect().notify(...)`（对齐参考 isolate 插件 patch-context 步骤 6），使 rebind 后的依赖方按新 realm 重新解析 |
| 测试 | `EntryIsolateChangeTest.isolateChangeViaRead_shouldMoveRealms`：a 移出 realm → b（声明 inject）卸载；b 跟随移入 → 重载并重新看到服务 |

### 2. 顺带修复：`unloadBody` 清空依赖缓存破坏 restart 重解析

| 项 | 内容 |
|---|---|
| 问题 | jcordis 把参考的 `_store`（依赖解析缓存，跨 unload 保留）与 `store`（body 快照）合并为一个 map，`unloadBody` 全清 → 带 inject 的插件 restart 后 `refresh` 无法重解析仍可用的依赖（参考 `_store` 不随 `_unload` 清空） |
| 修复 | `unloadBody` 不再清 `store`（依赖缓存保留；notify 路径经 `checkImpl` 增删）；`unload()`（完全销毁）仍清空 |

### 3. 文档

`compatibility.md` 裁剪表更新 isolate 行：realm GC 已实现；isolate 变更走插件重启（副作用重执行），服务 impl 原地迁移未实现（参考为 prototype 原地交换，jcordis 是不可变 ctx 拷贝模型）。

### 验证结果

```
Tests run: 178, Failures: 0 -- 总计（EntryIsolateChangeTest +1）
Reactor: jcordis 10/10 模块 SUCCESS, BUILD SUCCESS
```
