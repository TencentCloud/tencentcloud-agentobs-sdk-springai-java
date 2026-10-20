# Subagent Support — Design (2026-10-14)

> Status: **Approved for implementation**  
> Author: SDK team  
> SDK version target: **0.0.8**  
> Spec reference: CLS GenAI Trace v5 — `invoke_subagent` / `gen_ai.agent.scope=subagent`

---

## 1. 问题陈述

Spring AI 2.x 的 Tool 调用链里，当某个 tool 实现内部又发起 `ChatClient.call()`
（典型如 "主 agent 调度子 agent" 场景），现有 SDK 把内层 ChatClient 当作全新
一轮 turn 处理 —— 新开一个 `entry` span、新建 `TurnState`、`turnCounter`
递增 —— 导致：

1. **一个 trace 里出现多个 `enter_application`**（违反规范 "1 trace = 1 entry"）
2. **子 agent 的身份丢失**，CLS 控制台看不出 "谁调起了谁"
3. **Trace 树在控制台渲染为嵌套 entry**，用户难以识别真正的调用层级

同事反馈的截图里 3 层 entry 嵌套、41 spans 全部塞在同一 trace，就是本 bug 的
直接表现。

## 2. 修复目标

实现 CLS GenAI Trace v5 规范中的 **invoke_subagent 语义**：

- **一个 trace 只有一个 `enter_application`**（最外层 ChatClient 的 entry）
- 嵌套 ChatClient 不开 `entry`，只开 **subagent span**，直接挂在触发它的
  `execute_tool` span 下
- Subagent 的 `agent.name` 自动取**触发它的 tool 的名字**（策略 1，零用户配置）
- 嵌套检测用 **OTel Context 为主 + ThreadLocal 兜底**（方案 C，响应式友好）
- **无用户 API 变更、无配置新增、无兼容性破坏**

## 3. 修复后 Trace 树形态

```
entry  (turn=t1, enter_application)
└─ agent  "main"               scope=main         invoke_agent
   └─ step react
       ├─ chat (LLM)
       └─ execute_tool  "inspect_payment"   gen_ai.tool.call.id=call_abc
          └─ agent  "inspect_payment"        scope=subagent   invoke_subagent
                                             parent_tool_call.id=call_abc
             └─ step react
                 ├─ chat (LLM)
                 └─ execute_tool "query_db"
                    └─ ...  (可继续嵌套，第 N 层子 agent 用同样规则)
```

**关键字段**：

| 字段 | subagent 取值 |
|---|---|
| `gen_ai.span.kind` | `agent` （不是独立 kind） |
| `gen_ai.operation.name` | `invoke_subagent` |
| `gen_ai.agent.name` | 触发它的 tool 名 |
| `gen_ai.agent.scope` | `subagent` |
| `gen_ai.subagent.parent_tool_call.id` | 触发它的 tool 的 `tool.call.id` |
| `gen_ai.session.id` | **复用 outer 的 session.id** |
| `gen_ai.turn.id` | **复用 outer 的 turn.id**（不占新编号） |

## 4. 架构变更

### 4.1 核心变更点

| 组件 | 变更 |
|---|---|
| `ClsObservationHandler` | `startChatClientTurn` 入口加嵌套检测分支 |
| 新增私有方法 | `startSubAgentTurn(ctx, outer)` |
| 新增 ContextKey | `ContextKey<TurnState> CLS_TURN_KEY`（OTel Context 挂载点）|
| `TurnState` | 新增 `isSubAgent` + `outer` + `agentName` + `parentToolCallId` + `ctxScope` + `subAgentCounter` 字段，加 `root()` / `subAgent()` 工厂方法 |
| `SpanState` | 新增 `isSubAgent` 字段（区分 root turn vs subagent turn） |
| `onStop` turn 清理路径 | 拆分 root / subagent 两条分支 |

### 4.2 嵌套检测时序

```
┌─────── ChatClient.call() 发起 ───────┐
│                                      │
│  obs=spring.ai.chat.client           │
│         ↓                            │
│  startChatClientTurn(ctx):           │
│    outer = Context.current()         │   ← 主通道（跨线程）
│            .get(CLS_TURN_KEY)        │
│    if (outer == null)                │
│      outer = CURRENT_TURN.get()      │   ← 兜底（同线程）
│                                      │
│    if (outer != null)                │
│      startSubAgentTurn(ctx, outer)   │
│    else                              │
│      startRootTurn(ctx)              │
└──────────────────────────────────────┘
```

