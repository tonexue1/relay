# Relay · 从场景抽出的多 Agent 拓扑

> 把调研、端云接力、角色模拟 A/B/C 压成机制。商品名(Swarm / GroupChat / Hierarchical)不再当菜单。
> 场景来源:[topology](./multi-agent-topology.md) · [memory](./multi-agent-memory.md) · [role-simulation](./role-simulation.md)

---

## 0. 抽出结果

场景看起来有五种,机制上只剩 **两种多 agent 拓扑**。其余是它们的策略或寿命参数,或根本不是多 agent。

| 拓扑 | 一句话 | 覆盖的场景 |
|---|---|---|
| **Call** | 调用一个 agent,拿回结果,控制权不离开调用方 | 端云 Pipeline、调研 Supervisor、Map–Reduce、套娃 Hierarchical |
| **Yield** | 指定一个 agent 开口,这句话对用户可见,控制权交给「下一句谁说」 | 同场多角色、导演+演员;客服 Handoff 也是它 |

单角色陪聊 **不是**多 agent 拓扑:一个长活 `Agent`。它给 Yield 提供「居民」实例,但不增加第三条边。

```
场景                      拓扑          寿命            上下文
单角色陪聊            (无)           长活            自己的 messages
端云接力 / 流水线      Call           每步即弃        只传产出 / ref
调研派工 / 并行检索    Call           工人即弃        隔离 TaskBrief
同场多角色            Yield          全员长活        Scene 投影 + private
导演 + 演员           Yield          全员长活        同上 + 世界 ledger
```

原先菜单里的 Hierarchical、Map–Reduce = Call 的扇出/嵌套。GroupChat、Swarm/Handoff = Yield 的选人策略不同。不要为它们各做一套 runtime。

---

## 1. 场景怎么压

用四个轴看每个场景。轴相同就视为同一拓扑。

| 轴 | 取值 |
|---|---|
| 控制 | 边写死 / 调用方派工 / 选人开口 |
| 用户听见谁 | 调用方综合 / 被选中的那一个 |
| 上下文 | 隔离 brief / 共享 Scene 投影 |
| 寿命 | 即弃 / 长活 |

### 压掉的

- **单角色**:控制、汇合都不存在。支撑是 Card / Pin / Session,见角色文。
- **Pipeline 和 Supervisor**:都是 Call。差别只是「下一步」由写死的边还是 lead 的 tool 决定。
- **Map–Reduce**:Call 一次派多个,同步等齐。不是新拓扑。
- **Hierarchical**:Call 的返回值里再 Call。不是新拓扑。
- **同场 和 导演+演员**:都是 Yield。差别是选人策略(规则 / GM)和 GM 是否写世界账。演员都长活、都对用户说话。
- **Handoff / Swarm**:Yield 且「当前说话人自己指定下一个」。仍是 Yield,策略不同。
- **GroupChat**:Yield 且「selector 读 Scene 选下一个」。仍是 Yield。

### 不能压成一种的理由

Call 和 Yield 在三处互斥,合成一个 API 会把两边都做坏:

| | Call | Yield |
|---|---|---|
| 对用户 | 调用方说话 | 被叫到的 agent 说话 |
| 返回后 | 实例可丢 | 实例必须还在 |
| 上下文 | 工人不该看见同伴 | 角色必须看见已公开的 Scene |

用 Call 扮演角色 → 用户听到的是摘要,或每句 new Agent 失忆。用 Yield 做检索 → 每个工人直接对用户喷原文,无法综合、无法并行。

---

## 2. 两种拓扑的机制

### Call

```
caller ──invoke──► agent ──result──► caller
                     │
                     └── 大产出 → ArtifactStore,结果里只带 ref
```

- 调用方可以是写死的 Pipeline,或带 tool 的 lead。
- 被调方默认即弃;`new Agent` 或等价干净实例。
- 被调方只看见 `TaskBrief` + 可选 ref。
- 并行 = 一次多个 Call,orchestra 等齐后把 `WorkerReturn` 写进 lead 的 tool 结果。
- 账本:`TeamLedger`(目标 / 计划 / 派工 / ref)。

这就是调研 v1 的 Pipeline + Supervisor,一个原语。

### Yield

```
turn policy ──指定──► agent ──utterance──► Scene ──► 用户
                         ▲                    │
                         └── 投影(card∪private∪scene) ┘
```

- 在场的是长活 `Agent`,不是 tool。
- 真源是 Scene log`(speaker, text)`,不是某个 `AgentState`。
- 每个角色每次开口前投影一次;禁止共享可变 `messages`。
- 一轮默认说一句就停(`maxTurns = 1`)。
- 选人是策略:规则、用户抢话、GM、或以后的 LLM selector。runtime 只认「指定 id → 收一句 → 追加 Scene」。
- 账本:世界 / 关系旗帜(导演场景);秘密按角色分 artifact。

