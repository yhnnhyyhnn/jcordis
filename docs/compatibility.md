# jcordis 与 Cordis 行为差异对照表

> 本文档明确 jcordis 对 Cordis（TypeScript）移植过程中的**保留、修改、裁剪**，是行为对齐的权威参考。

## 一、完全保留（语义等价）

| 领域 | Cordis 概念 | jcordis 实现 | 测试 |
|---|---|---|---|
| 时空可组合性 | `ctx.effect()` 立即执行 + 逆序 dispose | `FiberImpl.effect` 相同 | FiberEffectTest |
| 事件总线 | 五种派发模式（emit/parallel/serial/bail/waterfall） | `EventBus` 相同（含 thisArg 过滤、AggregateError、isBailed） | EventBusTest |
| 内部事件 | internal/update、internal/listener 拦截、fiber 本地 hooks | 相同 | EventBusTest.update_shouldRunFiberLocalUpdateListeners |
| 内部事件链 | `internal/get`/`internal/set` 瀑布（可拦截/替换/拒绝）、`internal/status` 状态转换通知 | 相同（get/set 经 `events().waterfall`；状态转换经 `transitionState` 发射） | FiberEffectTest.internalGet/Set/Status |
| 失败恢复 | FAILED 状态仅 `update()` 清除 `_error` 后恢复；`await()` 抛错 | 相同 | FiberFailureTest |
| 效应可观测性 | `fiber.getEffects()`（label/children 树） | `EffectMeta` + `Fiber.getEffects()` 相同 | FiberEffectTest.getEffects |
| 服务注册 | `provide` 重复抛错、isolate 隔离域、`getImpl(strict)` ACTIVE 过滤 | 相同 | ContextTest、ReflectTest、ServiceRegistryTest |
| 依赖响应 | `notify` → checkImpl + refresh → epoch 重载 | 相同（isolateKey 过滤 + `Impl.check` 谓词） | ServiceRegistryTest、IsolateTest |
| 插件生命周期 | 插件 fiber 注册为父 fiber effect、逆序级联卸载 | 相同 | PluginRegistryTest、E2eDemoTest |
| thisArg 过滤 | `Session[Context.filter]` 协议、Service 作为 EventFilter | `EventFilter` 接口 + `Service.test()` 相同 | IsolateTest.isolatedEvent |
| Logger | 有界 buffer、exporter 注册（effect 回滚）、name 派生链 | 相同 | LoggerTest |
| Loader | EntryTree/EntryGroup/Entry、disabled 祖先链、group 子树、config 变更重启（diff 检测）、intercept/isolate 更新传播（rebindContext）、entry inject 合并、await 就绪门控、`locate()`/`partial-dispose` 事件 | 相同（核心语义） | LoaderBasicTest、LoaderGroupTest、LoaderIsolateConfigTest、EntryConfigChangeTest |
| Timer | timeout/interval/throttle/debounce + effect 自动清理 | 相同 | TimerTest |

## 二、有意的实现差异（语义等价，形态不同）

| Cordis（JS） | jcordis（Java） | 原因 |
|---|---|---|
| `Plugin` 联合类型（函数/类/对象） | `Plugin` 接口 + `Plugin.constructor(Class)` 反射工厂 | Java 无一等函数/类字面量 |
| `ctx.foo` 属性访问（Proxy 拦截） | `ctx.get("foo")` 方法调用；严格访问用 `getRequired` | Java 无动态属性 |
| `ctx.logger('x')` 可调用对象 | `ctx.logger("x")` 方法（Logger 对象） | Java 无 callable |
| `ctx.mixin('source', ['k'])` 原型链 accessor | `mixin` 记录声明（无动态属性解析） | 同上 |
| `[Service.init]` symbol 方法 | `Initializable.init()` 接口方法 | Java 无 symbol |
| JS 原型链上下文继承 | 显式 parent 链接 + extend() 复制 map | 规避原型链 |
| `Promise`/`async` 插件体 | `CompletableFuture`（同步插件体 + 可选异步 init） | Java 并发模型 |
| `inspect(ctx)` → `Context <name>` | `ctx.toString()` | 无 util.inspect |
| `Service[resolveConfig]`（intercept 链合并 base/head） | `Service.resolveConfig(base, head)` 方法 | Java 方法调用 |
| `fiber.inject` 合并（entry 级 inject 经 `internal/plugin` 事件注入） | `RegistryService.plugin(ctx, plugin, config, extraInject)` 构造前合并 | Java 无事件钩子时机 |
| `Loader[Service.check]`（await 配置门控依赖就绪） | `checkLoader()` 谓词 + `getTasks()/await()` | Java 方法调用 |
| vi fake timers 测试 | 真实短延迟 + 轮询等待 | Java 无虚拟时钟 |

## 三、裁剪（未移植或降级）

| 功能 | Cordis 机制 | jcordis 状态 | 说明 |
|---|---|---|---|
| 动态模块加载 | Node ESM `import()` + ModuleLoader | SPI 注册表（builtins/modules） | `Loader.importPlugin(name)` 查表；动态编译列为增强项 |
| HMR 插件源码热重载 | V8 模块缓存清除 + re-import | 仅配置文件变更 → 树 diff 重载（`Hmr`） | Java 无法动态重编译类 |
| 配置表达式求值 | `with(ctx){eval()}` | **纯数据插值**（方案 A） | 安全决策：不执行任意表达式，预留 Evaluator 接口 |
| isolate Realm 完整语义 | LocalRealm/GlobalRealm 7 步切换 + 服务 impl 迁移 | Realm 基类已建，核心隔离（`isolate(name, key)`）可用；**realm 引用传递/impl 迁移未实现** | 复杂度高 |
| `internal/get`/`internal/set` 瀑布 | 完整 waterfall 链 | ✅ 已实现（见第一节；尾端语义与参考一致） | 2026-08 对齐修正 |
| 调用者追踪（traceable） | JS Proxy + `symbols.tracker` | 未移植 | 影响：outer-caller intercept 覆盖、logger 调用栈 name 恢复不可用 |
| `internal/status` 事件消费 | fiber 状态变化通知 | 事件已发射（`transitionState`）；**消费方**（状态跟踪服务）留待后续 | |
| ANSI 颜色完整格式化 | `%o` inspect、C256 色板、showDiff | ConsoleExporter 基础 ANSI + %s/%d；深度 inspect 未移植 | |
| `interval(delay)` async iterator | JS AsyncIterable | 未移植（仅回调形态） | |
| include js-expr YAML 标签 | `!js` 标签 → eval | 未移植（安全决策） | |
| include 文件热重载 | include.refresh() + watcher | `Hmr` 轮询监听配置变更 | |
| Maven Archetype | | 未做（选方案 A：内嵌模板 CLI） | |

## 四、已知行为差异

| 场景 | Cordis | jcordis | 影响 |
|---|---|---|---|
| `ctx.on` 在 disposed fiber | 抛 `INACTIVE_EFFECT` | 抛 `CordisError(INACTIVE_EFFECT)`，消息为枚举名 | 断言需按类型而非消息文本 |
| `ctx.get(未注册)` | 返回 `undefined` | 返回 `null` | Java 惯例 |
| `Map.of("x", null)` | JS 允许 null 值 | Java 禁止 → 测试用 `inject(...)` 辅助 | 仅测试代码 |
| fiber dispose 后 uid | `null` | `-1` | 类型差异 |
| 时间戳 | `Date.now()` | `System.currentTimeMillis()` | 等价 |