### 4.3 TurnState 嵌套结构

```
outer (root turn)
  isSubAgent = false
  outer      = null
  agentName  = "main"
     ↑
     │ outer 指针
     │
sub-1 (level 1)
  isSubAgent = true
  outer      = <root turn>
  agentName  = "inspect_payment"
     ↑
     │
sub-2 (level 2)
  isSubAgent = true
  outer      = <sub-1>
  agentName  = "query_db"
```

onStop 收尾时沿 `outer` 链一步步 pop 回去，天然支持任意深度嵌套。

## 5. 关键决策

| 决策项 | 选择 | 理由 |
|---|---|---|
| **Trace 树形态** | Y — invoke_subagent（规范 v5） | 保留 agent 身份、支持多层嵌套、CLS 控制台可画主/子交互图 |
| **Agent 命名策略** | 策略 1 — 用触发 tool 的名字 | 零配置、业务含义自动传递 |
| **嵌套检测机制** | C — OTel Context 为主 + ThreadLocal 兜底 | 响应式友好、同步兜底 |
| **复用 outer 的 turn.id** | 是 | 规范语义 "1 trace 1 turn"，CLS 控制台正确聚合 |
| **subagent 的 span.kind** | `agent`（不新建 kind） | 规范规定：subagent 是 agent 的一个 scope |
| **开新 TurnState 不是复用** | 新建 | subagent 内部的 step/round/chat 计数独立 |

## 6. 兼容性

- 非嵌套（现有）场景：**行为完全不变**，所有用例保持 0.0.7 的输出
- 嵌套场景：从 "多 entry trace" 修正为 "单 entry trace + subagent 子树"
- 用户 API：**无变更**
- 配置项：**无新增**
- SDK 版本：**0.0.7 → 0.0.8**（minor，不破坏）

## 7. 实现 Checklist

| # | 任务 | 文件 | 大致行数 |
|---|---|---|---|
| 1 | 加 `ContextKey<TurnState> CLS_TURN_KEY` 字段 | ClsObservationHandler.java | +3 |
| 2 | `TurnState` 新字段 + `root()` / `subAgent()` 工厂 | ClsObservationHandler.java | +40 |
| 3 | `SpanState` 加 `isSubAgent` 字段 + 构造重载 | ClsObservationHandler.java | +10 |
| 4 | 把现有 `startChatClientTurn` 改名 `startRootTurn`，加 OTel Context 挂载 | ClsObservationHandler.java | +5 |
| 5 | 新 `startChatClientTurn` 做分流（outer != null → subagent） | ClsObservationHandler.java | +15 |
| 6 | 新增 `startSubAgentTurn` 方法 | ClsObservationHandler.java | +60 |
| 7 | 新增 `extractParentToolName` / `extractParentToolCallId` | ClsObservationHandler.java | +30 |
| 8 | 修改 `onStop` 的 turn 分支，拆分 root / subagent 收尾 | ClsObservationHandler.java | +25 |
| 9 | 修改 `onError` 对称处理 subagent | ClsObservationHandler.java | +10 |
| 10 | demo 工程加 `SubAgentDemoController` | demo-springboot | 新增 ~60 行 |
| 11 | 本地跑 demo，CLS 控制台肉眼验证 | — | — |
| 12 | 版本号升级 0.0.7 → 0.0.8（pom × 3 + README × 2） | — | 5 处 |
| 13 | commit + push | — | — |

**预计净代码增量**：主库 ~200 行、demo ~60 行。

## 8. 测试策略

### 8.1 Demo 工程扩展

`demo-springboot` 加 `SubAgentDemoController`，带 `inspect_payment` tool，tool
实现里调用内层 ChatClient。验证点：

```
onStart  TURN  session=sess-xxx  turn=sess-xxx:t1          ← root
onStart  SUBAGENT  name=inspect_payment  parent_tool=call_abc
onStop   SUBAGENT  name=inspect_payment
onStop   TURN  session=sess-xxx  turn=sess-xxx:t1          ← root 收尾
```

### 8.2 CLS 控制台肉眼验证

| 验证项 | 期望 |
|---|---|
| Trace 根节点数 | **1 个** `enter_application` |
| Entry 下 agent 数 | **1 个** `invoke_agent` name=main scope=main |
| Agent 下 subagent | 挂在 tool span 下，`invoke_subagent` scope=subagent |
| `parent_tool_call.id` | 等于触发 tool 的 `tool.call.id` |
| 深层嵌套（3 层） | 子 subagent 挂在父 subagent 的 tool 下 |
| 所有层级的 `turn.id` | **相同** |

