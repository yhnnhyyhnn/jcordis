# jcordis

Java 21 实现的 **Cordis** —— 时空可组合性元框架（Meta-Framework of Spatiotemporal Composability）。

参考项目：[cordiverse/cordis](https://github.com/cordiverse/cordis)（TypeScript）

## 核心特性

- **时空可组合性**：`ctx.effect()` 注册可逆副作用（时间组合），插件销毁时逆序清理；`ctx.isolate()` / `ctx.intercept()` 构建服务隔离域（空间组合）
- **事件系统**：emit / bail / serial / parallel / waterfall 五种调度模式，thisArg 过滤
- **插件生命周期**：Fiber 状态机（依赖 epoch 驱动，注入齐备自动加载、缺失自动卸载）、`restart()` / 配置热更新
- **Loader 声明式配置**：Entry 树 + 配置 diff 同步（YAML/JSON 经 `ConfigParser` 策略解析）+ 隔离域（`Realm`：`#id` 本地域 / `@label` 共享域）
- **插件热加载 HMR**：运行时从 jar 动态加载插件（SPI 发现 + 独立 `PluginClassLoader`）、jar 变更热替换（原子替换 + 失败回滚）、完整类卸载
- **脚手架**：Maven 插件 / CLI 双入口生成应用与插件项目

## 模块结构

| 模块 | 说明 |
|---|---|
| `jcordis-core` | 核心框架：Context / Fiber 状态机 / EventBus / Registry / Logger（含 ConsoleExporter）/ Reflect（服务隔离）+ `EffectList`（effect 跟踪集合）+ `TimerService`（timeout/interval/throttle/debounce） |
| `jcordis-loader` | Entry 树 / 配置同步 / 隔离域 Realm / 插件 jar 加载（`PluginClassLoader`、`loadJar`/`replaceJar`/`unload`）+ `include` 子包（配置文件插件 `Include` / `ConfigParser` / HMR `Hmr` / `JarWatcher` jar 热替换）+ 分组标记 |
| `jcordis-cli` | `Scaffolder` 脚手架引擎 + CLI 入口 |
| `jcordis-maven-plugin` | Maven 插件：`create` / `create-plugin` / `check` |
| `jcordis-all` | **聚合 jar**：shade 合并全部运行时模块（除 maven-plugin）为单一 jar，业务系统一个坐标引入 |
| `examples/*` | hello-world / service-graph / config-app 示例 + demo-plugin（独立插件示例，不参与 reactor 构建） |

## 快速开始

```bash
# 应用脚手架（与 CLI 生成物一致）
mvn io.jcordis:jcordis-maven-plugin:0.1.0-SNAPSHOT:create -Dname=my-app

# 插件脚手架（内嵌插件契约：jcordis 依赖 provided + SPI 清单 + check goal）
mvn io.jcordis:jcordis-maven-plugin:0.1.0-SNAPSHOT:create-plugin -Dname=demo-plugin
```

插件项目产出**干净 jar**（仅插件类 + `META-INF/services/io.jcordis.core.registry.Plugin` 清单），`mvn verify` 自动执行 `check` goal 校验不混入三方库/框架类。

## 业务系统集成

```xml
<!-- 一个坐标获得全部 jcordis 模块（core/loader/utils/timer/logger-console/include/group/cli）。
     三方库（jackson/slf4j）不打入聚合 jar，经精简 pom 传递提供 -->
<dependency>
  <groupId>io.jcordis</groupId>
  <artifactId>jcordis-all</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 文档

- [Cordis 源码分析报告](./docs/cordis-analysis.md) —— 参考项目完整架构分析
- [Java 21 移植路线图](./docs/roadmap.md) —— 分阶段移植计划
- [进度记录](./docs/progress.md) —— 实现里程碑与验证结果
- [设计模式应用](./docs/patterns.md) —— 23 种 GoF 模式应用清单
- [插件热加载设计](./docs/hmr-design.md) —— ClassLoader 热替换、卸载语义、依赖模型（传递依赖 + 业务 BOM）
- [兼容性对照](./docs/compatibility.md) —— 与 cordis API 映射

## 构建与测试

```bash
mvn clean verify   # 14/14 模块，146 测试全绿
```
