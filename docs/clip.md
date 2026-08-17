# Clip · 选中课题 → 深挖 → 结论外挂到端上

> 求职 sample，不是 orchestra 陈列课，也不是 playground 模块调试台。
> APK：`samples/clip`（`relay.demo.clip`）。进线是适配器，**主线是云上研究 + 端侧落地**。

---

## 1. 产品（已锁）

用户交出一段字（选区 / 分享 / 剪贴板 / 手动，哪条通算哪条）。默认两个动作：

1. **改写**：短、端上、可停、有 TTFT 徽章。选区且宿主可写时可以写回。
2. **深挖**：把这段当课题，云上 Call（真检索），出短报告；报告收成本机**课题卡片**。以后相关追问端上垫这一张，不出网。

三分钟脚本：打/贴一句有争议的话 → 深挖 → 指结构格和来源 → 点停 → 再来一句短的走端上改写 → 打开刚装的卡片追问一句（端、不出网）。

**对 JD**：入口和写回是客户端；TTFT / 取消 / ANR 是端侧；深挖是云 + 已有编排；卡片是端上外挂知识，不是微调、不是向量库。

---

## 2. 明确不是

| 不做 | 原因 |
|---|---|
| `ROLE_ASSISTANT` / 长按 Home | 国内 ROM 碎，不是这条的门 |
| 无障碍看屏、代点 GUI | 另一类项目，面试像套壳 |
| embedding / 图谱 | `architecture.md` 已后置；卡片少时关键词更稳 |
| 把进线做进 `relay-*` | 选区覆盖是宿主的 `queries`，不是骨架 |
| 把 RoutingProvider 讲成 JD | 路由是 sample 策略，不是库的卖点 |
| 狼人杀 / 圆桌当首页 | orchestra 附录，不是这枪 |

进线失败（微信选区没有 Relay、不能复制）**可接受**。首页输入框就是课题。

---

## 3. 进线（S0，已过，不再加门）

全部收成 sample 内 `InboundText`（一段 `text` + 是否可写回）。不进 `relay-*`。

| 源 | 用户怎么交 | 写回 |
|---|---|---|
| 手动 | 首页输入框「用这段开始」 | 否 |
| 剪贴板 | 先复制，再点按钮（点了才读） | 否 |
| 分享 | `ACTION_SEND` text | 否 |
| 选区 | `PROCESS_TEXT`，宿主愿意 query 才出现 | 仅非 readonly |

本包 EditText 的选区已跑通。别的 App 出不来是宿主没交选区，不是接收端坏了。

---

## 4. 用现成的，不新造

| 能力 | 已经在哪 | Clip 怎么用 |
|---|---|---|
| `Provider` / 流式 / 取消 | `relay/llm` | 云 DeepSeek、端 `OnDeviceProvider` 同一接口 |
| 端侧加载 / TTFT | playground 端侧屏 + `relay/ondevice` | S1 抄加载/停/徽章，不要重写引擎 |
| Agent loop / tool | `relay/agent-core` | 深挖工人要检索时才上 Agent |
| `Pipeline` / `Supervisor` | `relay/orchestra` | S2 深挖用 **Supervisor**（Anthropic Research 那套）；Pipeline 不用 |
| 真检索 | clip `WebSearch`（有 `relay.bocha.apiKey` 走博查 Web Search；否则 Bing + Wikipedia） | S2 直接用，禁止 mock；深挖必须有博查 key |
| 卡片注入 | Agent 的 `transformContext` | S4 只垫**一张**选中的卡，不写回 `messages` |

策略（短→端、深挖→云、命中卡片→端）写在 clip 里，不进 `relay-llm`。

---

## 5. S2 · 抄 Anthropic Research（[工程文, 2025-06-13](https://www.anthropic.com/engineering/multi-agent-research-system)）

功效不是「多几个 Agent」，是 **把超出单窗口的广度问题切成互不看见的并行压缩器**。他们内部 eval：Opus lead + 并行 Sonnet 工人比单 Opus 高 90%；BrowseComp 方差里 token 用量约占 80%。所以架构要让人合法地多搜、多窗口，而不是写死三拍。

他们怎么分工（我们只抄这一层，不抄 Opus/Sonnet 分模型、200k Memory、彩虹发布、异步工人）：

```
课题
  → LeadResearcher 想策略、把 plan 写下（他们用 Memory；我们用 TeamLedger.plan）
  → 一次并行 spawn 若干 Subagent（3–5，按题复杂度缩放）
  → 每个 Subagent 自己搜、自己判断、压缩后把 findings 交回
  → Lead 看摘要，不够就再派一轮
  → 够了才交给 CitationAgent，用户看见的终稿带引用
```

子 agent **不知道彼此**，只拿自包含任务。长文不进 lead 窗口：工人写文件，lead 只拿引用（他们叫 filesystem；我们已有 `ArtifactStore` + `WorkerReturn`）。

