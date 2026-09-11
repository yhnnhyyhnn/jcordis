# Changelog

## 1.0.2-SNAPSHOT（未发布）

### HMR / Loader

- **`Hmr.watch(path, callback)` 通用文件监视**（对齐 cordis `caab04e`）：监视任意路径，同路径可多回调，经 `ctx.effect` 注册（注册方 fiber 销毁自动注销），`isWatching(path)` 查询；`Hmr` 注册为 `hmr` 服务供插件发现
- **`Include` 自治重载**：`refresh()` 重读配置并重应用；`apply` 时若 `hmr` 服务可用则自注册 `watch(path, refresh)`，配置文件由其自身负责热重载（与便捷 config 模式的注册去重）
- **loader `commit(EntryChange)` + Include journal 双向同步**（对齐 cordis `c594d1a`）：
  - `EntryTree.write()` → 结构化 `commit(EntryChange)`（id/group/from/options/legacy）+ 根 tree 监听器；调用点为 `create/remove/update/transfer`、插件自更新 config、插件自禁用
  - `Include` 将运行时变更记录到 `Journal` 并**写回配置文件**（幂等、原子写、文件被外部修改时先合并）；文件编辑经三方 reconcile 后**文件优先**（冲突告警）
  - 匿名条目 id 稳定分配（编辑邻居不再重启）；patch 拥有项不落盘；文件外的运行时新建条目不回写（保守差异）
  - 修正 `internal/plugin` 守卫与 `EntryGroup.remove` 顺序（避免删除/组停止误写 `disabled: true`）
- **修复：jar 注册表操作并发竞态**（Windows 上 jar 句柄泄漏、临时目录无法删除）——`Loader.loadJar/replaceJar/unload` 串行化：watcher worker 线程与重试池并发调用时 `classLoaders.put` 竞态会导致类加载器被覆盖且从未 `close`

### 测试

- 新增 `EntryChangeTest`（6）、`JournalTest`（14）、`IncludeJournalTest`（6）、`HmrWatchTest`（2）、`IncludeIntegrationTest`（+2）：**215 → 241**
- `AggregateJarIT/E2eIT` 不再硬编码 jar 版本（surefire/failsafe 注入 `project.version`）

### 文档

- `docs/compatibility.md` 对齐基线更新至 cordis `4.0.0-rc.10`，补充 `hmr.watch()` 实现差异行
- README（中英）HMR 能力描述更新

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
