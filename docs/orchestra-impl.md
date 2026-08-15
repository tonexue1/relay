# Relay · Call / Yield 实现备忘

> 怎么在不改 `agent-core` 的前提下把两格做出来。不是完整 API spec,是准备动手的形状。
> 层:[extracted.md §4.1](./multi-agent-extracted.md)。记忆契约:[multi-agent-memory.md](./multi-agent-memory.md)。

---

## 0. 约束(从现有代码来)

`Agent` 已经定死几件事,orchestra 只能绕,不能改:

- 不是 `Provider`,不能套娃成模型后端。
- `prompt` / `run` 会把入参 **append** 进 `state.messages`。
- tool 只能 `suspend fun execute(...): String`;core 不会把工人的 `AgentEvent` 冒泡出来。
- `ToolExecutionMode.Parallel` 已经能一次跑多个 tool——Call 的并行借这个,不自己写线程池。
- `beforeToolCall` 能拦工人,人在环不用新钩子。

模块:`relay/orchestra`,Kotlin/JVM,`api(project(":relay:agent-core"))`。v1 不依赖 Android;`ArtifactStore` 先内存,files 实现留给 sample。

---

## 1. 共用件(两格都用,先做)

```
relay/orchestra/
  TeamEvent.kt
  TeamLedger.kt
  ArtifactStore.kt          // interface + InMemory
  call/AgentTool.kt
  call/Pipeline.kt
  yield/Scene.kt
  yield/TurnPolicy.kt
  yield/Stage.kt
```

`TeamEvent` 包一层,不改 `AgentEvent`:

```kotlin
sealed interface TeamEvent {
    data class Lead(val event: AgentEvent) : TeamEvent
    data class CallStarted(val workerId: String, val task: String) : TeamEvent
    data class CallChild(val workerId: String, val event: AgentEvent) : TeamEvent
    data class CallEnded(val workerId: String, val result: WorkerReturn) : TeamEvent
    data class YieldStarted(val speakerId: String) : TeamEvent
    data class YieldChild(val speakerId: String, val event: AgentEvent) : TeamEvent
    data class Utterance(val speakerId: String, val text: String) : TeamEvent
}
```

`ArtifactStore.put/get`、`TeamLedger`(runId / goal / plan / assignments)按 memory 文那三个数据类做。工人并行时只写 `artifact://{runId}/{workerId}/...`,回收时 **串行** `assignments +=`。

---

## 2. Call:工人是 `Tool`,并行借 core

### 2.1 `AgentTool`

```kotlin
class AgentTool(
    val workerId: String,
    private val spawn: () -> Agent,          // 每次 execute 都 new,即弃
    private val artifacts: ArtifactStore,
    private val ledger: TeamLedger,
    private val events: SendChannel<TeamEvent>,
    private val maxWorkerTurns: Int = 4,
) : Tool
```

`execute(id, argumentsJson)`:

1. 把 `argumentsJson` 当成任务说明(或解出 `task` 字段)。
2. `spawn()` 出一个干净 `Agent`(`maxTurns = maxWorkerTurns`,自己的 tools / provider)。
3. `agent.prompt(task).collect { events.send(CallChild(workerId, it)) }`。
4. 收 `AgentResult.text` → 收成短 `WorkerReturn`(status / findings / unknowns / refs)。超长正文 `artifacts.put`,结果里只留 ref。
5. `ledger` 记一条 assignment。
6. `return WorkerReturn` 的 JSON 字符串——lead 看见的 tool result 就是这个。

Lead 就是普通 `Agent`,tools = 若干 `AgentTool`。业务写 lead 的 systemPrompt,告诉它有哪些工人。**没有 `Supervisor` 类也可以先活**;需要并行时 lead 一次发多个 tool call,core 的 `Parallel` 会同时 `execute`。

事件怎么冒泡(不改 core):

```kotlin
fun Agent.asLead(events: ReceiveChannel<TeamEvent>): Flow<TeamEvent> = channelFlow {
    launch { prompt(userInput).collect { send(TeamEvent.Lead(it)) } }
    launch { for (child in events) send(child) }
}
```

`AgentTool` 和外层 flow 共享同一个 `Channel<TeamEvent>`。

### 2.2 `Pipeline`

没有 lead LLM。`steps: List<() -> Agent>`,上一步的 `WorkerReturn` 文本(或 ref 内容)当下一步 `prompt`。每步仍走 `AgentTool` 同一条 execute 路径,保证 ledger / artifact / 事件一致。