| 他们的坑 | 我们怎么挡 |
|---|---|
| 简单题拉 50 个工人 | `TeamLedger(maxWorkers = 4)`；lead prompt 写死缩放：事实题 1 个工人、对比 2–3、禁止 >4 |
| brief 写成「research X」，两人搜同一件事 | lead 派工必须带：目标、输出格式、用哪些源、**不要碰的边界** |
| 查询又长又窄，0 命中 | scout prompt：先短宽查询，看 SERP 再收窄；一次并行 2–3 个 `web_search` |
| 工人原文把 lead 撑爆（电话游戏） | 已有：超 400 字进 artifact，lead 只见 JSON + ref |
| 没完没了搜 | scout `maxTurns = 6`；够 3 条可靠 URL 就停 |
| lead 自己去搜，并行白做 | **lead 没有 `web_search`**，tool 只有工人 |

Clip 角色（全是云 DeepSeek，S2 先同一模型；分大小模型后置）：

| 角色 | 有什么 tool | 干什么 | 用户听见？ |
|---|---|---|---|
| **lead** | 只有 `scout`（`AgentTool`） | 拆独立子问题、写 plan、派 1–3 个、综合、决定还要不要再派 | **是**，终稿是它的 |
| **scout** | `web_search` / `fetch_url` | 只执行这一条 brief；宽搜 → 打开 1–2 页 → 压缩 findings + URL | 否，屏上只显示工人卡 |
| **cite**（S2 可后做） | 无检索 | 按已有 URL 给结论钉引用 | 否；没有它时 lead 终稿自己带 URL |

`AgentConfig.toolExecution = Parallel`：lead 一次多个 `scout`、scout 一次多个 search。Call 仍同步等齐。

屏上要能看出 Supervisor：主编先动 → 工人卡（任务一句，互不可见）→ 终稿是主编的。徽章云。取消停整个 `prompt`。不写卡片（S3）。

### API 缺口（S2 碰到的，没假装没有）

| 缺口 | 我们怎么处理 | 不该假装成 |
|---|---|---|
| 同一 `scout` 并行 Call，artifact 名曾是 `$workerId/output`，会互盖 | **已修**：`$workerId/$toolCallId`。单测 `parallelCallsToSameWorkerWriteDistinctArtifacts` | sample 里注册 scout_1/2/3 三个假角色 |
| `WorkerSpec.maxTurns` 没有任何调用方读取 | spawn 闭包里写 `AgentConfig.maxTurns` | 以为 WorkerSpec 会限制工人 |
| `Supervisor` 从不写 `TeamLedger.plan` | S2 不依赖 ledger.plan；计划在主编第一轮话里 | 以为有 Memory |
| lead 只拿到 `WorkerReturn` JSON（3 行摘要 + ref），**没有**读 artifact 的 tool | sample 在 `spawnLead` 里额外挂 `read_artifact`（`ArtifactStore.get` 已有） | 改 AgentTool 把全文塞回 lead |
| 不能运行时 new 一个 WorkerSpec | 反复 Call 同一个 `scout`，靠 brief 区分 | 动态 spawn API（后置） |
| 没有 CitationAgent 生命周期 | S2 让主编终稿自带 URL；cite 工人后置 | 第三拍 Pipeline |

---

## 6. Spike（按这个顺序，过了再并）

| | 验什么 | 状态 | 过线 |
|---|---|---|---|
| S0 | 一段字进得来；选区可写回 | **已过** | 本包输入框 / 分享 / 剪贴板 / 手动四条进同一屏 |
| 检索原子 | 博查 JSON + Bing 解析 + 联网 `searchHits` + clip 检索页 | **已过**（代码在 clip） | 有博查 key 时来源显示 Bocha；`WebSearchTest` 绿 |
| S1 | 端上改写 + 取消 + TTFT | **已过**（0.5B，不上云） | 徽章「端」、能停、TTFT 有数 |
| S2 | 云深挖：`Supervisor` + 真检索 | **代码已接** | 先有主编 turn，再有工人卡，用户读到的是主编的话；来源是真 URL；能停 |
| S3 | 报告 → `filesDir` 课题卡片，列表能删 | 未做 | 杀进程还在。JSON：课题、结论、关键词、来源 |
| S4 | 打开一张卡，端上追问，`transformContext` 只垫这一张 | 未做 | 不出网；卸卡后再问应变差或拒答 |
| S5 | 才拼路由 | 未做 | 短→S1；点「深挖」→S2→S3；已有卡且关键词命中可走 S4 |

**下一步是真机点通 S2。** 课题屏「深挖」走 `Supervisor`：lead 派 `scout`，`web_search` 调博查（`local.properties` 的 `relay.bocha.apiKey` 或屏上粘贴），用户读主编终稿。过了再 S3 卡片。

S1 已挂在课题屏：「改正式一点」走 `OnDeviceProvider`（Qwen2.5 0.5B）。下载/加载/取消/TTFT 抄 playground，端挂了就失败。

---

## 7. 卡片长什么样（S3 才实现）

本机 JSON，人能看见、能卸、能过期。不是记忆魔法。

```
topic, conclusions[], disputed[], sources[{title, url}], keywords[], installedAt
```

匹配先用标题/关键词/时间。包多到搜不过来再谈 `EmbeddingProvider`。

---

## 8. 附录（别混进这个 APK）

- `samples/playground`：模块自测（llm / ondevice / agent / orchestra）
- `samples/werewolf`：Director + Yield
- `docs/sample-ideas.md`：七个具名拓扑陈列课（尚未做 `orchestra-lab`）

Clip 只讲这一条产品圈。拓扑名字只在深挖结构格上露一次，不把 Call/Yield 写进用户文案。
