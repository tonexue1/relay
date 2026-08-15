# Relay · 主流拓扑实现 + 陈列样例

> 纠正:sample 练的是 **拓扑**,不是 search/notes 这种原子能力。
> orchestra **对外实现主流多 agent 拓扑**(具名类型);Call / Yield 是内部原语,不出现在 sample 菜单上。
> 公共工具库仍然后置。样例里的 tool 只是让拓扑能跑起来的道具。

---

## 1. orchestra 对外长什么样

内部两格不变。公开 API 是还原表里那些名字,每个都是可 `run` / `prompt` 的编排:

| 具名拓扑 | 内部 | 用户听见 | 必须能从 UI 一眼看出来 |
|---|---|---|---|
| `Pipeline` | 写死顺序的 Call | 最后一步或外层转述 | 一条链,A 完才 B,从不并行 |
| `Supervisor` | lead 的 tool = Call | lead | lead 先说话/想,再派工人,再综合 |
| `Hierarchy` | Call 嵌套 Call | 最外层 | 树:总编 → 组长 → 工人,两层派工 |
| `MapReduce` | 一次 N 个同构 Call,等齐再收 | 调用方 | 扇出同时转,再收成一份 |
| `Swarm` | Yield,当前人指定下一个 | 被交到的人 | 顶栏接待员切换,没有「综合」 |
| `GroupChat` | Yield,selector 读 Scene 选人 | 被选中的人 | 圆桌,全员同一频道,高亮下一句谁说 |
| `Director` | Yield,GM 选人 | 演员(GM 可旁白) | GM 不跟用户聊正事,只点名 |

不做进 v1 公开面:`Network`(Swarm 候选=全体)、`MagenticOne`(Supervisor + stall 重规划,可当 Supervisor 的策略开关)、`Mailbox`。

Call / Yield / `AgentTool` / `Stage` **不出现在 sample 首页**。首页是上面七个名字。

---

## 2. 样例形态

一个陈列馆 APK:`samples/orchestra-lab`。首页七张拓扑卡,一点进一个屏——和 playground 按模块分屏同一套路,只是对象换成拓扑。

不拆七个 `applicationId`:这不是七个产品,是七堂对照课。哪个拓扑以后长成产品(简报、围炉),再拆出去。

每屏三块固定布局,避免又做成「一个大聊天框」:

1. **结构图**(当前是链 / 树 / 扇出 / 圆桌 / 接待员)。
2. **事件带**(CallStarted / Utterance / Handoff,证明边在动)。
3. **对用户可见的话**(只有「用户该听见的那张嘴」)。

道具 tool 用假数据即可(`echo`、内存 notes、写死的三份素材)。重点是边,不是联网。

---

## 3. 一拓扑一课(故事只为让边可见)

### Pipeline · 三拍成稿

用户丢一句主题。固定三步,每步一个即弃 Agent:**搜集 → 起草 → 起标题**。屏上是三格进度条,第二格在第一格变绿之前不许亮。

看什么:没有 lead 在「选择」;边是写死的。

### Supervisor · 主编派工

用户丢一个问题。主编(lead)只有三个工人 tool:`scout` / `numbers` / `clippings`。它可以派一个或一次派两个,然后自己写给用户。

看什么:先有主编的 turn,再有工人卡片,最后用户读到的是主编的话,不是工人原文。

### Hierarchy · 总编 → 组长 → 记者

用户要「一组对比」。总编只 Call **调研组长**;组长再 Call 两个记者。记者互不可见。

看什么:事件带是两层缩进,不是平铺的三个工人。这是嵌套 Call,不是多注册几个 tool 的 Supervisor。

### MapReduce · 四则同时摘

用户贴四段短材料(或点「用示例四则」)。四个同构工人同时摘一句,reduce 合成四行对照。

看什么:四张卡片一起转,结束才出一份表。和 Supervisor 的差别:工人同构、切分是数据不是角色、没有主编在中途改派。

### Swarm · 前台转接

用户进「店」。默认 **前台**跟你聊;它判断是账单还是退货,handoff 给 **账房** 或 **售后**。被交到的人接着跟用户说话,前台退出。

看什么:顶栏名字换了;没有人把三方意见摘要给你。这是 Yield 的交接,不是 Call。

### GroupChat · 三人圆桌

用户出题。三位专家(立场写死)共用 Scene,selector(先规则:不能连说两句;以后可换 LLM)点下一位。用户随时插话。

看什么:同一条时间线,气泡带名字,高亮「下一个」。和 Swarm 的差别:没有人离开,全员一直在场。和 Supervisor 的差别:没有综合者对用户代言。

### Director · 点名演戏

一场短戏:店主、伙计、用户。**导演**只输出 `next=伙计` 或一句旁白,不跟用户谈生意。演员 `maxTurns=1`。

看什么:导演通道和演员通道分开。和 GroupChat 的差别:选人的那个默认不对用户说正事。和 Swarm 的差别:不是当前演员自己决定交谁,是场外 GM。

---

## 4. 和内部原语的对照(给实现的人)

```
课                公开类型       内部
三拍成稿          Pipeline       Call 顺序
主编派工          Supervisor     Call + lead Agent
总编→组长→记者    Hierarchy      Supervisor(worker=Supervisor)
四则同时摘        MapReduce      并行 Call + 一个 reduce Agent
前台转接          Swarm          Yield + HandoffPolicy
三人圆桌          GroupChat      Yield + SelectorPolicy
点名演戏          Director       Yield + DirectorPolicy
```

七课做完,等于对外宣布:主流拓扑我们是 **实现了**,不是只留了两格原语让业务自己拼。

---

## 5. 工具(仍然后置)

lab 里允许的道具:

- 内存 notes / 写死的四则素材 / `echo` / 简单计算器。
- Swarm / Director 的「人设」是 systemPrompt,不是 tool。

不要在 lab 里先做联网搜索、日历、分享。那是原子能力,会把课带跑。以后 `relay/tools-*` 另开,跟拓扑陈列无关。

---

## 6. 开场顺序

1. orchestra 先把 **具名类型** 立起来(`Pipeline` / `Supervisor` / …),Call/Yield 当包内实现。
2. lab 按表从上到下:先四条 Call 课(Pipeline → Supervisor → Hierarchy → MapReduce),再三条 Yield 课(Swarm → GroupChat → Director)。
3. 每一课的验收是「结构图 + 事件带能和上表对上」,不是回答质量。
