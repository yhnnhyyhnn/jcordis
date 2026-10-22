# jcordis 与 Cordis 行为差异对照表

> 本文档明确 jcordis 对 Cordis（TypeScript）移植过程中的**保留、修改、裁剪**，是行为对齐的权威参考。
>
> **对齐基线**：cordis `4.0.0-rc.10` + 2026-10 上游修复（`f8ea3cd..c261dd2` 与 `c261dd2..34c8ac5` 两批：fiber 重载版本号、每次重载解析配置、`internal/commit` 分离、`internal/update` 钩子随 fiber 处置、throttle dispose 防护、entry id 分隔符校验、Include ENOENT 判定、隔离边界/并行错误描述/热替换禁用守卫/匿名 id 防碰撞/`cordis:` 前缀移除）

## 一、完全保留（语义等价）

| 领域 | Cordis 概念 | jcordis 实现 | 测试 |
|---|---|---|---|
| 时空可组合性 | `ctx.effect()` 立即执行 + 逆序 dispose | `FiberImpl.effect` 相同 | FiberEffectTest |
| 事件总线 | 五种派发模式（emit/parallel/serial/bail/waterfall） | `EventBus` 相同（含 thisArg 过滤、AggregateError、isBailed） | EventBusTest |
| 内部事件 | internal/update（已加载 fiber 的瀑布链，`config, next` 签名）、internal/listener 拦截、fiber 本地 hooks（**随 fiber 处置，重载不累积**） | 相同 | FiberUpdateSemanticsTest、EventBusTest.update_shouldRunFiberLocalUpdateListeners |
| 重载版本号 | epoch = `revision:uid…`；`restart()` 递增 revision；被取代的异步 load 只清理，不置错/不激活新目标 | 相同（`FiberImpl.revision` + 完成回调 epoch 防护） | FiberUpdateSemanticsTest.supersededFailure/Disposable |
| 配置解析时机 | `internal/config` 瀑布在**每次重载与每次 update** 时解析（fiber 保存源 config）；持久化经 `internal/commit`（与重启解耦，未加载 fiber 也提交）；未加载 fiber 的 update 跳过钩子链直接重启 | 相同（jcordis 无 schema 校验，`internal/config` 默认透传，作为扩展点保留） | FiberUpdateSemanticsTest.configShouldBeResolved…/unloadedFiberUpdate… |
| 内部事件链 | `internal/get`/`internal/set` 瀑布（可拦截/替换/拒绝）、`internal/status` 状态转换通知 | 相同（get/set 经 `events().waterfall`；状态转换经 `transitionState` 发射） | FiberEffectTest.internalGet/Set/Status |
| 失败恢复 | FAILED 状态仅 `update()` 清除 `_error` 后恢复；`await()` 抛错 | 相同 | FiberFailureTest |
| 效应可观测性 | `fiber.getEffects()`（label/children 树） | `EffectMeta` + `Fiber.getEffects()` 相同 | FiberEffectTest.getEffects |
| 服务注册 | `provide` 重复抛错、isolate 隔离域、`getImpl(strict)` ACTIVE 过滤 | 相同 | ContextTest、ReflectTest、ServiceRegistryTest |
| 依赖响应 | `notify` → checkImpl + refresh → epoch 重载 | 相同（isolateKey 过滤 + `Impl.check` 谓词） | ServiceRegistryTest、IsolateTest |
| 插件生命周期 | 插件 fiber 注册为父 fiber effect、逆序级联卸载 | 相同 | PluginRegistryTest、E2eDemoTest |
| thisArg 过滤 | `Session[Context.filter]` 协议、Service 作为 EventFilter | `EventFilter` 接口 + `Service.test()` 相同 | IsolateTest.isolatedEvent |
| Logger | 有界 buffer、exporter 注册（effect 回滚）、name 派生链 | 相同 | LoggerTest |
| Loader | EntryTree/EntryGroup/Entry、disabled 祖先链、group 子树、config 变更重启（diff 检测）、intercept/isolate 更新传播（rebindContext）、entry inject 合并、await 就绪门控、`locate()`/`partial-dispose` 事件、**entry id 含树分隔符 `:` 抛错且不中断同批其余条目** | 相同（核心语义） | LoaderBasicTest、LoaderGroupTest、LoaderIsolateConfigTest、EntryConfigChangeTest、LoaderGuardsTest |
| Timer | timeout/interval/throttle/debounce + effect 自动清理；**dispose 后 wrapper 永不再执行（含窗口已过的立即分支）** | 相同 | TimerTest、TimerEdgeTest |

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
| 异步效应收集 | 完成时收集 disposable；fiber 已销毁则**立即处置**（不泄漏，`async return 2`）；销毁后失败忽略（保持 DISPOSED） | 同参考语义，经 per-fiber Monitor 锁实现 |
| `hmr.watch(path, callback)`（chokidar 递归监视 + async 回调） | `Hmr.watch(Path, Runnable)`（轮询；文件比 `FileTime`，**目录比整棵树的指纹**（相对名 + mtime + size），多回调/路径，`ctx.effect` 注销） | Java 无 chokidar；jar 目录另由 `JarWatcher` 的 WatchService 负责 |
| `Include` 通过 `ctx.hmr.watch(filename, refresh)` 自治重载 | 同（`Include.refresh()` + `Hmr.watch`；**每个 include 各自注册回调并随销毁注销**，refresh 带 disposed 守卫） | 参考 cacab04e；`Hmr` 注册为 `hmr` 服务供发现（`ctx.get("hmr")` 一次性查询，晚于 include 启动的 hmr 不可见——cordis 的 inject 是响应式，此项未对齐） |
| `EntryTree.commit(EntryChange)` 结构化变更 + `Include` journal 双向同步 | 同：`EntryChange`（id/group/from/options/legacy）+ root tree 监听器 + `Journal`（record/merge/diff/apply/reconcile，含 moved-both 冲突）；运行时变更（禁用/改配置）写回文件（**snapshot/swap**，并发记录不丢），文件编辑经 reconcile 后**文件优先**；`internal/update` 钩子 fiber-local | jcordis 的 `Include` 不是独立 `EntryTree` 子树，而是共享 loader 根树：re-apply 前把 root data 中**不属于文件的条目并入 diff**（等价子树 diff 语义，外部条目与 include 自身存活）；文件外的运行时新建条目不回写（保守差异） |
| `hmr` 三阶段部分重载（per-fiber drain、accepted/declined 依赖图分析） | `Loader.replaceJar` 三阶段：**验证**（全有或全无）→ **卸载**（本轮 entry 先全部 dispose + per-fiber drain）→ **重载**（逐 entry，祖先已在轮则跳过，失败隔离不回滚） | Java 无 ESM 模块图：**jar 即原子重载单元**，故不做 accepted/declined 依赖图分析（参考的“部分”以 vite 模块粒度）；其余三阶段语义已对齐 |
| `inspect(ctx)` → `Context <name>` | `ctx.toString()` | 无 util.inspect |
| `Service[resolveConfig]`（intercept 链合并 base/head） | `Service.resolveConfig(base, head)` 方法 | Java 方法调用 |
| `fiber.inject` 合并（entry 级 inject 经 `internal/plugin` 事件注入） | `RegistryService.plugin(ctx, plugin, config, extraInject)` 构造前合并 | Java 无事件钩子时机 |
| `Loader[Service.check]`（await 配置门控依赖就绪） | `checkLoader()` 谓词 + `getTasks()/await()` | Java 方法调用 |
| vi fake timers 测试 | 真实短延迟 + 轮询等待 | Java 无虚拟时钟 |

