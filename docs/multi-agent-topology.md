# Relay · 多 Agent 拓扑调研

> 状态:调研落盘(2026-08-15),供「在 `agent-core` 之上加一层」决策用,不是实现 spec。
> 范围:常见通信/控制拓扑、业界框架怎么落地、对 Relay(Android 库 + 端云协同)的约束。
> 非目标:选具体模型、写 API、做评测。动态生成拓扑(扩散模型 / 自进化 DAG)只作背景,不进 v1。
> 从调研 + 角色模拟场景压出来的机制只有 Call / Yield,见 [multi-agent-extracted.md](./multi-agent-extracted.md)。

---

## 0. 结论先行

多 agent 的本质不是「多几个 LLM」,而是三件事同时定下来:

1. **控制权**谁决定下一步(中心 / 对等 / 图边)
2. **上下文**怎么切(共享一份 transcript / 每人一窗 / 只回摘要)
3. **汇合**怎么发生(同步等齐 / 交接控制权 / 共享黑板)

业界产品层已经收敛到大约 **6 种可实现拓扑**。研究层在谈「按任务生成拓扑」,对 Relay 这种手机侧 runtime 过早。

对 Relay 的建议:

| 决策 | 建议 | 理由 |
|---|---|---|
| 层放哪 | 新模块 `relay/orchestra`(暂名),**在 `agent-core` 之上** | `Agent` 已写明「不要把自己包成 `Provider`」,嵌套 loop 会炸 |
| v1 做哪两种 | **Pipeline**(确定性边)+ **Supervisor / orchestrator-worker** | 可取消、可预算、和现有 tool dispatch 同构 |
| v1 不做 | Swarm / GroupChat / 全连接 Network | 回合不可界、token 爆炸、端上更糟 |
| 端侧角色 | 默认不当 worker;最多做路由/分类信号 | 3B CPU 已经是单 agent 预算;多一份 context 就是多一轮预填充 |
| 控制原语 | 先做 **agent-as-tool**(调用后返回),handoff 后置 | 和现有 `Tool` + `beforeToolCall` 对齐,事件也好挂 |

---

## 1. 问题框:现在缺的是哪一层

当前栈(已落地):

```
samples/playground
        │
        ▼
relay/agent-core     单个 Agent: loop + tools + window trim + AgentEvent
        │ 只依赖 Provider
        ▼
relay/llm            Provider / 拦截器 / FallbackProvider
        ▲
relay/ondevice       端侧 Provider(llama.cpp JNI)
```

`Agent` 是**有状态的单模型 loop**。它吃一个 `Provider`、一套 `Tool`、一份 `AgentState.messages`。注释已经排除了「Agent 再实现 Provider」——那会套娃。

所以多 agent **不是**再做一个更胖的 `Agent`。它是:

> 若干个已经能跑的 `Agent`(或等价 runner),按某种拓扑被调度、隔离上下文、汇合结果。

这和架构文档里的原则一致:**机制与策略分离**。拓扑是机制;谁当 lead、几个 worker、何时停,是策略,应留给业务编排层。

---

## 2. 三个正交轴(比「选一个名字」有用)

框架喜欢用 Supervisor / Swarm / Hierarchical 当菜单。实现时真正要选的是下面三轴的组合。

### 2.1 控制权

| 模式 | 谁决定下一步 | 典型产品名 |
|---|---|---|
| 中心调度 | 一个 lead / supervisor / orchestrator | Anthropic Research、Magentic-One、LangGraph supervisor、OpenAI *agents-as-tools* |
| 对等交接 | 当前 speaker 把控制权交给下一个 | OpenAI Swarm / Agents SDK *handoffs*、LangGraph swarm |
| 图边 | 边是写死的,或条件边,模型只填节点 | CrewAI sequential、LangGraph custom workflow、Google ADK Sequential/Parallel/Loop |
| 选择器 | 一个独立 selector(常是另一次 LLM)挑下一个 speaker | AutoGen SelectorGroupChat / GroupChatManager |

### 2.2 上下文隔离

| 模式 | 每个 agent 看见什么 | 代价 |
|---|---|---|
| 共享 transcript | 全员同一份消息 | 简单,窗口很快满,角色串味 |
| 隔离窗口 + 回摘要 | worker 只拿任务说明,回压缩结果 | 可并行;lead 有信息损失(电话游戏) |
| 共享工件 / 黑板 | 大产出落文件或 store,消息里只传引用 | Anthropic 明确推荐,防 coordinator 被原文撑爆 |
| 过滤后交接 | handoff 时裁剪/改写历史 | OpenAI `input_filter`;实现成本高 |

