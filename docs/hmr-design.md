# jcordis 插件热加载设计（HMR：ClassLoader 热替换）

> 状态：设计稿（待确认后编码）
> 对应参考：cordis `packages/hmr` 的**模块级热替换**（partialReload）在 Java 运行时的等价物

## 1. 背景

cordis 的 HMR 在 Node 中做**模块热替换**：监听源码文件变更 → 清 ESM/CJS 模块缓存 → 重新 import → `registry.delete(plugin)` + 重建 fiber，实现插件实例级热替换。

Java 的类一经加载不可变、普通 ClassLoader 无法卸载类。要在 jcordis 实现同构语义，唯一途径是 **ClassLoader 控制**：

- 每个插件 jar 用**独立 ClassLoader** 加载 → 丢弃该 ClassLoader 即实现"模块缓存清除"；
- 重新加载 = 新 ClassLoader 加载新 jar → 等价于"重新 import"；
- 插件以 **jar 包**为分发/变更单位（Java 编译产物），而非源码文件。

## 2. 现状与差距

| 维度 | jcordis 现状 | cordis HMR | 差距 |
|---|---|---|---|
| 插件来源 | `builtins`/`modules` 注册表（编译期 `mock`/`builtin` 注册的 Java 对象） | 运行时从 node_modules import | **插件不能运行时从 jar 动态加载** |
| 热替换 | 仅**配置变更** → `loader.root.update` 树 diff → 插件 fiber 重启（`Hmr.java`） | 文件变更 → 模块缓存清除 → 插件实例替换 | **缺模块（jar）级热替换** |
| 卸载 | `EntryGroup.remove` dispose fiber + 注册表移除（对象级） | 清缓存 + registry.delete | **缺 ClassLoader 级回收** |

## 3. 目标

1. 运行时从插件 jar 动态加载插件（SPI 发现）；
2. 插件 jar 更新时**自动热替换**（不重启进程）；
3. 插件可**完整卸载**（类回收）；
4. 插件依赖的三方包**统一版本管理**（parent-first 委托）；
5. **插件 jar 干净**：插件三方库全部由宿主业务系统提供（jcordis 是基础能力包，最终嵌入业务系统）。

## 4. 总体架构（ClassLoader 层级）

```
Bootstrap / Platform ClassLoader（JDK）
└── App ClassLoader（jcordis 框架 + 全部三方库，Maven dependencyManagement 收敛版本）
     ├── PluginClassLoader A ──→ demo-plugin.jar（parent-first 委托）
     ├── PluginClassLoader B ──→ other-plugin.jar
     └── ...（每插件一个实例，可独立 close）
```

- **parent-first 委托**（`URLClassLoader` 默认行为）：插件类先查框架 classpath，再查自身 jar → 框架类（`Context`/`Plugin` 接口等）与三方库**单实例**，版本恒等于宿主；
- **统一版本**：三方库只出现在框架 classpath；插件 jar 打包时**排除三方依赖**（`maven-jar-plugin` 默认行为，禁用 shade），插件 pom 中对三方库声明 `provided` 并由宿主提供。

## 5. 核心设计

### 5.1 PluginClassLoader

```java
/** 每插件 jar 一个实例；parent-first；close() 释放 jar 句柄。 */
public final class PluginClassLoader extends URLClassLoader {
    public PluginClassLoader(Path jar, ClassLoader parent) {
        super(new URL[] { toUrl(jar) }, parent);
    }
}
```

- 默认 parent-first 委托，无需覆写 `loadClass`；
- 仅加载 `io.jcordis.plugins.*`（或 jar 内全部）——由 SPI 清单收敛入口，不做包名白名单（jar 原子性保证）。

### 5.2 插件 SPI 发现

插件 jar 内置：

```
META-INF/services/io.jcordis.core.registry.Plugin
```

内容为实现了 `Plugin` 接口的类全名（每行一个）。宿主用：

```java
ServiceLoader.load(Plugin.class, pluginClassLoader)
```

实例化插件。对应 cordis 的模块导出约定（`default export` + `unwrapExports`）。

### 5.3 Loader 扩展（loadJar / unload）

```java
// Loader 新增
public String loadJar(Path jar, String name);   // 建 PluginClassLoader + SPI 实例 → modules.put(name, plugin)
public void unload(String name);                 // dispose 相关 entry fiber → modules.remove → classLoader.close()
```

- `loadJar`：SPI 首个实现即插件的 `Plugin` 实例（支持多实现时按服务顺序）；
- `unload` 时序（见 5.4）严格：**先停 fiber，再摘注册，最后关 ClassLoader**；
- `importPlugin(name)` 逻辑不变：`builtins` → `modules` → （未来）jar 注册表，兼容既有 `mock`/`builtin`。

### 5.4 卸载语义（类回收前提与时序）

类卸载条件（全部满足时 GC 才回收）：

1. `PluginClassLoader` 无强引用；
2. 其加载的 `Class` 无强引用（插件实例、运行时 `store` 条目、事件钩子等全部释放）；
3. 无正在执行的线程栈引用。

