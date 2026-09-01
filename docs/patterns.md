# jcordis 设计模式应用清单

> 本文档记录 jcordis 代码库中应用的 GoF 设计模式，标注每种模式的角色、位置与意图。
> 原则：**模式服务于真实需求**——仅在自然契合处应用，避免为模式而模式。

## 一、创建型模式（5/5）

| 模式 | 位置 | 角色与意图 |
|---|---|---|
| **Factory Method** | `Context.create()`、`Plugin.constructor(Class)`、`Plugin.object(name, inject, apply)` | 静态工厂创建 Context/Plugin；类插件经反射实例化（构造器选择策略） |
| **Abstract Factory** | `ConfigParser.YAML` / `ConfigParser.JSON` | 配置格式解析族：同一接口不同实现（YAML/JSON），`forPath` 按扩展名选择 |
| **Builder** | `EntryOptions.Builder` | 条目配置多可选字段的流式构建；替代裸公共字段，构造意图明确 |
| **Prototype** | `Context.extend()` / `ContextImpl.child()` | 上下文"复制"出新实例（继承链），等价 `Object.create` 原型语义 |
| **Singleton** | `ServiceKey.of(name)`（canonical 享元）、root 持有的 `ReflectService`/`RegistryService`/`EventBus`/`LoggerService` | 全局唯一键/服务，经 `actualRoot()` 共享 |

## 二、结构型模式（5/7）

| 模式 | 位置 | 角色与意图 |
|---|---|---|
| **Facade** | `Context` 接口的门面默认方法（on/emit/plugin/inject/mixin/logger） | 统一入口委托给 events()/registry()/reflect()/loggerService()，隐藏子系统 |
| **Composite** | `EntryTree` → `EntryGroup` → `Entry` 树；`Entry.subtree` 嵌套 | 树形结构：叶子（普通条目）与容器（group 条目）统一 Entry 处理 |
| **Adapter** | `Timer.Timer`（Runnable↔Disposable）、`EventFilter`（Service 作为 thisArg 过滤器）、`Include` 包装为 `Plugin` | 接口适配：不同协议间的桥接 |
| **Proxy** | `TimerService.scheduler()` 懒初始化（volatile + double-checked） | 惰性创建真实资源；`ContextImpl` 门面委托 |
| **Bridge** | `LoggerService`（抽象）↔ `Exporter`（实现）；`Message` 渲染与 exporter 分离 | 抽象与实现解耦，可独立演化 |

## 三、行为型模式（7/11）

| 模式 | 位置 | 角色与意图 |
|---|---|---|
| **Observer** | `EventBus`（on/once/emit） | 事件发布-订阅：注册/触发/过滤（thisArg + EventFilter） |
| **Chain of Responsibility** | `EventBus.serial/bail/waterfall`、`internal/update` 瀑布链、`Loader` 的 internal 监听 | 链式处理：bailed 短路 / next 传递 |
| **Template Method** | `EntryTree`（抽象 importPlugin/write）、`Realm`（抽象 suffix） | 骨架固定，子类/匿名类实现变体步骤 |
| **Strategy** | `Exporter`（ConsoleExporter 等）、`ConfigParser`（YAML/JSON） | 算法族可互换：渲染策略、解析策略 |
| **State** | `FiberState` 枚举 + `FiberImpl.transition(old, new)` | 状态机：INACTIVE↔ready 依赖切换触发 reload/unload |
| **Memento** | `EntryOptions.Snapshot`（snapshot/restore） | 配置快照：更新前捕获，回滚时恢复（HMR/配置更新语义） |
| **Command** | `Command` / `Command.TreeCommand`（create/update/remove） | 树操作封装为命令对象：与执行机制解耦，可组合/排队 |
| **Mediator**（隐式） | `Context` 作为中央协调者 | 服务/事件/插件/日志的集中交互点 |
| **Iterator**（隐式） | `DisposableList`（Iterable）、`EntryTree.entries()` | 集合遍历的统一接口 |
| **Factory Method**（隐式） | `EffectResult.of(...)`、`Disposable.noop()`、`EventOptions.of(...)` | 语义化工厂 |
| **Monitor**（并发） | `FiberImpl.lifecycle` 锁 | 复合状态操作原子化：异步插件体完成线程与销毁线程对 disposables/effectMetas 的并发修改串行化（快照-处置分离，持锁不回调） |
| **Snapshot**（并发） | `FiberImpl.drainEffects()` / `disposeTail(from)` | 锁内快照 + 锁外逆序处置：避免持锁调用用户回调（可重入安全），同时保证清空原子性 |

## 四、刻意未应用的模式

| 模式 | 原因 |
|---|---|
| **Visitor** | EntryTree 遍历已由 entries()/内部递归覆盖，无跨类型操作需求 |
| **Interpreter** | 配置插值刻意保持纯数据（安全决策），无表达式语法树 |
| **Singleton（全局）** | 仅 root 持有服务单例，无全局可变状态 |
| **Flyweight（显式）** | ServiceKey canonical 已隐含享元语义，无更多共享需求 |
