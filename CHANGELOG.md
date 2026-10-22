# Changelog

## 1.0.4-SNAPSHOT（未发布）

### 功能对齐：logger 一族三项

- **`%o`/`%O` 按 JSON 渲染**（对齐 `JSON.stringify` formatter）：字符串加引号、Map/数组/Iterable 展开；深度上限防循环（缩写 `…`，日志调用永不失败）
- **logger intercept 的 `level`**：`ctx.intercept("logger", {name, level})` 两字段均生效；级别阈值链对齐为 exporter per-name → exporter default → logger level → INFO
- **`Exporter.formatters()` 自定义占位符策略**：exporter 可按字母覆盖默认 formatter（新增 `Exporter.ValueFormatter` 接口）

### cordis 2026-10 第二批上游对齐（`c261dd2..34c8ac5`，5 项移植）

- **parallel 失败的 AggregateError 描述失败事件**（`1a87f46`）：消息为 `one or more listeners of event "X" failed`
- **`Include` 配置文件写入的 Windows 文件锁防护**（`db20555`）：相同内容跳过写入；rename 被占用时 10 次递增退避重试
- **jar 热替换不复活用户禁用的条目**（`fabd555`）：stage 3 按 dispose 前快照决策（disabled/removed/已重建三种跳过）
- **匿名条目 id 防碰撞**（`0e0cc2c`）：patch insert 的显式 id 参与防碰撞；不复用已被显式占用的历史匿名 id
- **移除 `cordis:` builtin 前缀**（`34c8ac5`）：跟随上游，builtin 按裸名解析

## 1.0.3 — 2026-10-13

**已发布至 Maven Central**（`io.github.yhnnhyyhnn:*:1.0.3`，parent + 5 模块）。

**全库缺陷修复版本**：两轮审查（三路并进，以 cordis 为基准）发现的 30 项缺陷全部闭环——22 项直接修复 + 8 项审查遗留处置，另含 cordis 2026-10 上游批次的行为对齐。

### 缺陷修复（全库审查专项，修复 22 项）

**Include / HMR**：

- **`internal/update` 钩子改 fiber-local**：不再误拦截 config 恰好含相同 `path` 键的无关插件（其重启曾被短路吞掉）
- **文件驱动 re-apply 保留文件外条目**：include 不再把自己删成孤儿 fiber（监听器/journal/HMR watch 全泄漏），运行时创建的外部条目不再被文件保存静默移除
- **`Entry.initializing` 重入守卫**：初始化期间的树重入不再构造第二个插件实例（无限递归）
- **销毁注销 HMR watch + `refresh`/`readAndApply` disposed 守卫**：僵尸 include 不再重写加载树；同路径每个 include 各自注册 watch
- **journal 并发保护 + 写回 snapshot/swap**：任意树变更线程与 HMR 轮询线程的竞态消除；写回期间到达的变更不再被 `clear()` 一并丢弃；`stop` 前最后冲刷
- **Hmr 便捷路径走 `ConfigParser`**：配置文件里 group 内的子插件不再被静默丢弃；匿名条目不再折叠成一个
- **reconcile 补 moved-both 冲突**：文件与运行时同时移动条目时文件胜出并报告（不再静默回滚用户编辑）
- **JarWatcher 每变更 10 次重试预算**：永久损坏的 jar 不再 200ms 无限循环

**Core**：

- **销毁链逐项容错**：一个 dispose 回调抛异常不再中断整条销毁链（剩余 fiber 曾永久泄漏）；plugin 销毁改调用线程同步执行（消除 ForkJoinPool 饥饿死锁面）
- **`set(ServiceKey)` 未注册 key 抛清晰错误**（原 NPE）
- **日志保留异常堆栈**：Throwable 首参渲染类名/堆栈/cause 链/AggregateError 成员（原只剩一行 message）
- **inject 配置生效**：非 null inject 配置写入 fiber ctx 的 intercept 层（原全库无消费点、静默失效）
- **`internal/service` 经 per-name 过滤**：不再跨隔离域泄漏
- 低风险：hooks 空表删除、`once` 并发 CAS、重复注册注销单删、`%d/%f` 非数字 NaN 容错、调色板索引掩码、`LoggerService.buffer()` 快照、`normalizeGroups` 过滤 null 子项

