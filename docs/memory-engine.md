# Relay · 记忆引擎(非 LLM)

> 状态:设计备忘(2026-08-18)。不是实现 spec。
> 问题:不含模型的记忆引擎怎么存、怎么合、怎么查、怎么忘。
> 抽取在云。端侧小模型不在本设计视野。v1 只做个人助手;`novel:{id}` 分仓已通手抽入库(林晚十回,一章一存),云端抽取未接。存储是 SQLite + FTS5。Orchestra 工作记忆见 [multi-agent-memory.md](./multi-agent-memory.md)。

---

## 0. 结论先行

**引擎里没有 LLM。** 它不理解中文、不抽三元组、不摘要。它只做四件确定性的事:

```
capture  原文落盘 + 记 raw_event
ingest   把外面送来的三元组幂等写进图
query    FTS5 + 一跳邻居,吐出短事实列表
forget   剪边、不剪日志
```

存储很无聊:**SQLite + FTS5 + filesDir**。JVM 单测和 playground 共用同一套 schema。新意在入库纪律。

核心一句:**`raw_event` = 对话真相(只追加);`fact_log` = 已采纳事实(只追加);`node/edge` = 可丢弃的物化视图。**

三层日志解决三件事:

- 聊天不卡:只 `capture`,不抽。
- 可重抽:抽错了 / 换抽取模型,拿 `raw_event` 再送云端,`DROP` 图重 `ingest`。
- 可离线重建:图坏了,不连网,重放 `fact_log` 就能长回同样的图。

云端 Extractor 是引擎的**插件**,不是引擎的一部分。`MemoryStore` 接口里不准出现 `Provider`。

引擎为两个应用服务,共用一套存储,图必须硬隔离:

| 应用 | 图里是什么 | 召回要什么 |
|---|---|---|
| 个人手机助手 | 用户档案(过敏、住址、家人…) | 本轮话 → 相关短事实 |
| 短篇连载(~50 章) | 故事圣经(人物、地点、伏笔、谁知道什么) | 本章场景 → 在场人物卡 + 未收束旗帜 + 近章摘要 |

两张图**禁止 JOIN**。助手的花生过敏不得进小说 prompt;小说角色不得进晨报。这是硬 ACL,不是约定。

---

## 1. 边界

```
  Agent 每轮结束
       │ capture(turn)          ← 无模型,毫秒
       ▼
  raw_event(consumed=false)
       │
       │ 慢路径(用户点整理 / WorkManager)
       ▼
  CloudExtract(片段) → TripleDraft[]   ← 唯一碰 LLM 的地方,在引擎外
       │ ingest(drafts)
       ▼
  规范化 → 对节点 → upsert 边 → provenance → FTS
       │
       │ 下次 prompt 前
       ▼
  query(user text) → bullets → transformContext  ← 无模型
```

引擎**拒绝**做的:

- 调任何 `Provider`(抽取在引擎外的 CloudExtract)
- 向量检索(S3 之后才作为插件)
- 自动摘要 `AgentState.messages`(trim 不写回,orchestra 也不碰)
- 跨 `graph_id` 检索或合并节点

---

## 1.1 两个应用,一个引擎

每行 `raw_event` / `fact_log` / `node` / `edge` 带 `graph_id`。`query` / `ingest` / 云端抽取必带,缺省拒绝。

```
graph_id = "assistant"           终身一张用户图
graph_id = "novel:{bookId}"      一本书一张图,50 章共用,换书换 id
```

### 助手

谓语用 §4.0 那 30 条。召回 = FTS 节点 + 一跳边,垫进 `transformContext`。敏感边默认 `scope=private`,上云抽取另开 `cloud_ok`。

### 小说(~50 章)

50 章原文塞不进一次生成窗,也不该塞。图不是「把小说存进去」,是 **连续性账本**:

| 记什么 | 不记什么 |
|---|---|
| 人物卡:姓名、身份、口吻、秘密、与他人关系 | 整章对白 |
| 地点、物件归属(`owns` / 谁拿着刀) | 修辞、气氛 |
| 未收束伏笔、已死/已离场 | 已写完且不再出场的细节 |
| 每章 1 条摘要(artifact,按章号) | 把 50 条摘要一次全垫进去 |

小说谓语是**另一份闭集**,不要复用过敏/饮食。起步够用:

