# jcordis

<p align="left">
  English | <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="left">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-orange">
  <img alt="JUnit" src="https://img.shields.io/badge/JUnit-5-green">
  <img alt="License" src="https://img.shields.io/badge/License-Apache--2.0-blue">
</p>

**jcordis** is a Java 21 implementation of **Cordis** — a meta-framework of spatiotemporal composability.

Reference project: [cordiverse/cordis](https://github.com/cordiverse/cordis) (TypeScript)

## Architecture Overview

```
Scaffolding tools ──generate──> Consumers (business apps / examples / plugin jars)
                                     │ single Maven coordinate
                                     ▼
                           jcordis-all (shaded aggregate jar)
                                     │ shade merge
                                     ▼
          jcordis runtime framework = jcordis-core + jcordis-loader
                                     │
                                     ▼
              Third-party dependencies (SLF4J / Jackson, provided transitively)
```

![jcordis architecture](docs/architecture.png)

> Editable source: [docs/architecture.drawio](docs/architecture.drawio) (open in draw.io to keep editing)

## Core Features

- **Spatiotemporal composability**: `ctx.effect()` registers reversible side effects (temporal composition), cleaned up in reverse order when a plugin is destroyed; `ctx.isolate()` / `ctx.intercept()` build service isolation domains (spatial composition)
- **Event system**: five dispatch modes — emit / bail / serial / parallel / waterfall — with thisArg filtering
- **Plugin lifecycle**: Fiber state machine driven by dependency epochs (auto-loads when injections are ready, auto-unloads when missing), `restart()` / hot config updates
- **Declarative loader**: Entry tree + config diff synchronization (YAML/JSON parsed via `ConfigParser` strategies) + isolation realms (`Realm`: `#id` local / `@label` shared)
- **Plugin hot reload (HMR)**: runtime plugin loading from jars (SPI discovery + dedicated `PluginClassLoader`), jar hot-swap on change (atomic replacement + rollback on failure), complete class unloading
- **Scaffolding**: dual entry points (Maven plugin / CLI) to generate application and plugin projects

## Module Structure

| Module | Description | Depends on |
|---|---|---|
| `jcordis-core` | Core framework: Context / Fiber state machine / EventBus / Registry / Logger (with ConsoleExporter) / Reflect (service isolation) + `EffectList` (effect-tracking collection) + `TimerService` (timeout/interval/throttle/debounce) | — |
| `jcordis-loader` | Entry tree / config reconciliation / isolation realms / plugin jar loading (`PluginClassLoader`, `loadJar`/`replaceJar`/`unload`) + `include` subpackage (config-file plugin `Include` / `ConfigParser` / HMR `Hmr` / `JarWatcher` hot-swap) + grouping markers | core |
| `jcordis-cli` | `Scaffolder` engine + CLI entry point | core |
| `jcordis-maven-plugin` | Maven plugin: `create` / `create-plugin` / `check` | — |
| `jcordis-all` | **Aggregate jar**: shades all runtime modules (except maven-plugin) into a single jar — one coordinate for business systems | core + loader + cli |
| `examples/*` | hello-world / service-graph / config-app examples + demo-plugin (standalone plugin example, not part of the reactor build) | — |

## Requirements

- JDK 21+
- Maven 3.x

## Quick Start

**Create an application scaffold** (identical to the CLI output):

```bash
mvn io.jcordis:jcordis-maven-plugin:0.1.0-SNAPSHOT:create -Dname=my-app
```

**Create a plugin scaffold** (embedded plugin contract: jcordis dependency as provided + SPI manifest + check goal):

```bash
mvn io.jcordis:jcordis-maven-plugin:0.1.0-SNAPSHOT:create-plugin -Dname=demo-plugin
```

## Business System Integration

One coordinate brings in the whole runtime framework (core + loader, including the timer / console-exporter / include / group / utils functionality merged into them):

```xml
<dependency>
  <groupId>io.jcordis</groupId>
  <artifactId>jcordis-all</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Third-party libraries (jackson/slf4j) are not shaded into the aggregate jar; they are provided transitively via the reduced pom.

## Plugin Development

Plugin projects produce a **clean jar**: only plugin classes + a `META-INF/services/io.jcordis.core.registry.Plugin` manifest.

- `mvn verify` automatically runs the `check` goal, verifying that no third-party / framework classes are mixed into the plugin jar
- At runtime the plugin is loaded in isolation by `PluginClassLoader`, with jar hot-swap and complete class unloading (see the [HMR design doc](docs/hmr-design.md), in Chinese)

## Documentation

| Document | Description |
|---|---|
| [Cordis source analysis](docs/cordis-analysis.md) | Full architecture analysis of the reference project (Chinese) |
| [Java 21 porting roadmap](docs/roadmap.md) | Phased porting plan (Chinese) |
| [Progress log](docs/progress.md) | Implementation milestones and verification results (Chinese) |
| [Design patterns](docs/patterns.md) | 23 GoF patterns applied (Chinese) |
| [HMR design](docs/hmr-design.md) | ClassLoader hot-swap, unload semantics, dependency model (Chinese) |
| [Compatibility matrix](docs/compatibility.md) | cordis API mapping (Chinese) |

## Build & Test

```bash
mvn clean verify   # 9/9 modules, 172 tests green
```