### 测试

- 352 → **358**（`FiberDisposeChainTest` 3、`HmrTest` 2、`EventBusEdgeTest` 1、`IncludeUpdateScopeTest` 2——后两项为修复前实证变红的复现测试）

### 对齐补全（审查遗留 8 项，全部处置）

- **`registry.delete` 去除冗余 join**（dispose 已同步化）
- **`Service.resolveConfig` 分层合并**：`hasIntercept` 计入显式 null 层（对合并无贡献，对齐 `Object.hasOwn`+`Object.assign`）；多层 Map 逐键合并后者胜；非 Map 层整体替换
- **`ctx.get` 的 required-service 语义**（对齐 cordis 定义链行走）：优先 fiber 本地解析快照（dispose 后不再读到残留 impl），inject 声明未解析抛 `cannot get required service`；根 fiber 与未 inject 的名字保持 lenient
- **`EntryOptions.clear(key)`**：显式实现 cordis 的 null-删除语义（Java 字段无法表达"显式缺席"）
- **`Entry.update` 逐键 diff**（对齐 force 语义）：intercept/inject 文件修改生效（重建上下文 + notify，不重启 body）；重启仅由 config diff 驱动；group 总是重启级联子条目；isolate 保持重启（无 impl 原地迁移）；transfer 不再重启
- **`Entry.id()` 无条件父前缀**；**`provide`/`mixin` props 互斥守卫**；**`ServiceKey.of` 去全局缓存表**（按值等价，消除 JVM 级累积）
- 358 → **374**（`PropsGuardTest` 4、`ResolveConfigLayersTest` 6、`EntryOptionsClearTest` 4 + 两个用例改写为新契约）

## 1.0.2 — 2026-10-13

**已发布至 Maven Central**（`io.github.yhnnhyyhnn:*:1.0.2`，parent + 5 模块）。

### Core（fiber / 事件 / Timer，对齐 cordis 2026-10 上游批次 `f8ea3cd..c261dd2`）

- **fiber 重载版本号**（对齐 cordis `c261dd2` #189）：epoch 嵌入 `revision`，`restart()` 递增而非途经 INACTIVE——并发 update/restart 期间被取代的异步 load 只清理不污染新目标（旧 body 抛错不再把新 fiber 置 FAILED、旧 disposable 立即处置、不清新 load 的 inertia）；`transition` 补 active→active 分支，依赖服务换新 fiber 后依赖方正确重载
- **配置解析时机与提交分离**（对齐 cordis `1431598` #134）：新增 `internal/config` 瀑布（**每次重载与每次 update** 都解析，插件看到 resolved、fiber 保存源 config；无 schema 时默认透传，作为扩展点）；新增 `internal/commit`（update 的持久化与重启解耦，`noSave` 跳过，未加载 fiber 也提交）；未加载 fiber 的 update 跳过钩子链直接重启；`internal/update` 链签名简化为 `(config, next)`
- **`internal/update` 钩子随 fiber 处置**（对齐 cordis `7d0a90d` #56）：fiber 本地钩子注册改经 `fiber.effect`，重载自动注销——修复插件每重载一次累积一个钩子（3 次 update 实际触发 1+2+3 次）
- **throttle/debounce dispose 防护**（对齐 cordis `10e1b1f` #161）：dispose 后 wrapper 永不再执行（此前窗口已过时立即分支照常跑 callback）

### HMR / Loader