| p | 中文 | 例子 |
|---|---|---|
| `is_a` | 是 | 林晚 → 捕快 |
| `named` | 名叫 | (别名) |
| `located_in` | 位于 / 身处 | 林晚 → 码头 |
| `knows` | 知道 | 林晚 → 账本秘密 |
| `wants` | 想要 | 林晚 → 翻案 |
| `has_item` | 持有 | 林晚 → 腰牌 |
| `related_to` | 关系是 | 林晚 → 师父(o 用短角色名,关系写节点摘要或以后加边属性) |
| `status` | 状态 | 王二 → 已死 |
| `foreshadow` | 伏笔 | 账本 → 未收束 |
| `appears_in` | 出场于 | 林晚 → 第12章 |

`related_to` 太粗,角色关系一多再拆 `mentor_of` / `rival_of` / `spouse_of`(小说里的配偶 ≠ 用户配偶:靠 `graph_id` 隔开,p 可以同名)。

**写一章时的召回**(仍无模型):

1. Pin:书级 logline + 本章目标(短,永不 trim)
2. 本章草稿/大纲里的专名 → FTS 命中人物/地点 → 一跳边(秘密、持有、状态)
3. `appears_in` 近 2–3 章的人物优先;已 `status=已死` 且本章未点名则不注入
4. 上一章摘要 artifact 1 条(不是 50 条)
5. `foreshadow` 且未收束的,限 K 条

50 章能写通,靠的是 **圣经短、摘要滚动、图管连续性**;不是加长窗口。

角色模拟文里的 Card / Lore / Scene 对应这里:`Card` = 人物节点+边,`Lore` = 本书 `graph_id` 的图,`Scene` 仍是 orchestra/sample 的场上对白,不进 `MemoryStore`。

---

## 2. 两速写入

### 2.1 快路径 `capture`(同步)

1. 正文写入 `ArtifactStore`(filesDir,内容寻址 = hash,去重 + 当 `text_ref`)。
2. `INSERT raw_event(..., consumed=0)`。

不解析、不抽、不更新图。

### 2.2 慢路径 `ingest`(引擎外先抽)

调用方(WorkManager / 一次「整理」)负责:

1. `SELECT * FROM raw_event WHERE consumed=0`。上云抽取用 `principal=extractor`,只看见 `cloud_ok`;默认 capture 是 `private`,playground 要先「允许上云」再 `markScope`。
2. 拼片段(可按 session,上限字符)送到云 Extractor
3. 得到 `TripleDraft(s, p, o, raw_event_ids, confidence?)`
4. `store.ingest(drafts)` —— **此后全是 SQL**
5. 标 `consumed=1`

`ingest` 内部没有网络。网络失败 = 不 ingest、不标 consumed,下次重试。

---

## 3. Schema

全在一个 SQLite。图不是图数据库,就是两张表。

```sql
raw_event(
  id, graph_id, ts, session_id, role, text_ref, source, consumed, scope
)

fact_log(
  id, graph_id, ts, s, p, o, confidence, raw_event_ids
)

node(
  id, graph_id, type, canonical_name, summary, confidence, updated_at
)
-- UNIQUE(graph_id, alias)
node_alias(graph_id, node_id, alias)
edge(
  id, graph_id, src, dst, relation, confidence,
  valid_from, valid_to, updated_at, scope
)
provenance(edge_id, raw_event_id)

pending_review(edge_id, reason, confidence)

CREATE VIRTUAL TABLE node_fts
  USING fts5(canonical_name, summary, content='node');
```

`node.type` day 1 只用:`person | place | thing | pet | org | other`。用户节点固定 id = `user`(canonical_name=`用户`)。

边的幂等键:`(src, relation, dst)`。同一键再来 = 升 confidence、加 provenance、刷新 `updated_at`,不插第二行。

---

## 4. 无模型算法(这才是引擎)

### 4.0 谓语闭集

词表以本表、`relay/memory-api` 的 `PREDICATES`、以及回归话剧 `AssistantPlay` 为准。新口语先加进话剧和 `PlayEvolutionTest`，再决定要不要加谓语。