Anthropic 的隔离边界很极端:research 子 agent **不知道彼此存在**,只拿自包含任务。这是能真并行的前提。需要互相看见时,他们另做了 Claude Code Agent Teams(共享 task list + mailbox)——那是另一种拓扑。

### 2.3 汇合

- **同步屏障**:lead 等一批 worker 齐了再综合。实现简单,一个慢 worker 卡住全体。Anthropic Research 生产上仍是同步的,他们自己把这写成已知瓶颈。
- **控制权转移**:没有综合步,下一个 agent 直接对用户说话。
- **黑板 / 共享任务表**:谁做完谁写回,lead 或同伴再读。
- **评审关卡**:另开一个 critic / citation agent,只看终态。

---

## 3. 六种常见拓扑

下面按「产品里真能见到」而不是论文菜单来分。每种都写成:结构 / 何时成立 / 何时失败 / 谁在用。

### 3.1 Pipeline(顺序流水线)

```
A ──► B ──► C ──► 输出
```

边是确定性的。A 的产出是 B 的输入。没有路由 LLM。

- **成立**:阶段稳定(检索 → 写作 → 引用检查;或 端侧分类 → 云端回答)。
- **失败**:中途要回头、阶段数随任务变。
- **谁在用**:CrewAI `process=sequential`;Google ADK `SequentialAgent`;大量「agent 其实是 DAG 节点」的内部工作流。
- **Relay**:v1 必做。零额外模型调用,和现有 `Agent.run()` 组合即可。端云接力本身就是一条 pipeline。

### 3.2 Supervisor / Orchestrator–Worker

```
          ┌─ worker A (自有窗口)
lead ────┼─ worker B
          └─ worker C
               │
               ▼
          lead 综合 / 再派
```

Lead 分解任务、派工、综合。Worker 是 **tool**:调用,返回,控制权不离开 lead。