- **jar 热替换三阶段部分重载**（对齐 cordis `b280b6c`）：`Loader.replaceJar` 拆为**验证**（全有或全无，失败不触碰任何 entry）→ **卸载**（本轮 entry 先全部 dispose，dispose 前 drain 进行中的异步初始化）→ **重载**（逐 entry；祖先也在一轮则跳过以避免双实例；失败隔离、无回滚，下次变更重试）
- **`Hmr.watch(path, callback)` 通用文件监视**（对齐 cordis `caab04e`）：监视任意路径（文件按 mtime；**目录按整棵树的指纹**，增删改任一文件均触发），同路径可多回调，经 `ctx.effect` 注册（注册方 fiber 销毁自动注销），`isWatching(path)` 查询；`Hmr` 注册为 `hmr` 服务供插件发现
- **`Include` 自治重载**：`refresh()` 重读配置并重应用；`apply` 时若 `hmr` 服务可用则自注册 `watch(path, refresh)`，配置文件由其自身负责热重载（与便捷 config 模式的注册去重）
- **loader `commit(EntryChange)` + Include journal 双向同步**（对齐 cordis `c594d1a`）：
  - `EntryTree.write()` → 结构化 `commit(EntryChange)`（id/group/from/options/legacy）+ 根 tree 监听器；调用点为 `create/remove/update/transfer`、插件自更新 config、插件自禁用
  - `Include` 将运行时变更记录到 `Journal` 并**写回配置文件**（幂等、原子写、文件被外部修改时先合并）；文件编辑经三方 reconcile 后**文件优先**（冲突告警）
  - 匿名条目 id 稳定分配（编辑邻居不再重启）；patch 拥有项不落盘；文件外的运行时新建条目不回写（保守差异）
  - 修正 `internal/plugin` 守卫与 `EntryGroup.remove` 顺序（避免删除/组停止误写 `disabled: true`）
- **修复：jar 注册表操作并发竞态**（Windows 上 jar 句柄泄漏、临时目录无法删除）——`Loader.loadJar/replaceJar/unload` 串行化：watcher worker 线程与重试池并发调用时 `classLoaders.put` 竞态会导致类加载器被覆盖且从未 `close`

### 示例与文档

- 新增 **`examples/journal-app`**（第 11 个模块）：配置双向同步演示（运行时变更写回文件 / 文件编辑重应用 / 冲突时文件优先）+ 3 个端到端测试；README 双语补真实输出
- `examples/config-app` 与 `journal-app` 不再依赖工作目录（从 classpath 模板复制到 `target/` 后运行，可从任意目录 `mvn -pl … exec:java`）
- 修正 README / `docs/hmr-design.md` 中遗留的旧坐标 `io.jcordis` → `io.github.yhnnhyyhnn`（带 1.0.1 版本）
- `docs/compatibility.md` 对齐基线更新至 cordis `4.0.0-rc.10`；README（中英）HMR / 双向同步能力描述与模块数更新

### 工具链

- **脚手架依赖版本可配置**：生成的 pom 不再硬编码 SNAPSHOT——默认取脚手架自身构建版本（Maven 资源过滤写入 `jcordis-version.properties`），可用 `-Djcordis.version=<v>`（CLI/Maven 插件 `jcordis.version` 参数）/ `Scaffolder.create(name, target, version)` 覆盖
- CI：并发测试偶发时序失败增加单次重试（`-Dsurefire.rerunFailingTestsCount=1`），保持信号且不掩盖真实失败

### 修复

- **`Logger.format` 遇到 null 参数抛 NPE**：内部用 `List.of(args)`，而 `List.of` 不允许 null 元素——`logger.info("value: %s", null)` 直接崩溃。改为 `Arrays.asList` 包装（null 是合法的日志值）
- **`Journal.flatten` 复制 group 子列表** → `detach`/`place` 作用于副本，group 内的 journal 变更（移动/删除子条目）静默失效。现直接复用 `config` 原列表（仅混合类型列表才过滤复制）
- **`Include` 的 patch `insert` 未做类型转换** → YAML/JSON 写法的 `patches[].insert`（原始 Map）被强转为 `EntryOptions` 而抛 `ClassCastException`（该写法的 patch 插入完全不可用）。新增 `ConfigParser.convert(Object)` 统一转换（含 group 规范化），`initial` 与 patch `insert` 均走它
- **删除死代码 `util/DisposableList`**（68 行）：模块重组后无任何引用（effect 收集由 `FiberImpl` 的 `List<Disposable>` + Monitor 锁实现）；`EffectList` 保留（有独立测试与文档引用）

