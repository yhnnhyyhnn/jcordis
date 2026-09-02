# jcordis

<p align="left">
  <a href="README.md">English</a> | 简体中文
</p>

<p align="left">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-orange">
  <img alt="JUnit" src="https://img.shields.io/badge/JUnit-5-green">
  <img alt="License" src="https://img.shields.io/badge/License-Apache--2.0-blue">
</p>

Java 21 实现的 **Cordis** —— 时空可组合性元框架（Meta-Framework of Spatiotemporal Composability）。

参考项目：[cordiverse/cordis](https://github.com/cordiverse/cordis)（TypeScript）

## 架构总览

```
开发脚手架工具 ──生成──> 使用方（业务系统 / examples / 插件 jar）
                              │ Maven 单一坐标引入
                              ▼
                        jcordis-all（shade 聚合 jar）
                              │ shade 合并打包
                              ▼
        jcordis 运行时框架 = jcordis-core + jcordis-loader
                              │
                              ▼
                  三方依赖（SLF4J / Jackson，传递提供）
```

![jcordis 架构图](docs/architecture.png)

> 高清可编辑源文件：[docs/architecture.drawio](docs/architecture.drawio)（draw.io 打开可继续编辑）

## 核心特性

- **时空可组合性**：`ctx.effect()` 注册可逆副作用（时间组合），插件销毁时逆序清理；`ctx.isolate()` / `ctx.intercept()` 构建服务隔离域（空间组合）
- **事件系统**：emit / bail / serial / parallel / waterfall 五种调度模式，thisArg 过滤；内部事件瀑布（`internal/get`、`internal/set` 可拦截服务访问）、`internal/status` 状态转换通知
- **插件生命周期**：Fiber 状态机（依赖 epoch 驱动，注入齐备自动加载、缺失自动卸载）、`restart()` / 配置热更新、`getEffects()` 自省；失败插件仅 `update()` 可恢复；异步插件体（返回 `CompletableFuture`）完成时收集 disposable，fiber 先销毁则不泄漏
- **Loader 声明式配置**：Entry 树 + 配置 diff 同步（YAML/JSON 经 `ConfigParser` 策略解析）+ 隔离域（`Realm`：`#id` 本地域 / `@label` 共享域，无引用时自动回收）+ entry `inject` 合并、`await` 就绪门控、跨 group `transfer(id, parent)`、`locate()`
- **插件热加载 HMR**：运行时从 jar 动态加载插件（SPI 发现 + 独立 `PluginClassLoader`）、jar 变更热替换（原子替换 + 失败回滚）、完整类卸载；配置文件热重载经 `Hmr`（见 `examples/hmr-app` 演示）
- **脚手架**：Maven 插件 / CLI 双入口生成应用与插件项目
- **并发安全**：per-fiber 监控锁（快照-处置分离，持锁不回调用户代码）、服务注册原子化（`putIfAbsent`）、线程安全效应集合与依赖缓存（详见 [Concurrency Model](#concurrency-model)）

## 模块结构

| 模块 | 说明 | 依赖 |
|---|---|---|
| `jcordis-core` | 核心框架：Context / Fiber 状态机 / EventBus / Registry / Logger（含 ConsoleExporter）/ Reflect（服务隔离）+ `EffectList`（effect 跟踪集合）+ `TimerService`（timeout/interval/throttle/debounce） | — |
| `jcordis-loader` | Entry 树 / 配置同步 / 隔离域 Realm / 插件 jar 加载（`PluginClassLoader`、`loadJar`/`replaceJar`/`unload`）+ `include` 子包（配置文件插件 `Include` / `ConfigParser` / HMR `Hmr` / `JarWatcher` jar 热替换）+ 分组标记 | core |
| `jcordis-cli` | `Scaffolder` 脚手架引擎 + CLI 入口 | core |
| `jcordis-maven-plugin` | Maven 插件：`create` / `create-plugin` / `check` | — |
| `jcordis-all` | **聚合 jar**：shade 合并全部运行时模块（除 maven-plugin）为单一 jar，业务系统一个坐标引入 | core + loader + cli |
| `examples/*` | hello-world / service-graph / config-app 示例 + demo-plugin（独立插件示例，不参与 reactor 构建） | — |

## 环境要求

- JDK 21+
- Maven 3.x

## 快速开始

**创建应用脚手架**（与 CLI 生成物一致）：

```bash
mvn io.jcordis:jcordis-maven-plugin:1.0.1-SNAPSHOT:create -Dname=my-app
```

**创建插件脚手架**（内嵌插件契约：jcordis 依赖 provided + SPI 清单 + check goal）：

```bash
mvn io.jcordis:jcordis-maven-plugin:1.0.1-SNAPSHOT:create-plugin -Dname=demo-plugin
```

## 示例

| 示例 | 演示内容 | 运行方式 |
|---|---|---|
| `examples/hello-world` | 最小插件生命周期（加载/卸载） | `mvn -pl examples/hello-world exec:java` |
| `examples/service-graph` | 服务提供 / 依赖 / 隔离域、依赖响应 | `mvn -pl examples/service-graph exec:java` |
| `examples/config-app` | YAML 配置 + include + group | `mvn -pl examples/config-app test` |
| `examples/hmr-app` | 配置热重载 + 插件 jar 热替换 | `mvn -pl examples/hmr-app exec:java`（改 `jcordis.yml` / `plugins/` 观察热重载） |

**示例输出**（JDK 21 / Windows）：

```text
$ mvn -pl examples/hello-world exec:java
2026-09-02 10:03:12 [I] hello hello world from jcordis
--- unloading the plugin ---
2026-09-02 10:03:12 [I] hello goodbye

$ mvn -pl examples/service-graph exec:java
app connected; database connections = 1
app fiber state after provider removal = PENDING

$ mvn -pl examples/config-app exec:java
entries loaded: 3 (feature-a + group + nested-feature)

$ mvn -pl examples/hmr-app exec:java
[hmr-app] watching ...jcordis.yml (config) and ...plugins (plugin jars)
[hmr-app] edit jcordis.yml or swap plugins/*.jar to see hot reload
```

## 并发模型

插件体默认同步执行；异步插件体返回 `CompletableFuture`（如异步 init）。异步体完成时，其产生的 disposable 在 per-fiber **监控锁**（并发模式）下收集，状态转换竞态被收敛为：

- 异步体在 fiber 销毁**之后**完成 → 产生的 disposable **立即处置**，绝不泄漏（对齐 `dispose.spec` 的 `async return 2`）；
- 销毁后到达的失败被忽略——fiber 保持 `DISPOSED` 而非 `FAILED`；
- 效应清理在锁内**快照**、锁外**逆序处置**——绝不持锁调用用户回调，disposer 可安全回调 fiber（如 `ctx.effect` / `ctx.get`）。

## 业务系统集成

一个坐标获得整个运行时框架（core + loader，含已并入其中的 timer / console-exporter / include / group / utils 功能）：

```xml
<dependency>
  <groupId>io.jcordis</groupId>
  <artifactId>jcordis-all</artifactId>
  <version>1.0.1-SNAPSHOT</version>
</dependency>
```

三方库（jackson/slf4j）不打入聚合 jar，经精简 pom 传递提供。

## 插件开发

插件项目产出**干净 jar**：仅包含插件类 + `META-INF/services/io.jcordis.core.registry.Plugin` 清单。

- `mvn verify` 自动执行 `check` goal，校验插件 jar 不混入三方库 / 框架类
- 运行时由 `PluginClassLoader` 独立加载，支持 jar 变更热替换与完整类卸载
- 完整契约见[插件开发指南](docs/plugin-development.md)（英文）与[插件热加载设计](docs/hmr-design.md)（中文）

## 文档

| 文档 | 说明 |
|---|---|
| [Cordis 源码分析报告](docs/cordis-analysis.md) | 参考项目完整架构分析 |
| [Java 21 移植路线图](docs/roadmap.md) | 分阶段移植计划 |
| [进度记录](docs/progress.md) | 实现里程碑与验证结果 |
| [设计模式应用](docs/patterns.md) | 23 种 GoF 模式应用清单 |
| [插件热加载设计](docs/hmr-design.md) | ClassLoader 热替换、卸载语义、依赖模型（传递依赖 + 业务 BOM） |
| [插件开发指南](docs/plugin-development.md) | 插件契约、打包、隔离、热替换（英文） |
| [性能基准](docs/perf.md) | 吞吐基线（ns/op）、与 Cordis 对比 |
| [兼容性对照](docs/compatibility.md) | 与 cordis API 映射 |

## 构建与测试

```bash
mvn clean verify   # 10/10 模块，206 测试全绿
```