jcordis 卸载时序（复用现有机制 + 新增收尾）：

```
entry fiber disposeAsync()   // 现有：effect 逆序清理、事件钩子摘除、registry.delete
      ↓
modules.remove(name)          // 现有：注册表移除
      ↓
PluginClassLoader.close()     // 新增：释放 jar 文件句柄（Java 7+）
      ↓
引用置 null → GC 可达性回收   // 类与 ClassLoader 一并卸载
```

无法强制 GC（`System.gc()` 仅为提示），但引用置空后可达性分析必然回收——文档约定不依赖立即回收。

### 5.5 三方库提供约定（宿主提供，插件干净）

**核心模型**：插件 jar = 纯插件代码（+SPI 清单）；全部三方库由宿主业务系统提供。对应 npm 生态的 peerDependencies 模式（插件声明依赖，宿主 lock 版本）。

**版本模型：传递依赖 + 业务 BOM（无 jcordis-bom）**

```
jcordis 模块 pom（自身依赖带版本）
   ↑ 依赖（compile）→ 传递依赖自动带入
宿主 classpath（jcordis + 框架三方库 + 业务三方库）   ← 业务系统依赖 jcordis 即获得框架面
   ↑ import
业务版本管理 pom（业务 BOM：业务三方库版本，业务自己定）
   ↑ import
插件项目 pom（jcordis + 三方库 = provided）→ 干净 jar
   ↓ parent-first
宿主 classpath（运行时提供一切）
```

- **框架三方库**：不需要 BOM——业务系统依赖 jcordis 模块时，jcordis 模块 pom 声明的依赖（jackson/slf4j 等）经 **Maven 传递依赖**自动进入业务 classpath；
- **业务三方库**：业务 BOM（业务系统既有版本管理 pom）管理；业务可显式覆盖 jcordis 传递的版本（`dependencyManagement` 就近原则：显式声明 > 传递版本）；
- **版本同源原则**：插件编译期（业务 BOM 解析）== 插件运行期（宿主 classpath = 业务 BOM 解析）→ 无版本漂移；
- **无业务 BOM 的独立插件**：直接写 jcordis 固定版本（provided）+ 三方库 provided 并在插件文档标注"由宿主提供"。

**① provided 约定（插件 pom 规范）**

插件 pom 必须满足：

```xml
<dependencyManagement>
  <!-- import 业务 BOM（可选）：三方库版本对齐宿主 -->
  <dependencies>
    <dependency>
      <groupId>com.example.business</groupId>
      <artifactId>business-bom</artifactId>
      <version>${business.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<!-- jcordis 模块与三方库一律 provided：编译可见、运行时宿主提供、不打入 jar -->
<dependency><groupId>io.jcordis</groupId><artifactId>jcordis-core</artifactId><version>0.1.0</version><scope>provided</scope></dependency>
<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><scope>provided</scope></dependency>
```

- 打包：`maven-jar-plugin`（默认行为，**不含依赖**）→ 产出**干净 jar**（仅插件类 + 资源 + SPI 清单）；
- SPI 清单：`src/main/resources/META-INF/services/io.jcordis.core.registry.Plugin`。

**② 脚手架模板固化（约定内嵌，零心智负担）**

`Scaffolder` 新增 `createPlugin`（`jcordis-maven-plugin` 提供 `create-plugin` goal）：生成的插件项目 pom 自动满足 ①（jcordis provided + 固定版本，import 业务 BOM 留作可配置项）——插件开发者无需手写任何约定。

**③ 运行时 parent-first 兜底（类加载层强制）**

`PluginClassLoader` parent-first 委托：即使插件 jar 违规携带了三方库，宿主版本**优先**——版本统一在类加载层面被强制。

**④ 构建期校验（可选增强）**

`jcordis-maven-plugin` 提供 `check` goal（绑定插件项目 `verify` 阶段）：
- 校验插件 jar 不包含三方库类（`com.fasterxml.*`、`org.slf4j.*` 等包前缀黑名单）；
- 校验插件依赖版本与宿主 BOM 一致（无业务 BOM 时校验 jcordis provided 版本）。

### 5.6 插件开发契约（给插件开发者的总览）

```
插件开发者：
  1. jcordis create-plugin demo-plugin     # 脚手架已内嵌全部约定（jcordis provided + 固定版本，业务 BOM 可配置）
  2. 实现 io.jcordis.core.registry.Plugin，类名写入 SPI 清单
  3. 使用三方库：import 业务 BOM（无业务 BOM 时显式 provided 版本并标注宿主提供），scope=provided
  4. mvn package → 干净 jar（仅插件代码 + 资源 + SPI 清单）

宿主业务系统：
  1. 依赖 jcordis 模块（传递依赖自动带来框架三方库）；业务三方库版本由业务 BOM 管理
  2. 部署：jcordis 框架 + 业务三方库在宿主 classpath；插件 jar 放插件目录
  3. jcordis 运行时：每插件独立 PluginClassLoader（parent = 宿主 classpath）
     ├── 加载：SPI 发现 → modules 注册
     ├── 热替换：jar 变更 → 新 ClassLoader → swap → 旧 ClassLoader close
     └── 卸载：fiber dispose → 摘注册 → close → 引用置空 → GC
```