## 三、裁剪（未移植或降级）

| 功能 | Cordis 机制 | jcordis 状态 | 说明 |
|---|---|---|---|
| 动态模块加载 | Node ESM `import()` + ModuleLoader | SPI 注册表（builtins/modules） | `Loader.importPlugin(name)` 查表；动态编译列为增强项 |
| HMR 插件源码热重载 | V8 模块缓存清除 + re-import | 仅文件变更回调 → 树 diff 重载（`Hmr.watch()` + 便捷 config 重载） | Java 无法动态重编译类 |
| 配置表达式求值 | `with(ctx){eval()}` | **纯数据插值**（方案 A） | 安全决策：不执行任意表达式，预留 Evaluator 接口 |
| isolate Realm 完整语义 | LocalRealm/GlobalRealm 7 步切换 + 服务 impl 迁移 | LocalRealm/GlobalRealm + realm GC（partial-dispose 消费）+ **isolate 变更走插件重启**（副作用重执行，非 impl 原地迁移）；服务 impl 原地迁移未实现 | 参考用 prototype 原地交换，jcordis 为不可变 ctx 拷贝模型 |
| `internal/get`/`internal/set` 瀑布 | 完整 waterfall 链 | ✅ 已实现（见第一节；尾端语义与参考一致） | 2026-08 对齐修正 |
| 调用者追踪（traceable） | JS Proxy + `symbols.tracker` | 未移植 | 影响：outer-caller intercept 覆盖、logger 调用栈 name 恢复不可用 |
| `internal/status` 事件消费 | fiber 状态变化通知 | 事件已发射（`transitionState`）；**消费方**（状态跟踪服务）留待后续 | |
| ANSI 颜色完整格式化 | `%o` inspect、C256 色板、showDiff | ConsoleExporter 基础 ANSI + %s/%d；深度 inspect 未移植 | |
| `interval(delay)` async iterator | JS AsyncIterable | 未移植（仅回调形态） | |
| include js-expr YAML 标签 | `!js` 标签 → eval | 未移植（安全决策） | |
| include 文件热重载 | include.refresh() + watcher + 双向 journal 写回 | 同（`Hmr` 轮询 + `Include` journal 写回）；patch 变更不回写 patch（patch 拥有项的变化不落盘） | 写回前只把 **ENOENT** 当"文件缺失等待"，其他读失败上抛（对齐 #171） |
| Maven Archetype | | 未做（选方案 A：内嵌模板 CLI） | |

