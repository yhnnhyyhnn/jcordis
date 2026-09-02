# jcordis 1.0.0 Release Notes

**Java 21 实现的 Cordis 元框架（时空可组合性）** — 首个正式版本。

- 参考项目：[cordiverse/cordis](https://github.com/cordiverse/cordis)（TypeScript 4.0.0-rc.9）
- 代码量：143 文件 / ~15 600 行
- 测试：206 个（覆盖率门禁 LINE ≥80% / BRANCH ≥60%）

---

## ✨ 核心能力

- **时空可组合性**：`ctx.effect()` 可逆副作用（逆序清理）+ `ctx.isolate()` / `ctx.intercept()` 服务隔离域
- **事件系统**：emit / bail / serial / parallel / waterfall 五种派发 + thisArg 过滤；`internal/get`、`internal/set` 服务访问瀑布链；`internal/status` 状态转换通知
- **插件生命周期**：依赖 epoch 驱动的 Fiber 状态机（注入齐备自动加载、缺失自动卸载）、`restart()` / 配置热更新、`getEffects()` 自省；异步插件体（`CompletableFuture`）完成语义对齐 dispose.spec（销毁后不泄漏）
- **Loader 声明式装配**：Entry 树 + 配置 diff 同步、隔离域 Realm（`#id` 本地 / `@label` 共享 + 自动 GC）、entry inject 合并、`await` 就绪门控、跨 group `transfer`、`locate`
- **插件热加载（HMR）**：jar 运行时加载（SPI 发现 + `PluginClassLoader` 类隔离 + 完整卸载）、jar 原子热替换（失败回滚）、配置文件热重载
- **脚手架**：Maven 插件 / CLI 双入口（`create` / `create-plugin` / `check`），生成产物端到端验证可运行

## 🛠 修复（对照 cordis 参考逐文件复核）

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

## ⚡ 工程化

- 并发模型：per-fiber Monitor 锁（快照-处置分离，持锁不回调）+ 线程安全集合
- 设计模式：23+ 模式应用（含 Monitor/Snapshot 并发模式），`docs/patterns.md` 全量清单
- JMH 专业基准（`-Pbenchmark`）：serviceGet ~20ns、事件派发 ~45ns、fiber 创建 ~11.6μs
- 覆盖率门禁（jacoco）：core LINE 90.1% / BRANCH 77.3%，loader LINE 82.6% / BRANCH 65.7%
- CI 三重保障：构建 + 覆盖率门禁 + spotless 格式检查
- 聚合 jar：`jcordis-all` 单坐标引入全部运行时（独立加载端到端验证）

## 📦 模块（10）

`jcordis-core` · `jcordis-loader` · `jcordis-cli` · `jcordis-maven-plugin` · `jcordis-all` · `examples/hello-world` · `examples/service-graph` · `examples/config-app` · `examples/hmr-app`（另有独立 `examples/demo-plugin`）

## 📖 文档

- 双语 README（功能清单 / 并发模型 / 示例真实输出）
- `docs/compatibility.md`（与 Cordis 行为差异权威对照）· `docs/progress.md`（开发记录）· `docs/patterns.md`（设计模式）· `docs/plugin-development.md`（插件契约，英文）· `docs/perf.md`（性能基准）· `docs/hmr-design.md`（HMR 设计）

## 🚀 快速开始

```bash
mvn io.jcordis:jcordis-maven-plugin:1.0.0:create -Dname=my-app
# 或插件项目
mvn io.jcordis:jcordis-maven-plugin:1.0.0:create-plugin -Dname=demo-plugin
```

## ⚠️ 说明

- 与 Cordis 的已知差异（刻意裁剪）见 `docs/compatibility.md`：traceable/shadow 双上下文、isolate 服务 impl 原地迁移（用插件重启达成等价）、StandardSchema 配置校验、JS eval 表达式等
- 发布到 Maven Central 待凭据（Sonatype 账号 + GPG）就绪后执行