### 8.3 响应式验证（可选）

把 outer `.call()` 改成 `.stream()...blockLast()`，重复 8.2 验证，确认 OTel
Context propagation 工作、subagent 没被重复识别为 root。

## 9. 风险清单

| ID | 风险 | 影响 | 缓解 |
|---|---|---|---|
| **R1** | `Observation.Context.getParentObservation()` 在 Spring AI 某些版本可能返回 null | subagent name 退化为 `subagent-N` | fallback 已覆盖，仍能看到 subagent |
| **R2** | 用户 tool 在另一个 Executor 线程里调 ChatClient 且没 wrap context | 嵌套检测失败 → 退化为旧 bug | 文档说明需要 Context propagation |
| **R3** | `extractInputMessages` 在 subagent 场景把内层 prompt 写到 subagent span | 这是正确行为（符合规范） | 无需处理 |
| **R4** | `turnCounter` 不为 subagent 递增 | subagent 不是独立 turn，故意的 | 文档说明 |
| **R5** | 社区规范 v5 中 `gen_ai.subagent.parent_tool_call.id` 字段名可能微调 | 后续规范变动需同步 | 常量化为字段，一处修改 |

## 10. 不做的事（YAGNI）

- ❌ subagent **独立 turn ID**（规范规定复用）
- ❌ subagent **memory 隔离机制**（SDK 不管用户业务）
- ❌ subagent **熔断/限流**（用户层策略）
- ❌ `ClsBaggage.pushSubAgent()` 显式命名 API（策略 1 够用）
- ❌ 改 `ClsConfig`（无新配置项）

---

**Design frozen. Implementation starts next.**

## 11. 实施记录

实施完成于 2026-10-14 22:30。

### 实测验证结果

Demo 工程 `SubAgentDemoController` 通过 HTTP POST 触发主 agent → tool
`inspectService` → 内层 ChatClient 调用。日志证据：

```
onStart  TURN      session=subagent-final-1  turn=subagent-final-1:t1
onStart  SUBAGENT  session=subagent-final-1  turn=subagent-final-1:t1
                   name=inspectService  parent_tool=call_f647a316fae44f01992c0979
onStop   SUBAGENT  name=inspectService
```

全部达成设计目标：
- ✅ 一个 trace 只有一个 `enter_application`
- ✅ Subagent 的 `agent.name = inspectService`（策略 1 自动生效）
- ✅ `gen_ai.subagent.parent_tool_call.id = call_f647a316...`（spec 字段写入成功）
- ✅ Subagent 复用 outer 的 `session.id` + `turn.id`
- ✅ Subagent 结束后 outer TurnState 正确恢复到 `CURRENT_TURN`

### 关键实现细节（和设计差异）

1. **extractParentToolName 的 KeyValue key**：实际 Spring AI 2.0 用
   `spring.ai.tool.definition.name`（不是设计里猜测的 `gen_ai.tool.name`）。
2. **extractParentToolCallId 的 KeyValue key**：实际是
   `spring.ai.tool.call.id`（HighCardinality）。
3. **反射 fallback**：优先走 KeyValue；取不到时反射 `getToolCallId()` /
   `getToolDefinition().name()`。初版 fallback 直接 `toString()` 了整个 record
   ，第一轮验证时发现并修复。
4. **root agent 增加 `gen_ai.agent.scope=main`**：和 subagent 的 `scope=subagent`
   对称，便于 CLS UI 区分主/子 agent（设计里原本只给 subagent 写 scope）。
