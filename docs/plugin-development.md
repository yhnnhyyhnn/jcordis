# Plugin Development (English)

This document describes the **plugin contract** of jcordis — how to build,
package, and hot-deploy plugins. It complements the Chinese design documents
([HMR design](hmr-design.md), [compatibility matrix](compatibility.md)).

## 1. The Plugin contract

A plugin is any class implementing `io.jcordis.core.registry.Plugin`:

```java
public interface Plugin {
    /** Applies the plugin body; returns a Disposable (or a CompletableFuture of one) to revert it. */
    Object apply(Context ctx, Object config);

    /** Plugin name (used for the fiber/context label). */
    default String name() { return null; }

    /** Declared service dependencies (name → config, or null). */
    default Map<String, Object> inject() { return Map.of(); }
}
```

Three authoring styles are supported:

| Style | How | When |
|---|---|---|
| Lambda | `loader.builtin("name", (ctx, config) -> ...)` / `loader.mock(...)` | built-ins, tests, in-process plugins |
| Class | `Plugin.constructor(MyPlugin.class)` (constructor-injected `Context`/`config`, optional `Initializable.init()`) | source-compiled plugins |
| Object | `Plugin.object(name, inject, apply)` | composing a plugin from parts |

Dependency declarations (`inject`) drive the fiber state machine: the body
only executes when every injected service is available, and re-executes or
unloads when the dependency set changes (see [compatibility.md](compatibility.md)
§1 "依赖响应").

## 2. Packaging a plugin jar

Plugin projects build a **clean jar** containing:

```
my-plugin.jar
├── com/example/MyPlugin.class          # the Plugin implementation (+ helper classes)
└── META-INF/services/
    └── io.jcordis.core.registry.Plugin # SPI manifest: one FQCN per line
```

The `check` goal (bound to `verify` by the generated plugin scaffold) rejects
any third-party/framework class leaking into the jar (package-prefix blacklist:
`com/fasterxml/`, `org/slf4j/`, `org/junit/`, `org/assertj/`,
`org/apache/maven/`, `io/jcordis/`). jcordis and third-party libraries must be
declared `provided` — the host provides them at runtime.

## 3. Runtime loading & isolation

At runtime the jar is loaded by a dedicated `PluginClassLoader`
(a `URLClassLoader` subclass):

- **parent-first delegation** — framework classes and third-party libraries
  resolve to the host classpath (single instance, version pinned to the host);
- **plugin classes only** exist in the child loader, so they never collide
  with host classes or other plugins;
- **`close()`** releases the jar handle and makes the plugin classes
  garbage-collectable (verified by `PluginIsolationTest`).

Entry points on the loader service:

| Method | Behavior |
|---|---|
| `loader.loadJar(jar, name)` | load via SPI discovery, register under `name` |
| `loader.replaceJar(jar, name)` | atomic hot-swap: validate the new loader first, then swap the registry, reload matching entries, close the old loader; rollback on failure |
| `loader.unload(name)` | dispose entry fibers → unregister → close the class loader |
| `loader.mock(name, plugin)` / `builtin(name, plugin)` | in-process registration (tests / built-ins) |

## 4. Hot reload

- **jar hot-swap**: drop a jar into the watched `plugins/` directory, or replace
  it — `JarWatcher` detects the change (SHA-256 fingerprint), loads/swaps the
  plugin and reloads the entries using it; a corrupted replacement rolls back
  to the previous plugin.
- **config hot reload**: edit the loader config file — `Hmr` diff-updates the
  tree (new/removed/disabled entries, changed configs), keeping the previous
  tree on parse failure.

See the runnable `examples/hmr-app` demo (`mvn -pl examples/hmr-app exec:java`).

## 5. Dependency model

```
plugin project pom (jcordis + libs = provided)  ──> clean jar
        │ SPI discovery
        ▼
PluginClassLoader (parent-first)  ──>  host classpath
                                        │ provides everything
                                        ▼
business system depends on io.jcordis:jcordis-all (one coordinate)
+ transitive third-party libs (jackson / slf4j, NOT shaded)
```

A plugin never bundles a framework or library — the host is the single source
of truth for their versions (business BOM can pin them).