这就是角色 B/C,一个原语。

---

## 3. 和业界名字的对照(只为读文档)

| 抽出的拓扑 | 业界常叫 |
|---|---|
| Call | agent-as-tool、Supervisor、Orchestrator–Worker、CrewAI hierarchical、Magentic-One 内环、ADK Sequential/Parallel |
| Yield | Swarm handoff、LangGraph swarm、GroupChat、SelectorGroupChat、Director/GM、客服转接 |
| (非多 agent) | 单 Agent、Character.AI 式陪聊 |

---

## 4. Orchestra 因此长什么样

```
relay/orchestra
  Call     Pipeline 是边写死的 Call
           Supervisor 是 lead 当调用方的 Call
  Yield    Scene + turn policy
           导演是 Yield + 特权选人 + 写世界账

  共用     ArtifactStore
           Ledger(Call 记派工,Yield 记世界/场次)
```

`agent-core` 仍然只跑单个 loop。Call 把它当函数调;Yield 把它当居民留着。

端云是 Provider 选择,不是第三种拓扑:Call 的工人默认上云;Yield 的演员可以上端。路由继续留在业务层。

### 4.1 Call / Yield 在哪一层

都在 **`relay/orchestra`**,不在 `agent-core`,不在 `relay/llm`。

```
业务 / samples
  选开 Call 还是 Yield
  注册工人、写 turn policy、做人设 Card
        │
        ▼
relay/orchestra          ← Call、Yield 的机制只在这里
  Call:  AgentTool / Pipeline / 汇合 / TeamLedger
  Yield: Scene / TurnPolicy 端口 / Speaker 事件
  共用:  ArtifactStore
        │ 组合 Agent,不修改它
        ▼
relay/agent-core         ← 不知道这两词
  Agent loop / Tool / WindowTrim / AgentEvent
        │
        ▼
relay/llm Provider
```

| 东西 | 层 | 原因 |
|---|---|---|
| `Tool` / `beforeToolCall` / 单次 loop | agent-core | 已经存在;Call 的工人看起来像 tool,但「派工账、brief、汇合」不是单 agent 的事 |
| Call / Yield API、Scene、Ledger | orchestra | 多 agent 的边;core 一旦认识 Scene 或「工人」,单 agent 发布面就被拖脏 |
| 谁当 lead、规则选人还是 GM、Card 内容 | 业务 / sample | 策略,和端云路由同一层 |
| `Provider` / 拦截器 / 端云 | llm / ondevice | 模型后端,不是拓扑 |

容易踩的坑:Call 和 `Tool.execute` 同构,有人会想把 `AgentTool` 直接塞进 core。不要。core 只提供「能调一个 tool」;「这个 tool 是另一个 Agent、要写 ledger、要并行汇合」是 orchestra 的适配器。Yield 更没有 core 可挂的钩子——`AgentEvent` 没有 speaker,硬加会让单 agent 屏也要理解一场戏。

依赖方向:orchestra → agent-core → llm。core 不反向依赖 orchestra。单角色 sample 只依赖 core,连 orchestra 都不要链。

---

## 5. 建议(替换「v1 做两种商品名」)

1. Orchestra 的公开机制就是 **Call 和 Yield**。
2. 调研 / 端云接力只开 Call。
3. 角色同场只开 Yield;单角色连 Yield 都不要。
4. 导演、Handoff、selector 都是 Yield 的 turn policy,不单独立项。
5. Memory 按拓扑分:Call 用即弃 + ledger + ref;Yield 用 Scene 真源 + 每角色投影 + Pin。

实现顺序仍可先 Call(和现有 `Tool` 同构)、后 Yield(要 Scene 事件和长活实例)。但菜单从此是两格,不是六格。

---

## 6. 主流拓扑能不能按这个还原

Call / Yield 不是新发明,是控制流的两格。主流名字还要 **策略**(谁决定下一步、是否并行)+ **记忆**(隔离 brief / 全历史 / Scene 投影)+ **寿命**(即弃 / 长活)。下面按「配方」还原;还原不了的单独说。

约定:Call / Yield 的对象可以是一个 `Agent`,也可以是另一段 orchestra(嵌套)。一个正在 Yield 的居民,自己的 `Tool` 仍可以是 Call——这就是「边说话边派工」,不必第三种边。

### 6.1 能还原