### 2.3 预算

`TeamLedger` 上 `maxWorkers` / 已派次数。`AgentTool.execute` 超限抛错,变成 lead 的 error tool result(和未知 tool 一样),lead 自己收手。取消:`coroutineContext` 取消会打进 `agent.prompt` 的 `ensureActive`,工人跟着重死。

### 2.4 先写的测试

用 core 测试里那种 `ScriptedProvider`(orchestra 测试目录自备一份,不依赖 test fixtures):

1. 一个 lead script:先 tool_call `researcher`,再出终答。
2. worker script:吐一段长文。
3. 断言 lead 的 tool result 是短 JSON + ref;artifact 里有原文;lead 的 `receivedRequests` 里 **没有** 工人全文。
4. lead 一次两个 tool_call → 两个 worker 的 `CallStarted` 都出现(并行)。
5. `cancel` 外层 Job → 工人 collect 被取消。

---

## 3. Yield:Scene 是真源,`Agent` 是居民

### 3.1 数据

```kotlin
data class Utterance(val speakerId: String, val text: String)

class Scene {
    val lines: List<Utterance>   // append-only
    fun append(line: Utterance)
}

fun interface TurnPolicy {
    suspend fun next(scene: Scene, userJustSpoke: Boolean): String?
}

class Resident(
    val id: String,
    val agent: Agent,            // 长活,maxTurns = 1
    val project: (Scene) -> String,  // 本轮塞进 transformContext 的可见场
)
```

`Stage` 持有 `Scene` + `List<Resident>` + `TurnPolicy`。

### 3.2 一轮怎么跑(绕开 `prompt` 污染)

`Agent.prompt(x)` 会把 `x` 写进 `messages`。Scene 不能当这个 x 的真源,否则每人一份脏历史。

做法:

1. 每个 `Resident.agent` 构造时带 `transformContext`:
   `{ private -> project(scene) 编成若干 Message + private }`。
   投影只用于 **发送**,不写回(和 `WindowTrim` 一样是视图)。
2. 开口:`agent.prompt("你的下一句。")`——messages 里只多一句 tick。
3. 从 `MessageEnd(assistant)` 抽出 text → `scene.append` → 发 `TeamEvent.Utterance`。
4. **回卷**:从 `state.messages` 去掉最后这条 user tick + assistant。居民只留下 private(关系、秘密)。公开对白只活在 Scene。

`maxTurns = 1`,默认无 tool。说完就停。

用户插话:业务把用户句 `scene.append(Utterance("user", text))`,再 `policy.next(userJustSpoke = true)`。

### 3.3 选人

v1 只做规则,例如 `RoundRobin(skip = "user")`。GM / LLM selector 是另一个 `TurnPolicy`,内部可以 `Agent.run` 一次(那是 Call 当策略用),不进 `Stage` 内核。

### 3.4 先写的测试

1. 两个居民,脚本各说一句;RoundRobin → Scene 顺序是 A,B;每个 agent 的 `messages` 回卷后 **不含** 对方台词。
2. 但他们的 `receivedRequests` 里 **能看见** 已公开的 Scene(投影生效)。
3. 居民 C 的 `project` 丢掉某句秘密 → 请求里没有那句。
4. 用户插话后 policy 指定 A → 下一条 utterance 是 A。

---

## 4. 明确不做(避免做歪)

| 不做 | 原因 |
|---|---|
| 改 `Agent` / `AgentEvent` | 单 agent 发布面保持干净 |
| `Supervisor` 重写一遍 loop | lead 就是 `Agent` |
| 共享一份 `AgentState` | Yield 用 Scene + 投影 |
| files 版 ArtifactStore 进 orchestra | JVM 模块;sample 再接 `filesDir` |
| mailbox / Post | 第三条边 |
| playground 第四屏 | 两格单测绿了再挂 |

---

## 5. 动手顺序

1. 脚手架 `:relay:orchestra` + `InMemoryArtifactStore` + `TeamLedger` 单测。
2. **Call**:`AgentTool` + channel 冒泡 + 上面 2.4 的三个测试。
3. `Pipeline` 两步传递 ref。
4. **Yield**:`Scene` + 回卷 + `Stage` + 3.4。
5. 再谈 sample / files / GM policy。

1–3 是调研场景的最小可运行;4 是同场角色的最小可运行。两边只共享 ledger / artifact / `TeamEvent`。