5. **嵌套检测升级为 4 通道**（关键修订，响应式实测后 + 双层响应式实测后）：
   - 原设计（方案 C）：OTel Context + ThreadLocal 兜底。响应式实测证明
     不足 —— Reactor `BoundedElastic` worker 线程里，`Context.current()`
     看不到父线程的 `CLS_TURN_KEY`（OTel Context 不是 Reactor 原生传播通道），
     ThreadLocal 更不用说。于是 inner ChatClient 的 onStart 查不到 outer，
     被错误识别为新 root turn，复现了同事反馈的"嵌套 entry"问题（本是响应式
     场景独有）。
   - 第一轮修正：**Micrometer Observation parent chain 为第一优先通道**。
     Spring AI 通过 Reactor Context 传播 Observation parent，这覆盖了 outer
     用 `.stream()` + inner 用 `.call()` 的场景（inner 的 observation 在
     tool handler 同步线程里构造，`getParentObservation()` 可见）。但是 outer
     + inner 都 `.stream()` 场景仍然失败：inner 的 Observation.Context 在
     Flux 组装时就构造好了，`parentObservation` 字段此时为 null，Reactor
     Context 还没恢复。
   - 第二轮修正：**新增第 2 通道 "Reactor Context Holder"**。Spring AI 内置
     `ToolCallReactiveContextHolder`（`internal` 包）—— 在调 tool 之前把
     outer 的 Reactor Context 快照到 ThreadLocal。SDK 用反射读它，从
     `micrometer.observation` key 取出 outer 的 Observation，爬它的
     `getParentObservation()` 链到 outer ChatClient 的 TurnState。这成功
     让 outer+inner 双层 stream 被识别为同一 trace / turn 下的 subagent。
   - 新增私有方法 `findOuterTurnState(ctx)` + `findOuterTurnStateFromReactorCtx()`。
     后者整体用 `try/catch` 包裹，Spring AI 内部 API 任何改动都会优雅降级
     到后续通道，SDK 不会 crash。

### 代码增量实际

| 文件 | 实际增量 |
|---|---|
| ClsObservationHandler.java | +400 行 |
| SubAgentDemoController.java | 新增 ~180 行（含 `/stream` 和 `/stream-deep` 端点） |
| pom.xml × 3 / README × 2 | 版本号 0.0.7 → 0.0.8 |

### 验证场景覆盖

| 场景 | Trace 结构 | subagent name | parent_tool_call.id | 结论 |
|---|---|---|---|---|
| 同步 `.call()` 嵌套 | ✅ 1 root + N subagent | ✅ tool 名 | ✅ 正确 call id | ✅ 完全达标 |
| outer `.stream()` + inner `.call()` | ✅ 1 root + N subagent | ✅ tool 名 | ✅ 正确 call id | ✅ 完全达标 |
| outer `.stream()` + inner `.stream()` | ✅ 1 root + N subagent | ✅ tool 名 | ✅ 正确 call id | ✅ 完全达标 |

### 嵌套检测的完整通道清单（最终版本）

**TurnState（outer）定位** — `startChatClientTurn` 分流入口按优先级尝试：

| # | 通道 | 覆盖场景 |
|---|---|---|
| 1 | Micrometer Observation parent chain | outer=stream + inner=call（inner obs 在 tool handler 线程同步构造） |
| 2 | Spring AI `ToolCallReactiveContextHolder` 的 Reactor Context | outer=stream + inner=stream（Reactor Context 里有 outer ChatClient obs） |
| 3 | OTel `CLS_TURN_KEY` | 同步跨 ExecutorService |
| 4 | ThreadLocal `CURRENT_TURN` | 同步同线程兜底 |

**ParentTool（name + call id）定位** — `startSubAgentTurn` 入口按优先级尝试：

| # | 通道 | 覆盖场景 |
|---|---|---|
| 1 | `extractParentToolName/CallId`（走 Observation 父链 + KeyValue + 反射） | outer=stream + inner=call（父链健全） |
| 2 | OTel `CLS_TOOL_INFO_KEY`（tool obs onStart 时同线程发布） | outer=stream + inner=stream（父链为空，但 tool obs 的 OTel scope 在同一 Reactor worker 线程上活着） |

### 双层响应式的突破点

早期分析曾断言双层响应式下 `parent_tool_call.id` 必然缺失（因为 tool obs 不进 Reactor Context），但实证发现：
- tool obs 的 **onStart** 和 inner ChatClient 的 **onStart** 都在**同一个 Reactor worker 线程**上执行（时间差 3ms）
- tool obs 的 OTel scope 是在 **BoundedElastic 工作线程** 上 `makeCurrent()` 的，不是 outer 的 HTTP 线程
- → 因此只要在 tool obs onStart 时把 tool identity 发布到 **当前线程的 OTel Context**，inner 的 onStart 在同线程读 Context 就能看到

实现：
- 新增 `CLS_TOOL_INFO_KEY` ContextKey，payload = `ToolInfo{name, callId}`
- `SpanState` 新增 `toolInfoScope` 字段（可空），由 tool 分支在 `onStart` 创建
- Scope 关闭顺序严格 LIFO：`onStop` 先关 `toolInfoScope`，再关 `span scope`，再 end span
- `startSubAgentTurn` 在 Observation 父链失败时读 `CLS_TOOL_INFO_KEY` 兜底

### 对应 git commit

见本次 commit（版本 0.0.8）。