| 主流 | 配方 | 要额外写的只是策略 / 记忆 |
|---|---|---|
| Pipeline / CrewAI sequential / ADK Sequential | 写死顺序的 Call | 边上传 `WorkerReturn` 或 ref |
| Supervisor / OpenAI as-tool / Anthropic Research / CrewAI hierarchical | lead 的 tool = Call(worker) | 隔离 brief;并行 = 一次多个 Call;最后再 Call 一个 citation 也行 |
| Hierarchical / 嵌套 supervisor | Call 的目标是另一段 Call-orchestra | 每层自己的 ledger |
| Map–Reduce / ADK Parallel / LangGraph map-reduce | 一次 N 个 Call,调用方等齐 | 切分函数是业务,不是拓扑 |
| Magentic-One | 上面的 Supervisor + stall 则改 plan 再 Call | Task / Progress ledger 字段 |
| Swarm / OpenAI handoff | Yield,turn policy = 当前说话人指定下一个 | 交接时的历史过滤(`input_filter`)是投影策略 |
| GroupChat / SelectorGroupChat | Yield,turn policy = 规则或 LLM 读 Scene 选人 | 投影 = 全 Scene 即「共享频道」;投影 = 裁剪即省 token |
| LangGraph Network(谁都能指定下一个) | Yield,下一候选人 = 全体 | 和 Swarm 只差候选集 |
| 导演 + 演员 | Yield,turn policy = GM | 世界旗帜进 ledger;GM 可以自己不开口 |
| 单角色 / Character.AI | 不用 orchestra | Card + Pin + Session |
| 辩论 / 评审 | 两种写法都成立:Yield 轮流骂,或 Call(critic) 再 Call(revise) | 选哪种看用户要不要听见过程 |
| Human-in-the-loop | Call 走现有 `beforeToolCall`;或 Yield 给用户 | 已有机制 |

LangGraph 文档里的 Network 有时指「谁都能把别人当 tool 调」。那是 **每个居民的工具列表里挂着对他人的 Call**,不是第四种拓扑。返回值回到调用方,用户听见的仍是当前 Yield 的那个人(或外层 lead)。

### 6.2 差一步(还是这两格,但要补调度)

| 主流 | 差在哪 | 怎么补,仍不算新拓扑 |
|---|---|---|
| Anthropic 想做的异步工人 | 我们的 Call 默认同步等齐 | Call 允许 fire-and-forget,完成事件写 ledger,lead 下一轮再读。调度策略 |
| OpenAI handoff 默认塞全历史 | 我们的 Yield 默认 Scene 投影 | 投影函数换成「过滤后的 transcript」。记忆策略 |
| AutoGen 每人一份完整 messages | 我们主张 Scene 为真源、按需投影 | 投影 = 全量即行为等价;不要真的共享可变 `AgentState` |
| 彩虹发布 / 长任务恢复 | 即弃 Call 丢了半截 | ledger + artifact 已够续;要的是 checkpoint,不是新边 |

### 6.3 还原不了(诚实缺口)

**同伴私信 / Claude Code Agent Teams mailbox。**

工人既不对用户说话(不是 Yield),也不把结果交回唯一的调用方(不是 Call),而是给另一个同伴发一条只有他们看见的消息。这是第三条边,暂叫 **Post**:写进某个收件箱,不占用户 Scene,也不阻塞调用方。

v1 不做。Teams 自己也还是实验开关。Relay 用 Call 的返回值或 Yield 的公开句都能绕开大部分「同伴协调」;真要实时互改同一份草稿,再开 Post。

**无事务的共享黑板并发写。** 不是拓扑,是存储。Call / Yield 都还原不了「两人同时改同一字段还正确」。v1 继续:只许写自己的 artifact,orchestra 串行 merge。

### 6.4 一张对照(读文档用)

```
主流名字                    控制格    选人/派工策略           用户听见
Pipeline                    Call      边写死                 最后一步或外层
Supervisor / Research       Call      lead 选 tool           lead
Hierarchical                Call      嵌套 lead              最外层
Map–Reduce                  Call      数据扇出               调用方
Magentic-One                Call      lead + 重规划          lead
Handoff / Swarm             Yield     当前 speaker 指定      被交到的人
GroupChat / Selector        Yield     规则或 LLM 选          被选中的人
Network(指定下一个)         Yield     候选 = 全体            被指定的人
Network(互为 tool)          Call      挂在居民的 tools 上    当前 Yield 者或外层
Director                    Yield     GM                     演员(GM 可选旁白)
Agent Teams mailbox         Post      —                      用户听不见这条
```

结论:**除 mailbox / 并发黑板外,主流都能用 Call 或 Yield 加策略还原。** 创新处不在发明第三种协作,而在不把策略做成模块。