### 测试与覆盖率（journal-app 加入 reactor）

- 新增 `EntryChangeTest`（6）、`JournalTest`（14）、`IncludeJournalTest`（6）、`HmrWatchTest`（3）、`JarPartialReloadTest`（4）、`JournalAppTest`（3）、`JournalBranchesTest`（19）、`IncludePatchTest`（10）、`LoaderGuardsTest`（9）、`LoggerFormatTest`（11）、`LoggerServiceEdgeTest`（6）、`ConsoleExporterEdgeTest`（6）、`TimerEdgeTest`（9）、`PluginFactoryTest`（6）、`EventBusEdgeTest`（9）、`IncludeIntegrationTest`（+2）、`ScaffolderTest`（+3）：**215 → 337**
- 覆盖率：`jcordis-loader` LINE 82.7% → **88.4%**、BRANCH 65.4% → **76.9%**；`jcordis-core` LINE 90.2% → **95.8%**、BRANCH 77.7% → **88.9%**
- `AggregateJarIT/E2eIT` 不再硬编码 jar 版本（surefire/failsafe 注入 `project.version`）

## 1.0.1 — 2026-09-11

**Maven Central 首次发布**：`io.github.yhnnhyyhnn:jcordis-*:1.0.1`（此前 1.0.0 仅本地/仓库分发）。

### 发布与分发

- **groupId 迁移** `io.jcordis` → `io.github.yhnnhyyhnn`：Central 命名空间（GitHub 账号自动验证）所需；Java 包名与 SPI 路径保留 `io.jcordis.*`
- **发布管线**：移除旧 OSSRH `distributionManagement`，接入 `central-publishing-maven-plugin:0.6.0`（Central Portal 上传）+ `maven-gpg-plugin`（工件签名，`passphraseServerId=jcordis-gpg`）
- **`jcordis-all` 聚合模块**：新增标记类（空源码模块无法满足 Central 的 sources/javadoc 校验）
- 发布顺序约束：parent 先行（子模块 pom 的元数据继承需 Central 可见）

### 修复与对齐

- `EventBus.waterfall` 递归派发 + 每级 `next` 单次调用守卫（对齐 cordis `5b195b3`）
- M-C4 agent scope spike 验证（`AgentScopeTest` 4 例）：子 fiber 创建/拆除、作用域服务回滚、影子注册均已内建

### 工程化

- CI：`workflow name` 移除 GitHub 校验器拒绝的字符（非 ASCII、未引号冒号）
- Codecov 接入：本地三模块报告上传；CI 无 token 时跳过上传（干净日志）
- `docs/agent-scope.md`：Agent 场景对接指南（用法 + 反模式 + 结论）

## 1.0.0 — 2026-09-02

**Java 21 实现的 Cordis 元框架（时空可组合性）** — 首个正式版本。