## 6. HMR 集成（WatchService + 原子替换 + 回滚）

在现有 `Hmr`（配置轮询重载）之外新增**插件目录监听**：

```
WatchService 监听插件目录（*.jar）
   → SHA-256 指纹变更检测（mtime+size 不可靠）
   → 命中已加载插件（name ↔ jar 映射）
   → 原子替换流程：
     1. 新 PluginClassLoader 加载新 jar，SPI 实例化新 Plugin
     2. 失败 → 记录日志，保留旧 ClassLoader（回滚，对应 cordis rollback）
     3. 成功 → modules 注册表 swap（旧 Plugin 实例摘除）
     4. 相关 entry 树 diff 重载：dispose 旧 fiber → 以新 Plugin 实例重建
     5. 旧 PluginClassLoader 延迟 close（待旧 fiber 完全 dispose）
```

- 与配置变更并行：配置重载（现有）+ jar 热替换（新增）互不干扰；
- `hmr/reload` 事件复用，事件载荷区分触发源。

## 7. 与 cordis HMR 映射

| cordis（Node） | jcordis（Java） |
|---|---|
| chokidar watch 源码目录 | WatchService 监听插件目录（jar） |
| 清 ESM/CJS loadCache | 丢弃旧 PluginClassLoader |
| 重新 import 模块 | 新 PluginClassLoader + SPI 实例化 |
| 依赖图 accepted/declined 分类 | 不需要——jar 为原子变更单元 |
| registry.delete + 重建 fiber | unload + entry 树 diff 重载 |
| 失败回滚（缓存恢复） | 新加载失败则保留旧 ClassLoader |

**粒度差异**：cordis 热替换源码文件（改代码即生效）；Java 热替换 jar（改代码需先 `mvn package`）——机制同构，粒度不同，为 Java 生态（OSGi/JPMS/SPI 插件系统）通用做法。

## 8. 边界与限制

1. **静态状态丢失**：插件类 `static` 字段在替换后重置（新 ClassLoader 新类）——插件应避免依赖跨替换的静态状态；
2. **跨插件类引用**：插件 A 引用插件 B 的类时，A 的 ClassLoader parent 需包含 B（否则 `ClassNotFoundException`）——v1 限定为不跨插件引用，插件间协作走框架服务（`Context` 服务注入）；
3. **jar 原子性**：替换期间 jar 文件被占用（Windows 文件锁）——先复制到临时名再原子替换；
4. **不可热替换框架自身**：框架/三方库变更仍需重启（对应 cordis externals → `loader.exit()`）。

## 9. 测试策略

- **测试插件 jar 构造**：测试运行时用 `JarOutputStream` 把 `target/test-classes` 中已编译的测试插件类打包 + SPI 清单（两个版本：`v1` 与 `v2`，行为不同）；
- **用例**：
  1. `loadJar` 加载 → 插件生效（fiber 运行、服务可见）；
  2. 双插件隔离：各自 ClassLoader 独立、实例互不可见；
  3. `unload` → fiber dispose + 注册表移除 + ClassLoader close（jar 句柄释放可断言）；
  4. HMR 替换：v1 jar → 覆写为 v2 → WatchService 检测 → 自动重载 → 行为切换断言；
  5. 替换失败回滚：写入损坏 jar → 旧插件继续运行；
  6. 版本统一：插件引用的三方库解析自宿主（parent-first）。

## 10. 实施步骤

| 步骤 | 内容 | 产出 |
|---|---|---|
| 1 | `PluginClassLoader` + `Loader.loadJar/unload` + SPI 发现 | 运行时 jar 加载/卸载 |
| 2 | `Hmr` 插件目录监听 + SHA-256 指纹 + 原子替换/回滚 | jar 级热替换 |
| 3 | 端到端测试基建（测试 jar 打包 + 6 类用例） | 验证闭环 |
| 4 | `Scaffolder.createPlugin` / `create-plugin` goal（provided 约定内嵌）+ `check` goal（jar 清洁度/版本校验） | 插件开发契约落地 |

> 步骤 4 即「插件开发约定」的工程化：依赖模型为「传递依赖 + 业务 BOM」（无 jcordis-bom），provided 规范由脚手架固化、构建期强制校验。

## 11. 风险与对策

| 风险 | 对策 |
|---|---|
| Windows jar 文件锁导致替换失败 | 临时副本 + `ATOMIC_MOVE`（现有 `Include` 写文件同法） |
| 插件泄漏 ClassLoader（内存泄漏） | `close()` + 引用置空 + 测试断言类可回收（`-XX:+ClassUnloading` 默认开启） |
| SPI 加载异常破坏替换流程 | 严格"先加载成功再 swap"，失败回滚旧加载器 |
| 版本漂移（插件带旧版三方库） | parent-first + provided + 构建期版本校验 |
