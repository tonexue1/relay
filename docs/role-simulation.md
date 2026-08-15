# Relay · 角色模拟要哪种支撑

> 接拓扑 / memory 两篇。角色模拟是一种 **sample**,不是把 `orchestra` 的默认拓扑改成开会。
> 先分清你要的是单角色还是同场多角色——两者对 runtime 的要求几乎相反。

---

## 0. 先选哪一种

| 形态 | 用户看到什么 | 和调研型 Supervisor 的关系 |
|---|---|---|
| **A. 单角色陪聊** | 一个人格、一份关系史 | 用不上多 agent。现有 `Agent` 就够,缺的是**人设持久化** |
| **B. 同场多角色** | 几个角色轮流说话,共用一场戏 | 要用我们后置的 **Scene / 选 speaker**,不是 agent-as-tool |
| **C. 导演 + 演员** | 一个 GM 控场,演员对用户说话 | 像 Supervisor,但工人**长活、对用户可见**,不是回摘要就死 |

调研拓扑里 v1 做 Pipeline + Supervisor,是因为那个问题是「拆开、并行、综合」。角色模拟的问题是「谁在场、谁知道什么、谁下一句开口」。**不要用工人用完即弃的 Supervisor 硬套角色。**

---

## 1. 单角色(A):现有 core 够,缺四件业务件

已经有的:

- `AgentConfig.systemPrompt` — 能塞人设,但只是一根弦
- `AgentState.messages` — 关系史的真源
- `WindowTrim` — 会把早期人设互动裁掉,角色模拟里这是伤
- 云 / 端 `Provider` — 端侧 3B 反而适合:私密、常驻、不需要 tool

还缺的(都不必进 `agent-core` 内核,进 `samples/roleplay` 或以后的薄模块即可):

| 支撑 | 做什么 | 为什么现有不够 |
|---|---|---|
| **CharacterCard** | 姓名、口吻、禁忌、目标、与用户关系,和 `systemPrompt` 分开 | 人设被 trim / 被业务改 prompt 时会漂 |
| **Pin** | card + 最近 K 轮永不进 `WindowTrim` | trim 不写回,但投影会丢掉「我们怎么认识的」 |
| **Lore 按需注入** | 设定集当 artifact,`transformContext` 只抽本轮相关切片 | 整本圣经塞进 system 会撑死端侧窗口 |
| **Session 落盘** | 序列化 `messages` + card,进程杀了能续 | 现在 `Agent` 只活在堆上;角色模拟一次会话就是产品 |

这一档 **先做 sample,不要等 orchestra**。它也给以后的多角色打底:每个角色仍是一个长活 `Agent`。

端侧在这里是加分项,不是禁区。和「3B 不当 research lead」不矛盾:陪聊不分解任务,只续同一人格。

---

## 2. 同场多角色(B):要 Scene,不要共享一份 `AgentState`

一场戏需要两层记忆,和 memory 文的「禁止共享 transcript」看起来冲突——其实 scope 不同:

| 层 | 内容 | 谁看见 |
|---|---|---|
| **Scene** | 场上已说出的话(对用户可见的剧本) | 在场角色的投影都读它 |
| **Private** | 这个角色知道、别人不该知道的(秘密、内心目标) | 只进该角色的 `transformContext` |
| **Card** | 人格,跨场次稳定 | 只进该角色 |

实现上仍是 **N 个 `Agent` 实例**,不是一个 `Agent` 轮换 system prompt。轮换 prompt 会把秘密漏进下一人格,也会把口吻洗脏。

缺的 runtime 机制(这才是对 orchestra 的新要求):

| 支撑 | 作用 | 对应后置拓扑 |
|---|---|---|
| **Scene log** | 一份对用户可见的发言序列 `(speaker, text)` | GroupChat 的频道,但**不是**把每人完整 tool transcript 广播 |
| **Turn policy** | 谁下一句:用户随时可插话;NPC 用规则或小 selector | SelectorGroupChat / 导演指定,默认先 **round-robin + 用户抢话** |
| **投影,不是共享状态** | 每个角色: card + private + scene 的裁剪视图 → 才调用该 `Agent` | 禁止 `AgentState` 互指 |
| **发言事件** | `SpeakerStart/End`,playground 才能按角色气泡渲染 | 现有 `AgentEvent` 没有 speaker |