- 参考项目：[cordiverse/cordis](https://github.com/cordiverse/cordis)（TypeScript 4.0.0-rc.9）
- 代码量：143 文件 / ~15 600 行
- 测试：206 个（覆盖率门禁 LINE ≥80% / BRANCH ≥60%）

---

### ✨ 核心能力

- **时空可组合性**：`ctx.effect()` 可逆副作用（逆序清理）+ `ctx.isolate()` / `ctx.intercept()` 服务隔离域
- **事件系统**：emit / bail / serial / parallel / waterfall 五种派发 + thisArg 过滤；`internal/get`、`internal/set` 服务访问瀑布链；`internal/status` 状态转换通知
- **插件生命周期**：依赖 epoch 驱动的 Fiber 状态机（注入齐备自动加载、缺失自动卸载）、`restart()` / 配置热更新、`getEffects()` 自省；异步插件体（`CompletableFuture`）完成语义对齐 dispose.spec（销毁后不泄漏）
- **Loader 声明式装配**：Entry 树 + 配置 diff 同步、隔离域 Realm（`#id` 本地 / `@label` 共享 + 自动 GC）、entry inject 合并、`await` 就绪门控、跨 group `transfer`、`locate`
- **插件热加载（HMR）**：jar 运行时加载（SPI 发现 + `PluginClassLoader` 类隔离 + 完整卸载）、jar 原子热替换（失败回滚）、配置文件热重载
- **脚手架**：Maven 插件 / CLI 双入口（`create` / `create-plugin` / `check`），生成产物端到端验证可运行

### 🛠 修复（对照 cordis 参考逐文件复核）

- 构建断裂：缺失测试 fixture 重建、版本/引用清理
- `Entry.update` 配置变更不重启（copyInto 后比较恒等）→ legacyConfig 前置捕获
- `Logger %C` 格式化吞值；`ReflectService.set` 跨 fiber 校验；`checkImpl` 未调用 `Impl.check()` 谓词
- 异步插件体失败卡 LOADING / FAILED 无恢复语义 → error 字段 + 仅 `update()` 恢复
- intercept/isolate 更新不传播到运行中 fiber → `rebindContext` 重启前重绑定
- isolate 变更在 `loader.read()`/HMR 路径被丢弃 → `isolateChanged` 分支 + 变更名 notify
- `unloadBody` 清依赖缓存破坏 restart 重解析；`EntryGroup.update` 无序迭代
- YAML group 嵌套 config 不反序列化（`normalizeGroups` 递归修复）
- CLI 模板重复 provide loader / logger-console 未实例化
- 并发审计（压力测试驱动，10+ 真实缺陷）：provide CAS、EventBus unregister AIOOBE、Fiber store/EntryGroup data 并发损坏、disposeTail 误删并发注册、JarWatcher 句柄泄漏（retry 门控 + try/finally + stop 有序关闭）、Hmr mtime 毫秒漏检

### ⚡ 工程化

- 并发模型：per-fiber Monitor 锁（快照-处置分离，持锁不回调）+ 线程安全集合
- 设计模式：23+ 模式应用（含 Monitor/Snapshot 并发模式），`docs/patterns.md` 全量清单
- JMH 专业基准（`-Pbenchmark`）：serviceGet ~20ns、事件派发 ~45ns、fiber 创建 ~11.6μs
- 覆盖率门禁（jacoco）：core LINE 90.1% / BRANCH 77.3%，loader LINE 82.6% / BRANCH 65.7%
- CI 三重保障：构建 + 覆盖率门禁 + spotless 格式检查
- 聚合 jar：`jcordis-all` 单坐标引入全部运行时（独立加载端到端验证）

### 📦 模块（10）

`jcordis-core` · `jcordis-loader` · `jcordis-cli` · `jcordis-maven-plugin` · `jcordis-all` · `examples/hello-world` · `examples/service-graph` · `examples/config-app` · `examples/hmr-app`（另有独立 `examples/demo-plugin`）

### 📖 文档

- 双语 README（功能清单 / 并发模型 / 示例真实输出）
- `docs/compatibility.md`（与 Cordis 行为差异权威对照）· `docs/progress.md`（开发记录）· `docs/patterns.md`（设计模式）· `docs/plugin-development.md`（插件契约，英文）· `docs/perf.md`（性能基准）· `docs/hmr-design.md`（HMR 设计）

### 🚀 快速开始

```bash
mvn io.github.yhnnhyyhnn:jcordis-maven-plugin:1.0.0:create -Dname=my-app
# 或插件项目
mvn io.github.yhnnhyyhnn:jcordis-maven-plugin:1.0.0:create-plugin -Dname=demo-plugin
```

### ⚠️ 说明

- 与 Cordis 的已知差异（刻意裁剪）见 `docs/compatibility.md`：traceable/shadow 双上下文、isolate 服务 impl 原地迁移（用插件重启达成等价）、StandardSchema 配置校验、JS eval 表达式等
- 发布到 Maven Central 待凭据（Sonatype 账号 + GPG）就绪后执行
