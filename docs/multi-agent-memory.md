# Relay · Orchestra 层如何做 Memory

> 状态:接 [multi-agent-topology.md](./multi-agent-topology.md) 的设计备忘,不是实现 spec。
> 问题:在 `agent-core` 之上加 Pipeline / Supervisor 时,记忆放哪、谁写、谁读、何时忘。
> 非目标:向量库、跨 App 用户画像、Mem0 类产品。那些是业务策略,而且 `embedding` 在架构里仍是后置。

---

## 0. 结论先行

单 agent 已经有记忆,只是很薄:

| 现有 | 是什么 | 不是什么 |
|---|---|---|
| `AgentState.messages` | 该 agent 的完整 transcript,唯一真源 | 不是团队共享脑 |
| `WindowTrim` | 发送前的**视图**,不写回 `messages` | 不是摘要,不是持久化 |
| `transformContext` | 可替换的投影钩子 | 目前默认只 trim |

Orchestra 再做一个「更大的 `messages`」是错的。多 agent 的记忆问题是 **scope**:谁看得见、大对象走哪条路、lead 被 trim 之后什么必须还在。

v1 只做三格,而且后两格要小:

```
每 Agent 一份 working memory     = 现有 AgentState(隔离,用完即弃)
一份 TeamLedger                  = 小组结构化状态(目标/计划/派工/结果指针)
一个 ArtifactStore               = 大产出按引用存(filesDir),消息里只传 ref
```

原则:**私有 scratch 默认不共享;共享层只收结构化、已收敛、带引用的东西。** 共享 transcript 是 GroupChat 的做法,v1 不做那种拓扑,也就不要那种记忆。

---

## 1. 先分清四类记忆,只让 orchestra 认领其中两类

经典分法对实现有用,但不要做成四个模块。

| 种类 | 内容 | 谁拥有 | v1 |
|---|---|---|---|
| Working | 当前窗口里的消息 | 单个 `Agent` | 已有,orchestra **不碰** |
| Episodic / Ledger | 这次 run 发生过什么:计划、派了谁、试过什么 | orchestra | **做**。小 JSON,能 checkpoint |
| Artifact | 报告、检索原文、代码、表格 | orchestra 提供 store,业务决定写什么 | **做机制**。按 run 分目录 |
| Semantic | 长期事实、用户偏好、跨 run 检索 | 业务 / 以后的 embedding | **不做**。没有 `EmbeddingProvider` |

Magentic-One 的 Task Ledger / Progress Ledger、Anthropic 把 plan 写到 Memory、子 agent 把大产出落到 filesystem,都是「ledger + artifact」,不是向量记忆。

---

## 2. 现在的代码已经定了两条不该破坏的不变量

`AgentState` 写明:`messages` 是 transcript 真源;trim **不写回**。所以:

1. **不要让 orchestra 去改子 agent 的 `messages`。** 投影继续走 `transformContext`。
2. **不要让两个 `Agent` 共享一个 `AgentState`。** 拓扑文已经说了:共享实例会把角色和 tool 结果揉在一起,`WindowTrim` 会误删别人的 turn。
3. **Worker 用完即丢。** 每次派工 `new Agent`(或以后的 `fork()` 出干净实例)。这是 **reset,不是 compaction**。工人的长思考不必摘要进 lead;该留的东西在返回值或 artifact 里。

`architecture.md` 写过「超窗则裁剪 / 摘要」。今天只实现了裁剪。摘要不要先做进 `agent-core`:在 orchestra 里,摘要是 **worker 的返回契约**,不是对 lead transcript 做就地改写。就地摘要会丢 tool_call 配对,也和「trim 不写回」打架。

---

## 3. 读写规则(比 API 名字重要)

### 3.1 Worker 看见什么

只给自包含任务包,不给 lead 的 transcript。

```
TaskBrief
  goal          这一枪要完成什么
  constraints   边界:不要搜什么、何时停
  outputSchema  必须按这个结构回来
  artifactRefs  可选,需要读的上一手材料
```

这是 Anthropic 能并行的前提:子 agent **不知道同伴存在**。需要互看时,走 artifact,不走共享聊天。

### 3.2 Worker 准许写什么

返回给 lead 的 tool result **短**。建议固定形状,避免「先工人摘要、再 lead 摘要摘要」的电话游戏:

```
WorkerReturn
  status        ok | partial | failed
  findings[]    短句,可被 lead 直接引用
  unknowns[]    没做成的,避免下一枪重复
  artifactRefs[] 大材料的指针
```

原文、长表、HTML、代码进 `ArtifactStore`。Lead 的消息里只有 `artifact://{runId}/{name}` 加一两句。Citation / 终态评审 **直接读 artifact**,不要读 lead 的转述。

### 3.3 Lead 看见什么

Lead 的 working memory 仍是它自己的 `messages`(用户目标 + 它的派工 tool 往返)。另外,orchestra 在每次 lead 调用前注入一份 **ledger 快照**(短,结构化):

- 目标原文(trim 掉用户第一句之后还能找回)
- 当前计划
- 已派工:谁、任务、status、result ref
- 已花掉的 worker 次数 / 预算

计划必须在窗口被截断**之前**落到 ledger。Anthropic 的原话就是:超 200K 会被截,plan 得活在窗外。Relay 的云模型窗口没那么夸张,但 lead 一样会被 `WindowTrim` 丢掉早期用户句和早期派工——没有 ledger,lead 会重复派同一枪。

### 3.4 Pipeline 看见什么

步与步 **不共享 `AgentState`**。N 的输入是 N-1 的 `WorkerReturn`(或其中的 artifact ref),不是 N-1 的全文 transcript。这和拓扑文「确定性边只传产出」是同一条规则。

