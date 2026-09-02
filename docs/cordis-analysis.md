# Cordis 源码分析报告

> 分析对象：`[`cordiverse/cordis`](https://github.com/cordiverse/cordis)`（TypeScript monorepo，v4.0.0-rc.8）
> 分析目的：为 jcordis（Java 21 移植项目）提供架构参考
> 分析日期：2026-08-19

---

## 1. 项目概述

**Cordis** 是一个"时空可组合性元框架"（Meta-Framework of Spatiotemporal Composability），由 Shigma（Koishi 生态作者）开发。官方描述为 *A Meta-Framework for Modern Applications*。

### 1.1 理论基础

项目配有一篇论文：*[A Programming Paradigm for Spatiotemporal Composability](https://github.com/cordiverse/paper)*，核心思想：

- **时间可组合性（Temporal Composability）**：组件被移除时能够完全回滚其副作用。框架形式化了 **revertible effects（可逆效应）** —— 每一次上下文变换都携带一个由运行时跟踪的逆操作。
- **空间可组合性（Spatial Composability）**：声明并响应式管理组件间的依赖。框架形式化了 **reactive coeffects（反应式余效应）** —— 上下文变化时，运行时根据组件的余效应规格（coeffect specification）通知组件。
- **统一上下文类型（Context Type）**：将效应上下文与余效应上下文统一为单一类型，构成一种编程范式。
- **组件演算（Component Calculus）**：将上述机制组合为"组件"概念，给出动态组合的演算，其元理论把时空可组合性从单一组件推广到整个交错组件系统。

**Cordis 是这套理论的落地实现**：核心库提供效应跟踪与余效应解析，外加一个声明式组件加载器（配置调和 + 热模块替换）。

### 1.2 项目规模

| 维度 | 数值 |
|---|---|
| packages | 9 个（core, loader, timer, logger-console, include, group, hmr, utils, create） |
| 源码文件 | 27 个 `.ts` 文件 |
| 测试文件 | 30 个（core 12 个、loader 4 个、其余若干） |
| 构建工具 | yarn 4 + yakumo（自定义 monorepo 构建器）+ esbuild + vitest |
| 核心包版本 | cordis@4.0.0-rc.8（尚未正式发布，API 不稳定） |
| 依赖 | cosmokit（工具库）、@standard-schema/spec（配置校验标准） |

### 1.3 Monorepo 结构

```
cordis/
├── packages/
│   ├── core/              # cordis —— 核心框架（上下文/事件/效应/服务）
│   ├── loader/            # @cordisjs/plugin-loader —— 声明式插件加载器
│   ├── timer/             # @cordisjs/plugin-timer —— 定时器服务
│   ├── logger-console/    # @cordisjs/plugin-logger-console —— 控制台日志导出
│   ├── include/           # @cordisjs/plugin-include —— YAML/JSON 配置文件
│   ├── group/             # @cordisjs/plugin-group —— 配置分组（重导出）
│   ├── hmr/               # @cordisjs/plugin-hmr —— 热模块替换（Node 专用）
│   ├── utils/             # @cordisjs/utils —— 工具（List 等）
│   └── create/            # create-cordis —— 脚手架 CLI
└── (外部: external/* 为空)
```

**依赖方向**：`core ← loader ← {include, group, hmr}`，`core ← {timer, logger-console, utils, create}`。

---

## 2. 核心框架（packages/core）

这是整个框架的心脏，共 9 个源文件。核心思想：**一切皆可组合、一切副作用皆可回滚、一切依赖皆响应式**。

### 2.1 架构总览

```
                        ┌─────────────────────────────┐
                        │          Context            │
                        │  (JS Proxy + 原型链继承)      │
                        └─────────────────────────────┘
            ┌───────────────┬───────────────┬──────────────┐
            ▼               ▼               ▼              ▼
     ┌───────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐
     │ Fiber     │   │ Reflect   │   │ Registry  │   │ Events    │
     │ (生命周期) │   │ (服务存储) │   │ (插件注册) │   │ (事件总线) │
     └───────────┘   └───────────┘   └───────────┘   └───────────┘
            │               │
            ▼               ▼
     ┌───────────┐   ┌───────────┐
     │ Logger    │   │ Service   │
     │ (日志)    │   │ (服务基类) │
     └───────────┘   └───────────┘
```

**根 Context 构造顺序**（`context.ts`）：
1. 创建 isolate/intercept 空表
2. 用 `Proxy` 包装自身（属性访问全部走 `ReflectService.handler`）
3. 创建 `Fiber`（根 fiber，uid=0，立即 ACTIVE）
4. 依次创建 `ReflectService` → `RegistryService` → `EventsService` → `LoggerService`

### 2.2 Context —— 统一的上下文类型

**文件**：`context.ts`（78 行）

Context 是论文中"统一上下文类型"的实现，同时承载：

| 载体 | 含义 |
|---|---|
| `[isolate]` | 服务隔离表：`name → symbol`，决定服务在哪个"域"内可见 |
| `[intercept]` | 配置拦截表：`name → config`，允许覆盖服务的配置 |
| `root` | 指向根 Context |
| `fiber` | 当前所在 Fiber（生命周期载体） |
| `events / logger / reflect / registry` | 四个核心服务 |

**关键机制**：

- **`extend(meta)`**：通过 `Object.create` + 原型链继承创建子上下文（JS 独有的原型链复用方式，Java 中需用组合/委托替代）。
- **`isolate(name, label)`**：创建一个新的隔离域。`name → Symbol(name)`，之后同名服务只在持有相同 symbol 的上下文中共享。**这是空间可组合性的核心原语**。
- **`intercept(name, config)`**：为某服务注入配置覆盖，沿原型链逐层合并（`Service.resolveConfig`）。

### 2.3 Fiber —— 生命周期与时间可组合性

**文件**：`fiber.ts`（486 行，核心包最大的文件）

**Fiber = 一个插件实例的生命周期载体**。它负责：

1. **效应跟踪（Effect Tracking）**：`ctx.effect(fn)` 注册一个效应，fn 的返回值（dispose 函数）被收集；当 Fiber 销毁时**逆序**执行所有 dispose —— 这就是"可逆效应"的运行时实现（时间可组合性）。
2. **依赖注入与重载（Reactive Coeffects）**：Fiber 声明 `inject`（所需服务列表），当依赖服务变化时自动卸载重载。
3. **状态机**：

```
PENDING → LOADING → ACTIVE
              ↘ FAILED → (重试/销毁)
ACTIVE → UNLOADING → DISPOSED
ACTIVE ⇄ LOADING/UNLOADING（依赖变化时循环重载）
```

**效应类型**（`effect()` 支持）：

| 返回类型 | 含义 |
|---|---|
| `() => T` | 返回 dispose 函数 |
| `Promise<() => T>` | 异步效应 |
| `Iterable<() => T>` | 生成器效应（可 yield 多个 dispose） |
| `AsyncIterable<() => T>` | 异步生成器效应 |

**依赖响应机制**（`_refresh` / `_setEpoch` / `_reload` / `_unload`）：

- 每个 Fiber 计算一个 **epoch**：`':' + impl.fiber.uid` 拼接其所有注入服务的实现 Fiber uid。
- 当任何注入服务的实现变化（提供/卸载）时，epoch 变化，触发 `_unload()`（逆序 dispose 所有效应）→ `_reload()`（重新执行效应）。
- `inertia` 字段跟踪当前的异步卸载/重载任务，保证串行。

**其他要点**：
- `update(config)`：校验配置（Standard Schema）→ 触发 `internal/update` waterfall → `restart()`。
- `await()`：等待所有 inertia 完成，若失败则抛出错误。Fiber 被包装为 `PromiseLike`（有 `then` 方法），因此 `await ctx.plugin(...)` 可直接等待加载完成。
- `ValidationError`：配置校验失败时抛出，格式化为逐条 issue 列表。

### 2.4 Events —— 五种派发模式的事件总线

**文件**：`events.ts`（178 行）

```typescript
type DispatchMode = 'emit' | 'parallel' | 'serial' | 'bail' | 'waterfall'
```

| 模式 | 语义 | 对应 JS | Java 类比 |
|---|---|---|---|
| `emit` | 同步广播，不等待 | `for` 循环同步调用 | 同步 for 循环 |
| `parallel` | 全部并行执行，聚合错误 | `Promise.allSettled` + AggregateError | `CompletableFuture.allOf` |
| `serial` | 串行执行，直到某回调返回"非空值" | `for...await` | 同步/异步循环 |
| `bail` | 同步串行，第一个非空返回值短路 | 同步循环 | 同步循环 |
| `waterfall` | 链式传递 `next()`，可中间插入 | 回调链 | 函数式 `next` 链 |

**内部事件**（`internal/*`，框架自用）：

```
internal/plugin     —— 插件 Fiber 创建/销毁
internal/status     —— Fiber 状态变化
internal/service    —— 服务可用性变化
internal/update     —— 配置更新（waterfall，可被拦截）
internal/get / set  —— 服务属性读写拦截
internal/listener   —— 监听器注册
internal/dispatch   —— 事件派发前钩子
```

**关键实现**：
- 监听器注册走 `fiber.effect()`，因此**事件监听器随 Fiber 自动注销**（`ctx.on()` 无需手动 dispose，context 销毁时自动清理）——这是时间可组合性在事件系统的体现。
- `_resolve()` 通过 `hook.global || filter(hook.ctx)` 过滤：默认只调用与当前上下文处于**同一隔离域**的监听器。
- `internal/update` 自身就是一个 waterfall 示例：监听器可拦截配置更新流程。

### 2.5 Service —— 服务基类

**文件**：`service.ts`（80 行）

```typescript
export abstract class Service<out T = never> {
  // 静态符号：init / check / config / invoke / extend / tracker / resolveConfig
  constructor(protected ctx: Context, name: string) { ... }
}
```

要点：
- 构造时调用 `ctx.reflect.provide(name, self, check)` 注册到服务存储。
- **`config` 符号**：声明服务的配置类型（`declare [symbols.config]: T`），用于 `@Inject` 类型推导。
- **`resolveConfig(base, head)`**：沿 `intercept` 原型链收集所有配置，用 `Config.merge` 或 `Object.assign` 合并 —— **配置拦截机制**。
- 支持 **callable service**（可调用服务）：若类定义了 `[invoke]`，则实例会被包装成"可调用的函数对象"（如 `ctx.logger('name')` 调用形式）。

### 2.6 Registry —— 插件注册表

**文件**：`registry.ts`（214 行）

- 插件三种形态：`Function | Constructor | Object{apply}`，统一 `resolve()` 为回调函数。
- `Plugin.Runtime`：`{ name, fibers, callback, Config }` —— 同一插件函数的多次注册共享一个 Runtime，各实例为不同 Fiber。
- `plugin()`：解析回调 → 创建 Runtime → `new Fiber(...)` → 包装为 PromiseLike 返回。
- `delete()`：删除 Runtime 并 dispose 其所有 Fiber。
- **`@Inject` 装饰器**：类或方法上声明依赖服务（`inject` 元数据），方法注入通过 `initHooks` 在构造后执行。

### 2.7 Reflect —— 代理处理器与服务存储（空间可组合性引擎）

**文件**：`reflect.ts`（281 行）

这是整个框架最精妙的部分。Context 的每次属性访问都经过 `ReflectService.handler`：

```
ctx.someService 访问解析顺序：
1. 特殊属性（symbol / prototype / then / 数字 / _开头）→ 直接反射
2. 自身属性 → 直接返回（并做 traceable 包装）
3. accessor 定义 → 调用自定义 getter
4. internal/get waterfall（可被插件拦截）
5. 沿 fiber 链向上查找服务存储（store）
```

**服务存储**：
```typescript
store: Dict<symbol, Impl>   // key 是隔离 symbol
Impl = { name, fiber, value, check }
```

**provide(name, value, check)**：注册服务实现，注册后调用 `notify([name])`。

**notify(names)** —— 空间可组合性的核心算法：
1. 遍历所有 Runtime 的所有 Fiber
2. 对每个 Fiber 检查其 `inject` 中是否包含变化的服务名，且处于同一隔离域
3. `_checkImpl(name)` 重新解析实现 → `_refresh()` 重算 epoch → 触发卸载/重载
4. 发出 `internal/service` 事件

**accessor / mixin**：声明式属性与"混入"（如 `ctx.on`、`ctx.get` 就是通过 mixin 从 EventsService/ReflectService 混入到 Context 的）。

**traceable / bind**：把服务值包装为 Proxy，使其方法调用时能跟踪"调用者上下文"（`symbols.caller`），实现**隐式上下文传播**。

### 2.8 Logger —— 可调用日志服务

**文件**：`logger.ts`（246 行）

- **服务形态**：callable —— `ctx.logger('模块名')` 返回一个 `Logger`，有 `error/warn/info/debug` 四方法。
- **导出器（Exporter）模式**：`ctx.logger.exporter({...})` 注册导出器，默认内置一个环形缓冲（1000 条）。
- **printf 风格格式化**：`%s %d %f %o %O %c %C`，支持自定义 formatter；`%C` 为模块名着色（名称哈希 → 256 色板）。
- **Message 结构**：`{ sn, ts, name, type, level, args, fiber: WeakRef }` —— 用 `WeakRef` 弱引用 Fiber，避免日志系统阻碍垃圾回收。
- 级别常量：ERROR=0, WARN=1, INFO=2, DEBUG=3，可在 exporter 或 intercept 配置中覆盖。

### 2.9 Utils —— 基础设施

**文件**：`utils.ts`（278 行）

- `DisposableList`：带序号与弱引用的可逆序清理列表。
- `symbols`：全部内部 Symbol 的定义（跨包共享的约定键）。
- `composeError / buildOuterStack`：**异步错误栈组合** —— 把异步操作的真实调用栈拼接到错误上（JS 异步栈丢失问题的解决方案）。
- `getTraceable / createTraceable / createShadow`：traceable 包装（见 2.7）。
- `isConstructor`：判断函数是否为构造函数（用于区分函数插件与类插件）。

---

## 3. 加载器（packages/loader）

**@cordisjs/plugin-loader@1.0.1-SNAPSHOT-rc.5** —— 声明式组件加载器，把配置文件变成活的插件树。

### 3.1 模型

```
EntryTree（树）
 ├── EntryGroup（组，对应配置中的 group: true 条目）
 │    └── Entry（条目 = 一个插件实例）
 │         └── 插件回调被实例化为 Fiber
 └── store: id → Entry（id 用 ':' 分层，如 "web:server"）
```

- **Entry**：`{ id, name, config, group, disabled, inject }`。`name` 是要动态 import 的模块名；`config` 是插件配置。
- **EntryTree**：抽象类，`write()` 由子类实现（内存树 write 为空操作；Include 树 write 到文件）。
- **EntryGroup**：条目数组；`Group` 类把一组条目自身也作为一个插件（`static [EntryGroup.key] = true`）。

### 3.2 Entry 生命周期

```
Entry.update(options) ──► 更新 options
    ├─ disabled? → fiber.dispose()
    ├─ 有 diff? → emit('loader/partial-dispose') → _patchContext() → fiber.update()
    └─ 无 fiber? → init()
```

`_patchContext`：把 Entry 的 context 原型链指向父组 context，配置变化时 `fiber.update(config, noSave=true)`。

`init()`：`tree.import(name)` 动态导入模块 → `unwrapExports`（处理 default 导出）→ `registry.plugin(plugin, config)` 创建 Fiber。

### 3.3 配置求值与插值（config/utils.ts）

```typescript
// 使用 JS 的 with + eval 在上下文作用域内求值表达式
export const evaluate = new Function('ctx', 'expr', `with (ctx) { return eval(expr) }`)
```

- 配置中的 `{ __jsExpr: "..." }` 对象会被求值为 JS 表达式（可访问 ctx 内任意属性）。
- `interpolate()` 递归替换整个配置树。
- **⚠️ 安全问题**：`eval` + `with` 是全功能代码执行，仅适用于完全信任的本地配置。Java 移植必须用受限表达式引擎（SpEL）或干脆移除。

### 3.4 隔离域系统（config/isolate.ts）

- **LocalRealm**（`#id` 后缀）：每个 Entry 私有域。
- **GlobalRealm**（`@label` 后缀）：多个 Entry 共享的域。
- `entry.options.isolate = { name: true | 'label' }` 声明某服务进哪个域。
- `loader/patch-context` 事件执行 7 步切换：生成新 isolate 图 → 计算服务 diff → 换原型 → 重载 fiber → 替换服务实现 → 通知刷新 → 清理分隔符。
- 实现"服务转移"：当服务从一个域换到另一个域时，把 `store` 中的实现 symbol 整体搬移。

### 3.5 Loader 服务本身

- 监听 `internal/update`：**配置热更新写回** —— 插件调用 `ctx.fiber.update()` 后，变化被序列化写回 Entry 并触发 tree.write()（持久化）。
- 监听 `internal/plugin`：处理插件自销毁（`ctx.fiber.dispose()`）并把 `disabled: true` 写回配置。
- `builtins`：`cordis:` 前缀的内置模块（如 `cordis:group`）。
- `envData`：从 `CORDIS_SHARED` 环境变量读取进程间共享数据（CLI worker 与主进程通信）。
- **`ModuleLoader.fromInternal()`**：读取 Node 内部模块加载器（`--expose-internals` 或 `node-addon-require-builtin`）—— 这是 HMR 的基础。

---

## 4. 周边插件包

### 4.1 Timer（@cordisjs/plugin-timer@1.1.2，142 行）

| API | 说明 |
|---|---|
| `ctx.timeout(cb, delay)` / `ctx.timeout(delay): Promise` | 一次性定时（效应化，自动清理） |
| `ctx.interval(cb, delay)` / `ctx.interval(delay): AsyncIterable` | 周期定时 / 异步迭代器 |
| `ctx.throttle(cb, delay, noTrailing?)` | 节流（返回带 `.dispose` 的函数） |
| `ctx.debounce(cb, delay)` | 防抖 |

所有定时器都包在 `ctx.effect()` 里 → **context 销毁时自动 clearTimeout/clearInterval**。`interval(delay)` 无回调形式返回 async iterator，可被 `for await` 消费。

### 4.2 Logger-Console（@cordisjs/plugin-logger-console@1.0.1-SNAPSHOT，26 行）

- `ConsoleExporter extends Base`：覆盖 `%o/%O` formatter 为 `node:util.inspect`（深度无限、紧凑模式）。
- `getDefaults()`：用 `supports-color` 检测终端是否支持 ANSI 颜色（level 0-3）。

### 4.3 Include（@cordisjs/plugin-include@1.0.4，219 行）

- **配置文件插件**：把 YAML/JSON 文件作为 EntryTree 的数据源。
- 自定义 YAML 类型 `tag:yaml.org,2002:js` 支持 JS 表达式（`__jsExpr`）。
- **Patches**：`config.patches` 可按 id 插入条目（`insert`）或覆盖字段，用于"在配置文件中打补丁"。
- **原子写入**：`writeFile(filename + '.tmp')` → `rename` 到目标。
- 文件不存在且有 `initial` 时自动创建；`refresh()` 供 HMR 触发重读。

### 4.4 Group（@cordisjs/plugin-group@1.0.1-SNAPSHOT，3 行）

仅重导出 loader 的 `Group` 类。

### 4.5 HMR（@cordisjs/plugin-hmr@1.0.15，405 行）—— Node 深度定制

**功能**：配置文件/代码变化时热替换插件，无需重启进程。

**实现手段（极其依赖 Node 内部机制）**：
1. `chokidar` 监听文件。
2. 读 Node `ModuleLoader.loadCache` 构建模块依赖图（`job.linked`）。
3. 变化分类：**externals**（框架代码 → 需重启进程）/ **accepted**（可热替换）/ **declined**（依赖不可替换）。
4. 用 `Map.prototype.delete` 直接操作 ESM `loadCache` 和 CJS `Module._cache` 清除模块缓存，重新 import。
5. 失败回滚：备份缓存 → 重载 → 失败则恢复缓存并重新注册旧插件。
6. `@babel/code-frame` 对 esbuild 构建错误做源码定位展示。

**结论：HMR 是 Node 专属能力（操纵 V8 模块系统内部状态），Java 移植不可直接对应，需另行设计（见 Roadmap）。**

### 4.6 Utils（@cordisjs/utils@1.0.1-SNAPSHOT，42 行）

`List<T>`：**效应化集合** —— `list.push(v)` 走 `ctx.effect()`，context 销毁时元素自动移除。

### 4.7 Create（create-cordis@0.3.0，306 行）

脚手架 CLI：
1. 交互式询问项目名（`prompts`）。
2. 从 npm registry 下载模板 tarball（`get-registry` 检测 registry），`tar` 解压。
3. `stageYarnBin`：复杂的 yarn 二进制版本协商（yarn 1.x 委托给新版本等 5 条规则）。
4. 改写 package.json 项目名；可选 `git init` / 安装依赖 / 启动。

---

## 5. 核心设计模式总结

| # | 模式 | 体现 |
|---|---|---|
| 1 | **可逆效应（Effect + Dispose）** | 一切副作用（事件、定时器、服务、监听器）都注册为 effect，context 销毁时逆序回滚 |
| 2 | **响应式依赖（Inject/Provide + Epoch）** | 服务提供/卸载时自动通知并重载依赖方 |
| 3 | **统一 Context 上下文** | 所有组件只依赖一个 Context 对象，通过它访问一切 |
| 4 | **原型链继承** | Context 用 `Object.create` 链实现作用域嵌套（Java 需改用组合/委托） |
| 5 | **代理式属性解析** | 服务访问走 Proxy，支持拦截/自定义 getter（Java 需改为显式接口方法） |
| 6 | **Symbol 隔离域** | 用 symbol 做服务作用域键（Java 可用类型化 Key 或 String + 注册表） |
| 7 | **Callable Service** | 服务实例可被调用（`ctx.logger('x')`）（Java 不支持，改为方法形式） |
| 8 | **配置拦截链** | intercept 沿原型链合并配置覆盖 |
| 9 | **事件驱动解耦** | 内部事件 + 外部事件统一走同一总线 |
| 10 | **生成器效应** | 一个效应函数可 yield 多个 dispose（Java 需用 `List<Disposable>` 或迭代器替代） |

---

## 6. 对 Java 21 移植的影响评估

### 6.1 可直接映射的概念

| Cordis (TS) | Java 21 对应 |
|---|---|
| `Context` | `Context` 接口（方法式 API） |
| `Fiber` | `Fiber` 类 + 状态机枚举 |
| `effect(fn)` | `effect(EffectRunner)` / `Disposable` 接口 |
| `EventsService` 五种模式 | `EventBus`（emit/parallel/serial/bail/waterfall） |
| `RegistryService.plugin()` | `PluginRegistry.register()` |
| `ReflectService.provide/notify` | `ServiceRegistry`（Map<Key, Impl> + 通知机制） |
| `Service` 基类 | `Service` 抽象类 |
| `Logger` + Exporter | `Logger` + `Exporter` 接口（SLF4J 风格） |
| `Promise` | `CompletableFuture` |
| `WeakRef` | `java.lang.ref.WeakReference` |
| Standard Schema 配置校验 | 自定义 `Schema<T>` 接口 + 校验器（或 Jackson + Bean Validation） |
| `DisposableList` | `DisposableList`（逆序清理） |
| 配置树 Entry/EntryTree/EntryGroup | 同名类模型 |
| Timer 服务 | `ScheduledExecutorService` |

### 6.2 无法直接移植、需重新设计的部分

| Cordis 特性 | Java 的挑战 | 建议方案 |
|---|---|---|
| **Proxy 动态属性解析** | Java 无动态属性访问 | Context 改为显式接口方法（`ctx.get("name")` / `ctx.service(Name.class)`） |
| **原型链上下文继承** | Java 单继承 | 组合 + `ContextView` 委托链（parent 引用） |
| **`with(ctx){eval()}` 配置表达式** | 不安全、不可行 | 移除，或使用受限 SpEL（Spring Expression Language），或改为纯数据插值 |
| **callable service（`ctx.logger('x')`）** | 无函数对象 | `ctx.logger().named("x")` 方法链 |
| **then-able Fiber（`await ctx.plugin()`）** | 无 | `plugin()` 返回 `Fiber`，`fiber.await()` 返回 `CompletableFuture`；或直接返回 CF |
| **生成器效应（yield 多个 dispose）** | 无生成器 | 返回 `List<Disposable>` / `Iterator<Disposable>`，或 `EffectResult` 包装 |
| **HMR（操作 V8 模块系统）** | 类加载机制完全不同 | 自定义 ClassLoader 重载（局限大）；或提供"重启式热重载"（检测变更→重建容器） |
| **动态 import 插件** | `ServiceLoader` / `Class.forName` / 动态编译 | 见 Roadmap Phase 4 |
| **Symbol 隔离** | 无 Symbol 类型 | 类型化 `IsolationKey` / 全局单例 Key 对象 |
| **协变类型 + 泛型推导（NoInfer 等）** | Java 泛型 | 简化类型设计 |

### 6.3 风险与复杂度评估

- **核心（Context/Fiber/Events/Service）**：★☆☆ 可移植性高，是价值最高的部分。
- **Loader（Entry 树 + 隔离域）**：★★☆ 模型可移植，但配置求值需重新设计。
- **Logger**：★☆☆ 直接移植。
- **Timer**：★☆☆ 直接移植（JDK 内置）。
- **HMR**：★★★★ 高风险，建议降级为"开发模式容器重建"。
- **Create CLI**：★☆☆ Maven archetype 或自定义 CLI。

---

## 7. 参考价值总结

Cordis 的核心价值不在于某个单一特性，而在于**把"插件系统"提升为一套有理论支撑的运行时机制**：

1. **副作用管理**：所有插件副作用显式注册、自动逆序回滚 —— 这是任何长期运行的应用框架都需要的。
2. **依赖响应式**：服务提供/卸载自动触发依赖方重载 —— 插件可热插拔。
3. **统一上下文**：插件只需要一个对象就能访问所有能力。
4. **声明式加载**：配置文件声明插件树，运行时负责实例化、调和、持久化。

对 jcordis 而言，**建议完整移植核心三件套（Context / Fiber+Effect / Events）+ ServiceRegistry + 简化版 Loader**，这是 80% 的价值所在；HMR 与动态表达式求值则按实际需求取舍。