| p | 中文 | 基数 |
|---|---|---|
| `allergic_to` | 过敏 | 集合 |
| `likes` | 喜欢 | 集合 |
| `dislikes` | 不喜欢 | 集合 |
| `prefers` | 更倾向 | 集合 |
| `diet` | 饮食 | 功能 |
| `lives_in` | 住在 | 功能 |
| `work_location` | 办公地 | 功能 |
| `born_in` | 出生于 | 功能 |
| `works_at` | 就职于 | 功能 |
| `works_as` | 职位是 | 功能 |
| `alumni_of` | 毕业于 | 集合 |
| `member_of` | 属于 | 集合 |
| `skilled_in` | 擅长 | 集合 |
| `knows_language` | 会说 | 集合 |
| `colleague_of` | 同事是 | 集合 |
| `friend_of` | 朋友是 | 集合 |
| `family_of` | 家人是 | 集合 |
| `spouse_of` | 配偶是 | 功能 |
| `parent_of` | 子女是 | 集合 |
| `child_of` | 父母是 | 集合 |
| `sibling_of` | 兄弟姐妹是 | 集合 |
| `has_pet` | 养宠物 | 集合 |
| `named` | 名叫 | 集合 |
| `owns` | 拥有 | 集合 |
| `takes` | 在服用 | 集合 |
| `attends` | 参加 | 集合 |
| `plans` | 打算 | 集合 |
| `has_task` | 待办 | 集合 |
| `work_years` | 工龄 | 功能 |
| `located_in` | 位于 | 集合 |

功能边:同一 `src+p` 来了不同 `o` → 旧边 `valid_to`,新边 supersede。集合边可并存。`family_of` 仅在说不清亲疏时用;能分清则用 `child_of` / `parent_of` / `spouse_of` / `sibling_of`。

### 4.1 规范化(ingest 第一步)

对 `s` / `o` 做和评测台相同的纪律,全部代码:

- NFKC、去空白、小写拉丁
- 别名表(花生酱→花生,离职→跳槽,美式咖啡→美式)
- 谓语必须在闭集(见 §4.0);否则丢进 `pending_review` 或直接丢
- 主语 `助理` 丢弃;`named` 只允许宠物类型节点

### 4.2 对节点

`resolveNode(name, typeHint)`:

1. `node_alias.alias` 精确命中 → 该 node
2. `node.canonical_name` 精确命中
3. FTS5 `MATCH` 同 type、limit 5;若唯一命中且规范化后编辑距离很小(或完全包含)→ 合并,写入 alias
4. 否则 `INSERT node`

day 1 **不用 embedding**。对不上就新节点,宁可碎,靠 alias 以后合。S3 再决定要不要向量。

`用户` / `user` 永不新建第二条。

### 4.3 连线 / 矛盾(纯图约束)

ingest 一条边之后跑规则,不跑模型:

- 同一 `src+relation`、`dst` 不同且两 dst 未合并 → `pending_review(contradiction)`(例如两个 `lives_in`)
- `valid_to IS NULL` 的旧边与新边冲突 → 旧边打 `valid_to=now`,新边 `supersedes`,进晨报
- `allergic_to X` 与 `likes/prefers X` 同 src → 丢掉 taste 边(评测台已验证这条规则)

传递闭包**不做**。多跳在 query 时现走 1 hop,够 L2 起步。

### 4.4 查询 `query`

输入:本轮用户话(或小说本章大纲);输出:≤K 条短事实(给 `transformContext` 垫到投影头部)。

1. 用简单切词(空白 + 连续 CJK 二元)拼 FTS5 `MATCH`;节点检索只走 FTS,不用 `instr` 回退。闭集谓语的中文提示(过敏、工龄…)仍可直接命中边。
2. 命中 node → `SELECT` 其作为 src 或 dst、且 `valid_to IS NULL` 的边
3. 按 `confidence * recency` 排序,截断到字符预算(默认约 2k 字,避免整图倾倒;不按端侧窗卡)
4. 渲染成 `- 用户 allergic_to 花生` 这种行,不要段落

没有命中就返回空。**禁止**「没找到就让模型编」。

### 4.5 遗忘 `forget`

定时或每次 ingest 末尾:

- 剪 `confidence` 低、长期无 provenance 新引用、`updated_at` 旧的**边**
- `raw_event` / `fact_log` 不删(可冷存)
- 图忘了用 `fact_log` 重建;事实错了用 `raw_event` 重抽

---

## 5. 接口(纯 Kotlin,无 Provider)

```
relay/memory-api       MemoryStore / SqliteMemoryStore(SQLite + FTS5)
samples/playground     同一套 schema,Android SQLiteOpenHelper 落 filesDir
```