---

## 4. 机制放哪

```
业务编排
  · 要不要落盘、哪些 artifact 出域、跨 run 记什么     ← 策略
        │
        ▼
relay/orchestra
  · TeamLedger(本次 run)
  · ArtifactStore 端口 + 默认 files 实现
  · 派工时组装 TaskBrief;回收时写入 ledger
  · TeamEvent 带上 ref,不带正文
        │
        ▼
relay/agent-core
  · 仍然只懂自己的 messages + WindowTrim
  · 不 import orchestra,不出现 Memory 基类
```

端口保持小。不要先造 `Memory` 上帝接口(read/write/search/embed/forget 一把抓)。

```kotlin
data class ArtifactRef(val runId: String, val name: String)

interface ArtifactStore {
    suspend fun put(runId: String, name: String, text: String): ArtifactRef
    suspend fun get(ref: ArtifactRef): String
}

data class Assignment(
    val workerId: String,
    val task: String,
    val status: Status,
    val returnSummary: String? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
)

class TeamLedger(
    val runId: String,
    val goal: String,
    var plan: String? = null,
    val assignments: MutableList<Assignment> = mutableListOf(),
)
```

`ArtifactStore` 默认实现指向 `context.filesDir/orchestra/{runId}/`。单测用内存 map。云端以后可以换对象存储,契约不变。

Ledger 序列化成同一个 run 目录下的 `ledger.json`。Android 进程随时会被杀:可恢复的是 **ledger + artifacts**,不是某个 `Agent` 堆上的 `messages`。Lead 若要续跑,用 ledger 重建一个新的 lead `Agent`,把 goal/plan/assignments 填回 system 或第一轮 user,而不是试图 freeze 整个 transcript。

---

## 5. 和拓扑怎么对齐

| 拓扑 | 记忆含义 | v1 |
|---|---|---|
| Supervisor | Lead 有 ledger;每个 worker 一份即弃 working memory;大产出走 artifact | 主场景 |
| Pipeline | 边上流动的是 `WorkerReturn` / ref,不是共享脑 | 主场景 |
| Hierarchical | 每层自己一份 ledger,向上只交摘要 + ref | 组合得到,不必先做树形 memory |
| Handoff | 要定义「交接包」= 过滤后的历史或一份 handoff 文件 | 后置 |
| GroupChat | 共享 transcript 就是记忆 | 不做 |

并发写是陷阱。v1 Supervisor 即使并行工人,也 **禁止工人写同一份可变 ledger 字段**。工人只写自己的 artifact(名字带 `workerId`);回收时由 orchestra **串行** merge 进 `assignments`。共享黑板的事务问题先不要碰。

---

## 6. 端上怎么叠

端侧默认不当 worker,记忆也不该假设端上能跑 embedding 检索。

| 做法 | 是否做 |
|---|---|
| Artifact 落在 App `filesDir`,按 run 封顶(个数 / 总字节) | 做 |
| Ledger 小、可 JSON、可恢复 | 做 |
| 用端侧 3B 做「会话摘要模型」 | 后置。贵、慢、还引入第二套 trim 语义 |
| 向量召回跨 run | 不做。等 `EmbeddingProvider` |
| 把用户长期偏好塞进 orchestra | 不做。那是业务层,和拓扑无关 |

端云协同已有的路径(端上分类 → 云上 agent)不需要团队记忆:那是 Pipeline 的一跳,记一次分类结果当 artifact 即可。

---

## 7. 遗忘(不做遗忘的记忆都会胀死)

| 对象 | 何时忘 | 谁执行 |
|---|---|---|
| Worker `Agent` | tool 返回后立刻丢弃实例 | orchestra |
| Lead `messages` | 照旧 `WindowTrim`;被裁掉的派工细节以 ledger 为准 | agent-core |
| Artifact | run 结束或超过字节预算;playground 可手动清 | store + 策略 |
| Ledger | run 结束可归档;默认不跨 run | orchestra |
| 跨 run 语义 | v1 无 | — |

「忘」是机制:TTL、run scope、丢弃实例。什么值得跨 run 留下是策略,v1 给空实现。

---

## 8. 反模式

| 做法 | 为什么糟 |
|---|---|
| 全员共享一份 `AgentState.messages` | 角色串味;trim 拆开 tool 配对;无法并行 |
| 把 worker 全文 transcript append 给 lead | lead 窗口按人数爆炸,15× 之外再乘材料长度 |
| 先摘要再摘要 | 电话游戏;引用层读不到原文 |
| 在 `agent-core` 加 `Memory` 接口 | 单 agent 被拖进团队语义;循环依赖 |
| 一上来上向量库 | 没有 embedding 端口;手机上还多一个索引寿命问题 |
| 工人并发写同一 ledger 字段 | 无事务,派工账对不上 |
| 靠 compaction 续超长 lead | 丢掉 plan/目标;应用 reset + ledger 外置 |

---

## 9. 建议决策

1. **Orchestra 的 memory = Ledger + ArtifactStore。** Working memory 留在每个 `Agent` 里。
2. **Worker 隔离 + 用完 reset。** 返回契约短、结构化;大东西只传 ref。
3. **Lead 靠 ledger 抗 trim,不靠改 `WindowTrim`。** 目标/计划/派工账活在窗外。
4. **不共享可变黑板。** 并行只允许写自己的 artifact,由 orchestra 合并。
5. **语义记忆、跨 run、端侧摘要模型全部后置。**

拍板后实现顺序:内存 `ArtifactStore` + `TeamLedger` 的单测 → files 实现 → Supervisor 派工/回收走这两样 → playground 能看见 ref 而不是把长文糊在事件流里。