不要做的:

- 把整场 scene 复制进每个角色的 `messages` 当真源。真源是 Scene;角色 `messages` 只是投影缓存,或每次从 Scene 重建。
- 每个角色都跑一遍完整 tool loop。角色模拟默认 **无 tool 或极少 tool**(查自己的 lore)。`maxTurns` 应是 1:说完一句就停。
- LLM selector 当默认。多一次云调用选「谁说话」又贵又抢戏;2–4 人先规则。

Token:Scene 是一份,投影是 N 份。所以 **角色数要硬顶**(建议 ≤4),trim 裁 Scene 的远古对白,不裁 card。

---

## 3. 导演 + 演员(C):Supervisor 的「长活、对用户说话」变体

GM 更新世界账本(地点、时间、旗帜、胜负),指定下一个开口的演员。演员不回摘要给 GM 就死,而是 **对用户说一句**,GM 再记「发生了什么」。

| 调研 v1 Supervisor | 角色模拟要的 |
|---|---|
| worker 是 tool,返回后丢弃 | 演员长活,跨用户回合 |
| 隔离窗口,互不知情 | 演员必须看见 Scene(已公开的),不能看见别人的 private |
| lead 综合给用户 | 用户听的是演员,GM 默认不说话(或只旁白) |
| 并行工人 | 同场必须顺序开口,假并行是对的 |

世界账本 = memory 文里的 `TeamLedger`,只是字段换成 `place / time / flags`。大设定仍走 `ArtifactStore`。

这一档可以等 A 的 card/session 和 B 的 Scene 都有了再做。GM 用云、演员用端,是合理的端云叠法。

---

## 4. 和已拍板的 orchestra 怎么共存

不要改 v1 结论。角色模拟是 **第二条拓扑产品线**,机制上只多三个小口:

```
relay/orchestra
  Supervisor / Pipeline     ← 调研、派工(已建议)
  Scene (后置,角色模拟才拉)  ← 共享发言 log + turn policy
  Ledger + ArtifactStore    ← 两边共用;角色把 ledger 当成世界/关系账
```

`agent-core` 仍只懂单个 loop。角色不是新的 Agent 子类;是 **配置 + 投影 + 长活实例**。

UI 不要塞进现在的 playground 模块卡。这是独立 sample:`samples/roleplay`,`applicationId` 分开——和「每个用例自己的 APK」一致。

---

## 5. 最小支撑清单(按你要的形态勾)

**只做 A(建议先做,验证人设不漂):**

1. CharacterCard(数据类 + 写成 system 的稳定模板)
2. SessionStore(messages + card 落 `filesDir`)
3. Pin:card 与最近 K 轮不参与 trim
4. 端侧 Provider 当默认演员
5. sample APK 一页聊

**要做到 B,在 A 之上再加:**

6. Scene log + 硬顶人数
7. Turn policy(先规则,后 selector)
8. 按角色投影:card ∪ private ∪ scene
9. `Speaker*` 事件和按角色气泡
10. `maxTurns = 1` 的说话契约(说完就停)

**C 再加:** GM 写世界 ledger、指定 speaker、旁白通道。

**现在不必做:** 向量召回人设、情感模型、语音、Handoff 客服式转接、把角色模拟做进 `agent-core`。

---

## 6. 风险

| 若用错支撑 | 结果 |
|---|---|
| 用 Supervisor 派「角色工人」 | 每句都 new Agent,失忆;或回摘要,用户听不到角色 |
| 一个 Agent 换 systemPrompt 扮演多人 | 泄密、串味 |
| 共享一份 `AgentState` | trim 误删、秘密共享 |
| 不做 Pin 就上长聊 | 人设漂,看起来像模型不行 |
| 端上同时跑 4 个 3B 上下文 | 顺序预填充,体感像卡死;要排队 + 小窗口 |

---

若你点的是 **A**,下一步是 sample,不是 orchestra。若点的是 **B**,要把 Scene / turn policy 从「后置」里提前,并接受 v1 orchestra 变成「Supervisor + Scene」两条原语。