```kotlin
interface MemoryStore {
    suspend fun capture(turn: RawTurn): String          // 必带 graphId
    suspend fun ingest(drafts: List<TripleDraft>)
    suspend fun query(
        graphId: String,
        text: String,
        budgetChars: Int = 2000,
        principal: String = "user",
    ): MemoryHit
    suspend fun forget(graphId: String, now: Long = ...)
    suspend fun pendingReview(graphId: String): List<ReviewItem>
    suspend fun resolveReview(graphId: String, edgeId: String, accept: Boolean)
    suspend fun rebuildFromFactLog(graphId: String)
}

data class TripleDraft(
    val graphId: String,
    val s: String, val p: String, val o: String,
    val rawEventIds: List<String>,
    val confidence: Double = 0.7,
)
```

`graphId` 是硬 ACL 的第一闸。FTS 命中后回表读边,`WHERE graph_id = ?`;跨图 ID 当未命中。`principal` 第二闸(助手上云抽取 / 以后家庭共享);小说 day 1 只有 `author`。

### 5.1 挂到 Agent(不改 agent-core)

`Agent` 不接收 `MemoryStore`。挂点只有两个现成钩子,和 GroupChat 垫 Scene 的方式一样。

**坑:** `Agent.withSystem` 会丢掉 `transformContext` 产出的所有 `Role.SYSTEM`,只保留 `state.systemPrompt`。事实**不能**做成 system 消息。产品路径学 `projectIntoContext`:垫一条**不写回 transcript** 的 `Message.user`。

读(每次 LLM 调用前,含 tool 循环):

```kotlin
fun MemoryStore.remembering(
    graphId: String,
    trim: suspend (List<Message>) -> List<Message>,
    pin: String = "",           // 小说: logline+本章目标; 助手可空
): suspend (List<Message>) -> List<Message> = { msgs ->
    val q = msgs.lastOrNull { it.role == Role.USER }?.content.orEmpty()
    val bullets = query(graphId, q).render()
    val prefix = buildString {
        if (pin.isNotBlank()) append(pin).append('\n')
        if (bullets.isNotBlank()) append("已知事实:\n").append(bullets)
    }
    val injected = if (prefix.isBlank()) emptyList() else listOf(Message.user(prefix))
    injected + trim(msgs)
}

val agent = Agent(
    provider, config, tools,
    transformContext = store.remembering("assistant", WindowTrim(...)),
)
```

写(Agent 没有 after-turn 回调,包一层 `prompt`):

```kotlin
fun Agent.promptAndCapture(store: MemoryStore, graphId: String, input: String) = flow {
    store.capture(RawTurn(graphId, role = "user", text = input))
    var assistant = ""
    prompt(input).collect { event ->
        emit(event)
        if (event is AgentEvent.MessageEnd && event.message.role == Role.ASSISTANT) {
            assistant = event.message.content.orEmpty()
        }
    }
    if (assistant.isNotBlank()) {
        store.capture(RawTurn(graphId, role = "assistant", text = assistant))
    }
}
```

慢路径不挂在 Agent 上:WorkManager /「整理」按钮读未 consumed 的 `raw_event`,云端抽,再 `ingest`。下次 `prompt` 的 `query` 自然能命中。

**两个应用只换 `graphId` 和 `pin`**,同一个 Agent 类:

| | 助手 | 小说第 N 章 |
|---|---|---|
| `graphId` | `"assistant"` | `"novel:$bookId"` |
| `pin` | 空或人设一句 | logline + 本章目标 |
| `query` 的 text | 用户这句 | 本章大纲/草稿专名(可另传入,不必是 last user) |
| 工人 Agent | **不挂**用户图 | **不挂**圣经;圣经只给写章的那个 Agent |

不要做成 tool(`remember` / `recall`)。召回是硬闸(`graph_id` / scope),必须在进模型之前由代码完成,不能让模型自己挑看哪张图。

---

## 6. 向量(后置,不是引擎本体)

S3 证明 FTS5 不够再用。接口留 `EmbeddingProvider`,实现另说。没它时 `query` / `resolveNode` 仍然完整。

---

## 7. 一句话

**非 LLM 记忆引擎 = 按 `graph_id` 隔离的两本账 + 可重建的 SQL 图 + FTS。** 助手一张用户图,每本小说一张圣经图;聪明在抽取和入库规则里,引擎只做哈希、别名、唯一键、JOIN 和硬闸。