### 2026-10 上游不适用项（`f8ea3cd..c261dd2` 中 6 项）

| cordis 提交 | 内容 | jcordis 判定 |
|---|---|---|
| `00432ef` | Entry `_initTask` 保持到 init 落定（ESM 动态 import 任务） | 不适用：jcordis 条目同步加载，`_initTask` 从不赋值 |
| `9f5e690` | `parallel()` 向 internal/dispatch 报告 `parallel` 模式 | 已是修复后形态（`resolve("parallel", …)`） |
| `91708e7` | hmr.watch 路径与 baseDir 大小写不一致（Windows） | 不适用：Java `Path.equals/hashCode` 在 Windows 大小写不敏感，watch key 天然归一 |
| `ade19d0` | `process.argv[1]` 缺失时收集 externals 防护 | 不适用：jcordis 无 ESM loadCache/externals 概念 |
| `0221244` / `c3cc637` | create 脚手架的 `.yarnrc.yml` / yarn 临时二进制 | 不适用：jcordis-cli 是 Maven 脚手架，无 yarn |

## 四、已知行为差异

| 场景 | Cordis | jcordis | 影响 |
|---|---|---|---|
| `ctx.on` 在 disposed fiber | 抛 `INACTIVE_EFFECT` | 抛 `CordisError(INACTIVE_EFFECT)`，消息为枚举名 | 断言需按类型而非消息文本 |
| `ctx.get(未注册)` | 返回 `undefined` | 返回 `null` | Java 惯例 |
| `Map.of("x", null)` | JS 允许 null 值 | Java 禁止 → 测试用 `inject(...)` 辅助 | 仅测试代码 |
| fiber dispose 后 uid | `null` | `-1` | 类型差异 |
| 时间戳 | `Date.now()` | `System.currentTimeMillis()` | 等价 |

## 五、2026-10-13 审查遗留项处置（原 8 项，已全部处置）

首轮全库审查后暂缓的 8 项，已于同日第二批全部修复或给出 Java 语义的显式对齐：

