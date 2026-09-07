# Agent 作用域对接指南（M-C4）

> 本文档回答外部项目（agent 系统，代号 majo）在 jcordis 上实现
> "每个 agent 一个可独立拆除的作用域"的架构结论，含能力确认、
> 推荐用法与反模式。验证测试：`AgentScopeTest`（jcordis-core，4 例）。

## 背景：M-C4 的需求

majo 需要为每个 agent scope 提供：

1. 每子代**独立 fiber** 的生命周期（可单独 `disposeAsync`）；
2. scope 内注册的服务在 fiber 拆除时**自动回滚**（防 agentLoop/turnSummary
   重复注册泄漏）；
3. 子上下文内按名服务的**影子注册**语义（隔离域）。

初判这三项为 jcordis 缺口（可能需要 `ctx.fork()` 之类的新 API）。
**Spike 验证结论：三者均已内建，无需新 API、无需结构性调整。**

## 能力映射

| 声称缺口 | jcordis 实际能力 | 验证证据 |
|---|---|---|
| 无公开"子 fiber 工厂" | **`ctx.plugin(...)` 就是**：每次调用在子上下文上创建一个独立 fiber（独立 uid / 状态机 / `disposeAsync`）；Java `Plugin` 是函数式接口，`ctx.plugin((c, cfg) -> ...)` 直接可用 | `AgentScopeTest.agentFiber_shouldBeIndependentlyDisposable`：agentA 拆除不影响 agentB |
| scoped service 回滚未确认 | **已内建**：`ctx.provide` 注册为 fiber effect，fiber 拆除时逆序自动 unregister | `AgentScopeTest.agentLoop_shouldNotLeakServicesWhenEachTurnGetsItsOwnScope`：4 轮作用域销毁后服务零残留、registry 空 |
| 影子注册需补 | **`ctx.isolate(name, key)`**：同名下每个隔离域一份实例，互不串扰；销毁只清本域 | `AgentScopeTest.agentShadowServices_shouldIsolateByRealm` |
| 同作用域重复注册语义 | 同一作用域（同一 fiber ctx）内同名 `provide` 第二次**抛错**——先拆除或回滚旧注册 | `AgentScopeTest.repeatedProvideInSameScope_shouldRequireDisposeFirst` |

## 推荐用法：每 agent = 一个 `ctx.plugin()` fiber

```java
Context root = Context.create();          // 应用共享上下文

// 每个 agent（或每轮 agent loop）一个独立作用域：
Fiber agent = root.plugin((ctx, config) -> {
    ctx.provide("agent-db", new AgentDb(ctx));   // ① 作用域服务
    ctx.on("agent/step", (thisArg, args) -> {    // ② 作用域事件钩子
        return step((String) args[0]);
    });
    return (Disposable) () -> {                  // ③ 作用域清理
        System.out.println("agent torn down");
    };
});

// 用后即焚：单独拆除该 agent，服务/钩子/清理自动逆序回滚
agent.disposeAsync().join();

// 需要与其它 agent 同名服务隔离时：影子注册
Fiber isolated = root.isolate("agent-db", ServiceKey.unique("realm-A"))
        .plugin((ctx, cfg) -> { ctx.provide("agent-db", ...); return null; });
```

关键点：

- **谁持有 ctx 谁就是作用域**：`ctx.plugin()` 返回的 fiber 的 ctx（`fiber.ctx()`）
  是独立的子上下文，其上注册的一切（服务/事件/效应）都归属该 fiber 的生命周期；
- **拆除 = 逆序回滚**：`disposeAsync()` 按注册逆序执行全部 disposables——
  服务注销、钩子移除、定时器取消一次完成，天然防泄漏；
- **每轮新建、用后即焚**：agent loop 的正确形态是"每轮一个新 `ctx.plugin()`
  fiber、轮末 dispose"——而不是在共享 ctx 上反复注册。

## 反模式（泄漏根因）

```java
// ❌ 反模式：服务注册到共享 ctx（根 fiber），生命周期与 agent 无关
Context root = Context.create();
root.provide("agent-db", dbA);            // 挂在 root fiber 上
root.provide("agent-db", dbB);            // 同 key 冲突 / 或覆盖泄漏
// agent 结束时无处回滚 —— 服务残留，下一轮重复注册抛错或覆盖

// ✅ 正确：服务注册到 agent 自己的 plugin fiber ctx
Fiber agent = root.plugin((ctx, cfg) -> {
    ctx.provide("agent-db", dbA);         // 挂在 agent fiber 上
    return null;
});
// agent 结束时 disposeAsync() 自动注销 —— 零残留
```

反模式特征：把 `provide`/`on`/`effect` 挂在 `root` 或 `root.extend()` 出的 ctx
（其 `fiber()` 仍是根 fiber）上——它们的生命周期是"应用级"而非"agent 级"。
这是 agentLoop/turnSummary 重复注册泄漏的典型根因。

## 常见问题

| 问题 | 答案 |
|---|---|
| 需要 `ctx.fork()` 吗？ | 不需要。`ctx.plugin((c, cfg) -> body)` 即 fork；返回的 `Fiber` 有 `uid()/state()/disposeAsync()/await()` |
| 多个 agent 同名服务会冲突吗？ | 默认会（无隔离时同 `ServiceKey`）。需要隔离时用 `ctx.isolate(name, key)`（影子注册）或给服务唯一命名 |
| agent 内嵌 agent 呢？ | 在 agent 的 plugin body 里再 `ctx.plugin(...)` 即可——子 agent 挂在父 agent fiber 上，父拆除时级联逆序清理 |
| 异步 agent 体？ | plugin body 可返回 `CompletableFuture`；完成前 `disposeAsync()` 则结果立即处置不泄漏（async return 2 语义） |

## 结论

jcordis（继承 cordis 的"ctx=作用域、plugin=fiber、effect=回滚"模型）已原生覆盖
M-C4 全部需求。majo 侧的工作是**迁移用法**（每 agent 一个 `ctx.plugin()` fiber），
而非等待 jcordis 提供新能力。若后续觉得"每 agent 写 Plugin 包装"不顺手，
可提出糖方法 `ctx.scope(Runnable)`（≈1 方法 + 测试，随 1.1.0-SNAPSHOT 发布）。