- **成立**:任务可切成相对独立的子问题;需要一个对用户负责的合成口。
- **失败**:子任务强依赖(写代码改同一文件)、lead 提示含糊导致工人重复劳动。Anthropic 早期失败模式:简单问题拉 50 个子 agent、工人搜同一件事。
- **谁在用**:
  - Anthropic Research:Opus lead + 并行 Sonnet 子 agent + 最后 CitationAgent([工程文, 2025-06-13](https://www.anthropic.com/engineering/multi-agent-research-system))
  - Magentic-One:Orchestrator 外环改 Task Ledger、内环改 Progress Ledger,stall 则重规划
  - LangGraph supervisor;OpenAI `Agent.as_tool()`;CrewAI `process=hierarchical`
- **Relay**:v1 主拓扑。和现有 `Tool.execute` 同构——worker 就是一种 tool,只是 `execute` 里跑另一个 `Agent`。`beforeToolCall` 可复用成人在环拦截。

### 3.3 Hierarchical(监督者的监督者)

```
manager
  ├─ team lead 1 ── workers
  └─ team lead 2 ── workers
```

Supervisor 套 Supervisor。只有 lead 的上下文或扇出撑不住时才值得加一层。

- **成立**:15+ 角色、天然有团队边界(研究组 / 代码组)。
- **失败**:两层 lead 都在烧 token,延迟叠乘。
- **谁在用**:LangGraph 把 compiled supervisor 再嵌进父图;企业编排博文常吹「15+ agents」,缺少公开对照实验。
- **Relay**:v1 不做。Supervisor 组合自身就能长出第二层,不必先造树 API。

### 3.4 Swarm / Handoff(对等交接)

```
triage ──handoff──► billing ──handoff──► refund
                         ▲                    │
                         └──── 可再交回 ──────┘
```

当前 agent 把**对话控制权**交给下一个。下一个默认看见(过滤后的)历史,并直接对用户说话。

- **成立**:交互式分流(客服、按技能续聊);「谁在跟用户说话」本身就是产品语义。
- **失败**:需要综合多方产出、要并行、要一个地方做预算/取消。交接是单向的,出错要接收方自己扛。
- **谁在用**:OpenAI Swarm → Agents SDK `handoffs`;LangGraph swarm(记住上次 active agent,下次从那儿续)。
- **Relay**:后置。Playground 现在是「模块分屏自测」,不是多角色客服。Handoff 还要定义「对用户可见的当前 speaker」,UI 和事件模型都要扩。

### 3.5 Group Chat / Blackboard(共享频道)

```
        shared transcript / topic
   ┌────────────┼────────────┐
worker A    selector     worker B
```

全员往同一频道发。下一个 speaker 由 round-robin 或 LLM selector 选。AutoGen 的原始隐喻是「开会」。

- **成立**:需要互相看见、辩论、评审;角色少(2–4)。
- **失败**:每人重读全文 → token × N;selector 本身又一次 LLM;顺序执行,假并行。AutoGen 自己写明 group chat 是顺序的,同一时刻只有一个 agent 在干活。
- **谁在用**:AutoGen `RoundRobinGroupChat` / `SelectorGroupChat`;部分「多专家讨论」demo。
- **Relay**:不做默认拓扑。若以后要「端侧小模型当评审」,用 Pipeline 末尾加一个 critic agent 更便宜。

### 3.6 Parallel / Map–Reduce

```
          ┌─ map(item 1)
fan-out ──┼─ map(item 2) ──► reduce
          └─ map(item N)
```

切分是数据并行,不是角色并行。边可以是确定性的(map 完 reduce),也可以由 supervisor 动态 fan-out。

- **成立**:子任务同构且独立(N 家公司董事、N 个文件审查)。这是 Anthropic 说 multi-agent **值得付钱**的主场景。
- **失败**:子任务要实时协调。Anthropic 原文:多数编码任务并行度不够,agent 也不擅长实时互相委派。
- **谁在用**:LangGraph map-reduce;Google ADK `ParallelAgent`;Claude Code 用 `Task` tool 拉并行 subagent。
- **Relay**:不必单列模块。Supervisor 的「一次派多个 worker」就是它。机制上要保证 `ToolExecutionMode.Parallel` 能套在 agent-tool 上。

### 3.7 刻意不收进 v1 菜单的

- **全连接 Network**:每个 agent 都能叫其他所有人。LangGraph 有概念页,生产里几乎立刻退化成无预算的 group chat。
- **辩论 / Multi-persona 同模型**:有时有效,但是 prompt 技巧,不是 runtime 拓扑。
- **生成式拓扑**(GTD 图扩散、QueenBee 自进化 DAG、AdaptOrch 按任务选四原型):2025–2026 论文活跃,假设「拓扑是一等优化目标」。对手机库来说,先把两种静态拓扑做对、可观测,比学会生成图重要。

---

## 4. 框架对照(同一套轴)

| 框架 | 主拓扑 | 控制原语 | 上下文默认 | 备注 |
|---|---|---|---|---|
| LangGraph | supervisor / swarm / hierarchical / custom graph | 节点 + 边 + `Command` handoff | 图 State,可共享或分子图 | 文档把 Network 也列出;custom workflow = 确定性边 |
| OpenAI Agents SDK | manager vs handoff | `Agent.as_tool()` vs `handoff()` | tool:只见参数;handoff:默认全历史 | Swarm 已收进 SDK,不再是独立产品 |
| AutoGen | group chat / Magentic-One | selector 或 Orchestrator ledger | 共享频道 vs lead 账本 | Group chat **顺序**;Magentic-One 是 supervisor + 重规划 |
| CrewAI | sequential / hierarchical | Task 队列;hierarchical 有 manager | 按 Task 传产出 | 原语是「工作项」不是「对话」 |
| Anthropic Research | orchestrator–worker + 终态 citation | lead 用 tool 拉子 agent | 子 agent 完全隔离,回摘要;计划外置 Memory | 同步等齐;异步是他们写下的下一步 |
| Claude Code | subagent vs Agent Teams | `Task` tool vs 共享 task list + mailbox | 都是自有窗口;Teams 允许同伴直连 | Teams 仍实验,默认关 |
| Google ADK | Sequential / Parallel / Loop | 工作流原语 | 按 agent 配置 | 偏确定性编排,不像 AutoGen 那样开会 |

读这一表时,不要被商品名带跑。**OpenAI 的 manager ≈ Anthropic 的 orchestrator ≈ LangGraph supervisor ≈ CrewAI hierarchical**。**OpenAI handoff ≈ LangGraph swarm**。**CrewAI sequential ≈ ADK Sequential ≈ LangGraph custom edges**。

---

## 5. 证据、适用边界、反例

数字全部来自 Anthropic 官方工程文(2025-06-13),评测是他们**内部** research eval / BrowseComp 分析,不是通用 benchmark。只说明「广度并行检索」这一种任务。

| 主张 | 数字 | 适用范围 | 反例 / 他们自己的限制 |
|---|---|---|---|
| 多 agent 在广度查询上强于单 agent | Opus lead + Sonnet workers 相对单 Opus **+90.2%** | 可切成独立检索方向的内部题 | 同一文:强共享上下文、强依赖的任务不适合 |
| 多 agent 主要靠「花更多 token」 | BrowseComp 上 token 用量解释 **~80%** 方差;三因子合计 ~95% | 浏览/检索类 | 换更强模型(3.7→4)比「把 3.7 的预算加倍」更有效 |
| 成本 | 单 agent ≈ chat 的 **4×**;多 agent ≈ chat 的 **15×** | 他们的 Research 产品 | 任务价值必须盖过 15×;手机流量/电量更敏感 |
| 并行换时间 | lead 一次拉 3–5 子 agent + 子 agent 3+ 工具并行,复杂查询最多 **~90%** 墙钟下降 | 可并行的检索 | 生产 lead 仍**同步**等子 agent,他们承认这是瓶颈 |
| 提示比拓扑更先炸 | 早期:简单问题拉 50 个子 agent;工人重复同一搜索 | 任何 supervisor | 必须在 lead prompt 里写努力预算(1 / 2–4 / 10+ 工人) |

AdaptOrch 预印本称四种静态拓扑之间按任务切换有 12–23% 提升。当作「拓扑选择有影响」的弱证据,不当作 Relay 要做动态路由的理由。

**反确认(什么时候不该上多 agent):**

1. 单 agent + 好工具已经能做完(多数 playground 现有屏)。
2. 子任务不能自包含描述(工人必须偷看同伴的 scratch)。
3. 需要实时互改同一可变状态(同一份源码、同一份 KV)。Anthropic 对「多数 coding」持保留。
4. 预算封顶的端侧。多一个隔离窗口 = 多一轮 prefill;Mate 70 Pro 上 3B 已经是单会话资产。

---

## 6. 对 Relay 的落点

### 6.1 层放在哪

```
业务编排 / samples/*
        │ 选拓扑、选谁当 lead、定预算
        ▼
relay/orchestra          新层:Pipeline / Supervisor,发 TeamEvent
        │ 组合已有 Agent,不替代它
        ▼
relay/agent-core         不变:单 agent loop
        │
        ▼
relay/llm Provider
```

不放进 `relay-llm`:那一层的契约是「一个模型后端」。不把 `Agent` 改成 `Provider`:`Agent.kt` 已禁止套娃。不把拓扑塞进现有 `AgentConfig`:那是单 loop 的旋钮(`maxTurns` / `toolExecution`),和「几个 agent、怎么连」不是同一类状态。

命名倾向 `orchestra` 而不是 `multi-agent`:后者太泛,且和「Agent 模块」在 playground 里撞名。可再议。

### 6.2 v1 机制(只列必须有的)

1. **`Pipeline`**:`List<Step>`,每步一个 `Agent`(或任意 `suspend (String) -> String`)。步与步只传文本/结构化产出,不共享 `AgentState`。
2. **`Supervisor`**:一个 lead `Agent`;workers 注册成 `Tool`(或 `AgentTool`)。Lead 的 `maxTurns` 就是派工轮次上限。并行走现有 `ToolExecutionMode.Parallel`。
3. **`TeamEvent`**:在 `AgentEvent` 外包一层 `WorkerStart/End`、`StepStart/End`,playground 才能画「谁在跑」。不要改 `AgentEvent` 语义。
4. **预算**:`maxWorkers`、`maxWorkerTurns`、总超时。Anthropic 的教训是 lead 不会自己收手。
5. **取消**:coroutine `Job` 取消必须打到正在跑的子 `Agent`。单 agent 已经 `ensureActive()`;orchestra 要会 `cancelChildren`。

### 6.3 明确后置

- Handoff / 当前 speaker(要改 UI 和会话模型)
- GroupChat selector
- 动态生成拓扑
- Worker 之间直连 mailbox(Claude Code Teams 那种)

### 6.4 端云怎么叠,而不是「端上也拉一队」

现有架构已经把**路由当业务策略**。多 agent 不改变这一点。

合理叠法:

| 模式 | 做法 | 何时 |
|---|---|---|
| 单云 agent | 现状 | 默认 |
| Pipeline 端→云 | 端侧分类/脱敏 → 云端 agent | 已在架构文档里,应先于多 agent 做 |
| 云 supervisor + 云 workers | Research / 多源检索 | 任务值钱、可并行 |
| 云 supervisor + 端 worker | 端只做便宜信号(分类、PII、缓存键) | worker 必须是**短、无 tool 或极少 tool** |
| 端 supervisor | 不建议 | 3B 当 lead 分解任务,失败模式就是 Anthropic 早期的「乱派工」,而且更慢 |

「多 agent」和「端云协同」是两轴。前者解决**并行与上下文容量**,后者解决**成本、隐私、离线**。不要用多 agent 层去实现路由——路由继续用 `RoutingProvider` / 业务策略。

### 6.5 和现有代码的接口摩擦

- `Agent` 持有可变 `state`,不能当无状态函数复用。Supervisor 每派一次工应 **new 一个 worker Agent**,或提供 `fork()`。共享同一实例会把 transcript 揉在一起。
- Worker 不要看见 lead 的 tools。隔离 = 独立 `tools` 列表。
- `WindowTrim` 只裁单个 transcript。Orchestra 需要另一条规则:worker 回给 lead 的是摘要,不是全文。
- Playground 按模块分屏。新层应是第四张卡 `relay/orchestra`,不要塞进 agent-core 屏。

---

## 7. 风险登记

| 若判断错 | 炸在哪 |
|---|---|
| 先做 Swarm | 取消/预算没有单一责任人;端上一次会话打爆 |
| 把 Agent 包成 Provider | 双重 loop、usage 重复、事件套娃 |
| 共享一份 `AgentState` | 角色串味,trim 误删别人的 tool 结果 |
| 当通用「智能提升」卖 | 15× token,多数任务单 agent 更好;库的定位被带偏 |
| 端侧默认当 worker | 预填充和热节流把「并行」变成更慢的串行 |
| 没有努力预算 | lead 把简单问题拆成十个工人(Anthropic 已踩过) |

---

## 8. 建议决策(供拍板)

1. **加一层,不加进 `agent-core`。** 单 agent loop 保持 pi 风格、可单测、可单独发布。
2. **v1 只交付 Pipeline + Supervisor(agent-as-tool)。** Hierarchical 用组合得到;Parallel 是 Supervisor 的派工模式。
3. **Handoff / GroupChat 不进 v1。** 等有真实「多角色对用户说话」的 sample。
4. **端侧不进 orchestra 的默认路径。** 端云仍走 Provider / 路由,不走「端上组队」。
5. **先写失败预算和事件,再写聪明 lead prompt。** 拓扑选对但不可取消,在手机上比选错拓扑更致命。

拍板之后再开实现 spec(模块名、`TeamEvent`、`AgentTool`、playground 第四屏)。

---

## 来源

- Anthropic, *How we built our multi-agent research system*, 2025-06-13. https://www.anthropic.com/engineering/multi-agent-research-system
- Claude Code docs, *Agent Teams* (experimental). https://code.claude.com/docs/en/agent-teams.md
- LangGraph, multi-agent concepts (supervisor / network / hierarchical / custom) and agents guide (supervisor / swarm / handoffs).
- OpenAI Agents SDK, *Handoffs* and agents-as-tools. https://openai.github.io/openai-agents-python/handoffs/
- AutoGen, *SelectorGroupChat*; *Magentic-One* (Task Ledger / Progress Ledger).
- CrewAI process model: sequential vs hierarchical (role/task 原语)。
- 背景(不驱动 v1):Jiang et al., GTD, ACL 2026; AdaptOrch preprint; QueenBee Planner preprint.

本仓库对照:`relay/agent-core` 的 `Agent` / `AgentEvent` / `Tool` / `AgentConfig`;`docs/architecture.md` 的机制/策略分离与「Agent 只依赖 Provider」。