| 项 | cordis 行为 | 处置 |
|---|---|---|
| `registry.delete` 阻塞 | 不阻塞 | 已修：dispose 已同步化后 `join()` 是冗余等待，去除（对齐 cordis 的不等待） |
| `Service.resolveConfig` 多配置合并 | `Object.assign` 任意形状 | 已修：按层收集（`hasIntercept` 计入显式 null 层，null 层对合并无贡献）；多层 Map 逐键合并后者胜，非 Map 层整体替换（Java 无法键合并任意值，记为文档化偏差） |
| PENDING fiber 取 injected 服务 | 抛 `cannot get required service` | 已修：get 尾端按 cordis 走定义链——优先 fiber 本地解析快照（unload 即清，杜绝 dispose 后读到残留 impl），声明未解析则抛错；根 fiber 与未 inject 的名字保持 lenient |
| `EntryOptions.merge` 的 null 清除 | null 即删除 | 已修（显式化）：新增 `clear(key)` 标记删除，`merge` 执行清除——Java 字段无法表达"显式缺席"，普通 null 保持"不动"的历史语义 |
| 文件驱动 update 的 force | force 仅绕过 no-diff 短路，重启仅由 config diff 驱动 | 已修：`Entry.update` 重构为逐键 diff——intercept/inject 文件修改现在生效（重建上下文 + rebind + notify，**不重启** body，对齐 patch-context 语义）；config 变化才重启；group 条目总是重启（级联子条目）；isolate 变化保持重启（jcordis 无 impl 原地迁移，见第三节）；transfer 不再重启 body（cordis 无 transfer，即 update+parent） |
| `Entry.id()` 前缀守卫 | 无条件父前缀 | 已修：去除同 id 守卫，子条目与组同 id 时按 `g:g` 寻址 |
| `ctx.mixin` | accessor 声明 + props 守卫 | 已修：`provide`/`mixin` 互斥守卫（"already declared as accessor/service"）；accessor 的动态属性读取仍不适用（Java 无 Proxy，见第二节） |
| `ServiceKey.of` CANONICAL 表 | key 属于每棵树 | 已修：canonical key 本就按值等价（uid=0 + name），去掉全局缓存表，消除全 JVM 累积 |

### 2026-10 第二批上游（`c261dd2..34c8ac5`，30 提交）处置

**已移植（5 项）**：

| cordis 提交 | 内容 | jcordis 实现 |
|---|---|---|
| `1a87f46` | parallel 失败的 AggregateError 描述失败事件 | `AggregateError(errors, message)` 双参构造；`EventBus.parallel` 抛 `one or more listeners of event "X" failed` |
| `db20555` | Windows rename 被文件锁阻塞：相同内容跳过 + 瞬态错误退避重试 | `Include.writeText`：内容一致短路 + `AccessDeniedException`/`AtomicMoveNotSupportedException` 10 次 20ms 递增退避 |
| `fabd555` | 卸载等待期间被 disable/remove 的条目不被重建 | `Loader.replaceJar` stage 3 决策：disabled 取 **dispose 前快照**（dispose 自身会写自禁用侧标记）、removed 查 store、re-init 查 fiber |
| `0e0cc2c` | 匿名 id 不复用已被显式占用的值；patch insert 的显式 id 参与防碰撞 | `Include.assignIds`：used 集合纳入 patches insert 的 id；复用前检查 used |
| `34c8ac5` | 移除 `cordis:` builtin 前缀 | `Loader.importPlugin` 删前缀分支，builtin 按裸名解析 |

**已等价/模型规避（12 项）**：`bc9a344`（隔离边界——jcordis 的 `getImpl` 兜底严格按当前 ctx 的 isolateKey 解析，域外值天然不可见，隔离域内 get 为 lenient null 而非抛错，契约测试 `IsolateTest.serviceLookupShouldNotCrossIsolationBoundaries`）；`5dac695`（unload 尾部显式 `transitionState(DISPOSED)`）；`1ce0e49`（renderThrowable 空 errors 列表天然不展开）；`7e9eb09`（inject 配置在 Fiber 构造时固化进 ctx intercept 层，无 def-site 运行时查询需求）；`9cad5e8`（jcordis 级联销毁同步：notify→refresh→unload 同一调用栈，依赖者先于 impl 移除完成卸载）；`147e4f2`（isolate 变更走重启，provide disposer 的 key 对称）；`1a6df96`/`67538f6`（schema 校验定位，jcordis 无 schema）；`6434188`（patch 归属按实际应用推导——jcordis 保持 id 级保守归属，键级归属列为增强项）；`e76a291`/`df4431e`/`a71c658`（Windows 盘符/分隔符——Java `Path` 天然正确）。

**不适用（其余）**：`1d9ad95`（`!!js` 表达式，js-expr 裁剪）、`8273ac3`/`10ef5d8`（npm registry/脚手架失败报告，jcordis-cli 为 Maven 模板）、`3cef027`（TS 类型）、`9aa1415`（TS symbol 属性）、CI/chore/版本类。
