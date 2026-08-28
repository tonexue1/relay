# 主流 Agent 记忆框架：存储与召回原理

> 附录，不是实施 spec。目标设计从 [README.md](./README.md) 读起。  
> 调研日期：2026-08-26。对象：Mem0、Zep / Graphiti、Letta、LangMem、Supermemory、Cognee。

---

## 0. 结论先行

当前没有哪一家只靠一种数据库或一种检索方式解决记忆问题。代表性实现逐渐收敛到四层：

```text
原始事件 / 对话
        ↓
可直接使用的 Profile / Core Memory
        ↓
可检索的 Claim / Episode / Memory Document
        ↓
可选的实体关系图与时间模型
```

召回则普遍采用：

```text
当前问题 + 最近对话 + 当前任务
        ↓
用户 / Agent / Session / Task / 时间过滤
        ↓
关键词、向量、结构化字段、图遍历等多路候选
        ↓
融合排序（RRF 或加权融合）
        ↓
可选 Reranker
        ↓
去重、时效、范围和上下文预算控制
        ↓
注入 Prompt 或作为 Tool Result 返回
```

最重要的行业经验不是“用了向量库”或“用了知识图谱”，而是：

1. **关键记忆与按需记忆分层**：姓名、身份、过敏、长期规则等不能完全依赖概率召回。
2. **语义入口与结构扩展分工**：向量/关键词负责找到入口，图负责扩展关系，不让图独自承担自然语言理解。
3. **先扩大候选，再提高精度**：召回和重排是两件事；Reranker 无法救回根本没进入候选池的记忆。
4. **时间和 Scope 是硬约束**：相关但属于其他用户、任务、会话或已失效的记忆，仍然是错误记忆。
5. **存储不等于可用上下文**：最终还需要一个 Context Builder 在 token/字符预算内选择、组织和标注来源。

---

# 第一部分：总体原理

## 1. 记忆存储的通用逻辑模型

### 1.1 Raw Event：不可损的原始来源

保存原始消息、文档、工具结果或业务事件，主要用途是：

- 审计和 provenance；
- 重新抽取或迁移索引；
- 搜索“当时原话”；
- 在结构化记忆错误时回查来源。

Raw Event 通常不是默认 Prompt 的一部分，也不适合直接全量检索后注入。它更像事实日志或 source of evidence。

典型实现：

- Graphiti 的 `EpisodicNode`；
- Letta 的完整 conversation history；
- LangGraph Checkpointer 中的 thread state 和消息；
- Supermemory 的 document chunks；
- Relay 的 `raw_event`。

### 1.2 Profile / Core Memory：常驻或直接读取

Profile 是少量、高价值、频繁使用的稳定信息，例如：

- 用户身份和称呼；
- 长期偏好与禁忌；
- Agent 身份、行为规则；
- 当前项目或任务的核心约束。

这类记忆通常有严格 schema，并通过 key 直接读取或每轮常驻上下文，而不是依赖 top-k 检索。

原因很简单：如果“用户对花生过敏”必须等用户问题和该事实的 embedding 足够相似才出现，系统就无法保证安全性。

代表路线：

- Letta：`system/*.md` 每轮编译进 system prompt；
- LangMem：Profile 作为一个结构化 JSON 文档直接 `get`；
- Mem0 / Supermemory：可通过 metadata/category 固定查询，但是否常驻由应用决定；
- Graphiti：更偏检索型图，没有内建的常驻 Profile 层。

### 1.3 Memory Document：语义召回的主要载体

Memory Document 是可独立检索的文本单元，可以是：

- 一条事实描述；
- 一个 Claim；
- 一段 Episode 摘要；
- 一次成功经验；
- 一个文档 chunk；
- 一条行为规则。

常见字段：

```text
id
content
embedding
user_id / agent_id / session_id / task_id
type / category
created_at / updated_at
valid_at / invalid_at
confidence
source_ids / provenance
metadata
```

它是向量检索、BM25 和 Reranker 最容易消费的形式。相比单独保存三元组，完整文本保留了更多语义，也更适合作为自然语言召回入口。

### 1.4 Graph：实体、关系和时间

图层通常保存：

- Entity Node：用户、人物、地点、项目、产品、概念；
- Fact Edge：实体之间的关系和自然语言事实；
- Episode → Entity / Fact 的来源关联；
- Community：实体簇或区域摘要；
- 有效时间、失效时间和系统写入时间。

图的优势：

- 从已命中实体扩展一跳或多跳；
- 回答明确的关系问题；
- 表达事实变化和冲突；
- 保留结构化 provenance；
- 支持按实体、关系类型和时间过滤。

图的弱点：

- 用户问题未出现图中实体时，很难获得遍历起点；
- 同义词、隐含意图和跨领域联想仍需语义检索；
- 大图上的在线遍历、向量查询和多租户隔离成本较高。

因此主流实现更接近“向量/关键词找到入口，图补充上下文”，而不是“所有问题先跑图查询”。

### 1.5 索引与真相源不是一回事

一个系统可能同时使用：

- SQL：事实、metadata、版本和审计；
- Vector DB：embedding 与 ANN；
- FTS/BM25：精确词和专有名词；
- Graph DB：实体与关系；
- Object/File Store：原始文档和大对象。

这些是物理部署选择。逻辑上更重要的是明确：

- 哪个存储是真相源；
- 哪些只是可重建索引；
- 删除、过期和修改如何传播；
- 搜索结果怎样回到来源。

---

## 2. 召回的通用流水线

### 2.1 Query Construction：不能只看最后一句

最简单的系统直接把最后一条用户消息送去 embedding。更完整的实现会组合：

- 当前用户问题；
- 最近若干轮对话；
- 当前 task/session；
- Agent 当前目标；
- 已识别实体；
- 时间意图；
- 多个 query rewrite。

Query rewrite 的价值是把表面问题转成记忆语言。例如：

```text
原问题：今晚吃火锅行吗？

可能的记忆查询：
- 用户饮食禁忌
- 用户过敏信息
- 火锅相关历史经历
- 最近饮食计划
```

但 Query rewrite 不是免费的真理生成器。它扩大召回面，也可能引入错误意图，因此仍需范围过滤和后续重排。

### 2.2 Pre-filter：先约束搜索空间

常见硬过滤字段：

- `user_id` / tenant；
- `agent_id`；
- `session_id`；
- `task_id` / namespace；
- memory type；
- category/tag；
- created/valid/invalid 时间；
- confirmed/candidate 状态；
- expiration。

过滤既是质量机制，也是数据隔离机制。把其他任务或用户的高相似结果召回，不能通过 Reranker 补救。

### 2.3 Candidate Generation：多路生成候选

### 关键词 / BM25

适合：

- 人名、项目名、订单号；
- 代码符号、产品型号；
- 用户原话；
- embedding 容易混淆的短文本。

问题：

- 无法自然处理同义表达；
- 中文依赖合适的分词或 n-gram；
- 词面重叠可能产生误召回。

### 向量检索

适合：

- 同义改写；
- 主题相近的经历；
- 自然语言事实和 Episode；
- query 与记忆没有完全相同词面的场景。

问题：

- “相关”不等于“可用于回答”；
- 短事实 embedding 不稳定；
- 专有名词、否定和数字可能表现较差；
- 隐含的安全关系未必有足够语义距离优势；
- threshold 和 top-k 对效果非常敏感。

### 结构化直接读取

对 Profile 和明确字段，直接按 key/schema 读取通常比搜索更可靠。例如：

```text
profile/{userId}/dietary_constraints
profile/{userId}/preferred_language
agent/{agentId}/rules
```

### 图候选与遍历

常见方式：

1. 关键词或向量命中实体；
2. 以实体为中心扩展事实边；
3. 按关系类型、方向、时间和深度过滤；
4. 将边的自然语言事实送入融合或重排。

图遍历适合增加“结构相关性”，但不应无界扩展，否则热门节点会带来大量噪声。

### 最近事件

部分系统会单独加入：

- 最近写入；
- 最近访问；
- 当前会话；
- 当前任务；
- 最近被修改或失效的事实。

这是为了避免纯语义排序忽略刚刚发生但文本不够相似的事件。

### 2.4 Fusion：合并不同检索通道

不同检索器的分数不可直接比较：

- cosine similarity 可能在 `[-1, 1]` 或 `[0, 1]`；
- BM25 没有固定上限；
- 图距离是 hop count；
- 时间分和置信度又是另一套尺度。

常见办法有两种。

### 加权分数融合

```text
score =
  w_semantic × semantic_score
  + w_keyword × keyword_score
  + w_entity × entity_boost
  + w_recency × recency
```

优点是可解释；缺点是需要归一化和大量标定。

Mem0 OSS 当前使用的就是类似路线：语义候选上叠加 BM25 与实体 boost，再归一化。

### Reciprocal Rank Fusion（RRF）

```text
RRF(d) = Σ 1 / (k + rank_i(d))
```

RRF 只依赖每一路的相对排名，不要求不同分数同尺度。Graphiti、Cognee 和一些混合搜索服务采用或支持这种方式。

注意：不同实现的 `k`、rank 起点和候选截断方式可能不同，不能仅凭“用了 RRF”假定行为一致。

### 2.5 Rerank：提高 Top 结果精度

Reranker 输入通常是：

```text
(query, candidate_memory_text)
```

常见实现：

- 本地 cross-encoder；
- 托管 rerank API；
- LLM 相关性分类；
- 图距离；
- MMR 多样性排序；
- 规则与业务权重。

Reranker 的作用主要是提高候选池内部的 precision 和顺序。它不能解决：

- 候选根本没召回；
- scope 过滤错误；
- 记忆已过期；
- 存储里没有足够语义信息；
- 在 rerank 前已经被 top-k 截断。

### 2.6 Context Assembly：最终不是返回一堆搜索结果

成熟的 Context Builder 还要处理：

- token/字符预算；
- Profile、Fact、Episode 的配额；
- 重复和冲突；
- 当前事实与历史事实；
- provenance；
- 是否展示置信度；
- 记忆文本的排序和分组；
- Prompt 注入角色。

可能的输出：

```text
核心档案：
- 用户对花生过敏。

当前相关事实：
- 用户计划今晚与同事聚餐。

相关经历：
- 上次吃川味火锅时，用户询问过花生酱配料。

来源：
- session-18 / turn-42
```

不同框架的差异，很大一部分就在“搜索结果由谁、何时、以什么角色进入上下文”。

---

# 第二部分：各家实现

## 3. Mem0

### 3.1 设计定位

Mem0 把一条可复用记忆主要表示为“带 metadata 的事实文本”。主检索路径围绕向量记录展开，再叠加关键词、实体和可选 Reranker。

需要区分：

- **Mem0 OSS**：公开 Python 实现，可自选 vector store 和 reranker；
- **Mem0 Platform**：托管服务，公开 API 和概念文档，但服务端实现闭源。

### 3.2 OSS 存储

当前 OSS v3 中，一条记忆通常是主向量集合中的一条记录：

```text
id
embedding
data                    # 事实正文，对外映射为 memory
hash
text_lemmatized         # BM25 使用
created_at / updated_at
user_id / agent_id / run_id
actor_id / role / attributed_to
expiration_date
custom metadata
```

历史变更另存 SQLite `history` 表，记录 memory ID、旧值、新值、事件类型、时间和删除状态。它主要用于审计，不直接参与召回。

OSS 还维护实体索引：

```text
entity text + embedding
entity_type
linked_memory_ids
scope metadata
```

它更接近“实体到记忆 ID 的向量化倒排索引”，不是完整的有类型关系图。

### 3.3 OSS 召回链路

入口为 `Memory.search()`，核心步骤是：

```text
query
  → 词形归一化
  → query embedding
  → 主向量库语义检索
  → 可选 keyword/BM25 搜索
  → 查询实体匹配
  → 过期与 metadata 过滤
  → 语义 + BM25 + entity boost 融合
  → 截断 top_k
  → 可选 reranker
  → 返回 memory records
```

一个容易被产品文档掩盖的实现细节是：

- 最终候选集合以语义结果为基础；
- BM25 和实体信号主要给已有语义候选加分；
- 关键词独有命中不一定能进入最终候选；
- threshold 先作用于语义分；
- Reranker 当前接收的通常已是截断后的 top-k。

因此 Mem0 OSS 更准确的描述是：

> 语义召回为主，在语义候选上进行多信号增强和可选重排。

它不完全等价于“语义、BM25、图三路候选取并集”。

### 3.4 Platform 路线

托管版文档描述的检索信号包括：

- semantic；
- keyword；
- entity；
- temporal；
- 可选 managed reranker；
- 可选 memory decay。

Platform v3 的 Graph Memory 主要用于实体与记忆之间的连接和 ranking boost。服务端权重、候选池和物理存储没有公开，不能按 OSS 源码推断。

### 3.5 返回与上下文

OSS `search()` 返回事实列表及 score、时间、scope 和 metadata，不自动形成 Prompt。上下文预算、冲突处理和注入角色由应用负责。

### 3.6 优势与边界

优势：

- API 简单；
- 事实文本天然适合 embedding 和 rerank；
- metadata filter 比较完整；
- 后端可替换；
- 可解释融合细节。

边界：

- OSS 图能力有限；
- BM25 取决于 vector store 后端；
- 语义召回的候选入口较强势；
- Reranker 放在较晚且候选已截断；
- Platform 与 OSS 能力不能混写。

### 3.7 关键源码

- [`mem0/memory/main.py`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1a/mem0/memory/main.py)
- [`mem0/utils/scoring.py`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1a/mem0/utils/scoring.py)
- [`mem0/memory/storage.py`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1a/mem0/memory/storage.py)
- [官方 Search 文档](https://docs.mem0.ai/core-concepts/memory-operations/search)
- [OSS v2 → v3 迁移](https://docs.mem0.ai/migration/oss-v2-to-v3)

---

## 4. Zep / Graphiti

### 4.1 设计定位

Graphiti 是时序 Context Graph 框架，核心不是简单“把记忆存进 Neo4j”，而是把：

- 原始 Episode；
- 消歧 Entity；
- 自然语言 Fact Edge；
- Community；
- 双时钟；
- provenance；
- 混合检索

组合成可持续更新的图。

Zep 是托管产品，在 Graphiti 思路上增加专有图引擎、跨 scope 检索、上下文装配、多租户和治理能力。

### 4.2 数据模型

#### Episode

保存非损原始输入：

```text
uuid
group_id
source / source_description
content
created_at
valid_at
entity_edges
metadata
```

Episode 通过 `MENTIONS` 连接实体，并通过 `entity_edges` / edge `episodes` 与事实互相保留 provenance。

#### Entity

```text
name
summary
name_embedding
labels
attributes
```

#### Fact Edge

```text
source_node_uuid
target_node_uuid
name
fact
fact_embedding
episodes
created_at / expired_at
valid_at / invalid_at
attributes
```

`fact` 是可直接返回给 LLM 的自然语言事实；结构化端点提供关系和遍历能力。

#### Community

Community 是实体簇及其摘要。Graphiti OSS 当前使用 Label Propagation 构建，需要显式维护，不是每次搜索自动实时生成。

### 4.3 双时钟

Graphiti 区分：

```text
现实世界时间：
  valid_at
  invalid_at

系统认知时间：
  created_at
  expired_at
```

这允许表达：

- 某事实从什么时候开始为真；
- 什么时候不再为真；
- 系统何时知道它；
- 系统何时将旧事实标记为被替代。

旧事实不必物理删除，因此可以回答历史时点问题并保留变更来源。

### 4.4 索引与后端

Graphiti OSS 支持 Neo4j、FalkorDB、Neptune 等后端。索引包括：

- UUID、group、时间字段范围索引；
- Episode content 全文索引；
- Entity name/summary 全文索引；
- Fact name/fact 全文索引；
- Entity、Fact、Community embedding。

重要边界：

- OSS 默认实现并不等于成熟的 ANN 集群；
- 部分后端是在 group filter 后计算 cosine 并排序；
- BM25 的具体行为来自底层数据库；
- Zep 托管的专有 Context Graph Engine 不能从 Graphiti OSS 性能外推。

### 4.5 候选生成

Graphiti 可以对不同对象并行检索：

| 对象 | BM25/全文 | 向量 | BFS/图 |
|---|---:|---:|---:|
| Fact Edge | 是 | 是 | 是 |
| Entity | 是 | 是 | 是 |
| Episode | 是 | 当前主要为否 | 否 |
| Community | 是 | 是 | 否 |

BFS 可从显式 origin 开始；没有 origin 时，某些配置会先用 BM25/向量结果作为种子，再进行图扩展。

### 4.6 融合与重排

Graphiti 提供多套 SearchConfig Recipe：

- RRF；
- MMR；
- node distance；
- episode mentions；
- cross encoder。

默认简化搜索通常对 Fact 做 BM25 + cosine，再用 RRF。

RRF 用排名融合多路候选。Graphiti 当前源码的常数和标准论文常用参数不同，因此 RRF score 不能当概率解释。

MMR 用于相关性与多样性的权衡，但 Graphiti OSS 的实现细节与经典逐步贪心 MMR 不完全相同。

Node Distance 适合“围绕某个人或项目搜索”。但 OSS 部分后端当前更接近一跳邻居排序，不能直接等同于任意多跳最短路径。

Cross Encoder 路径通常是：

```text
BM25 / vector / graph candidates
  → RRF 预筛
  → 最多约 2 × limit 候选
  → relevance rerank
```

### 4.7 过滤

Graphiti OSS 支持：

- `group_ids`；
- node labels；
- edge types；
- edge UUID；
- property filter；
- `valid_at`、`invalid_at`、`created_at`、`expired_at`。

Zep 托管还公开更丰富的端点、Episode metadata、source/target、自然语言时间和跨 scope 搜索能力。

### 4.8 上下文装配

Graphiti OSS 搜索主要返回结构化对象：

- edges；
- nodes；
- episodes；
- communities；
- 对应 score。

辅助函数可以格式化为文本，但没有统一 token budget 和跨类型全局选择器。

Zep `scope="auto"` 则负责：

```text
跨 edges / nodes / episodes / observations / summaries 搜索
  → 专有跨 scope 排序
  → 按 max_characters 组装 context
```

这是 OSS 与托管版的重要产品差异。

### 4.9 优势与边界

优势：

- 数据模型完整；
- provenance 与双时钟是一等公民；
- 图、全文、向量多路检索；
- 能表达事实演化；
- SearchConfig 可定制。

边界：

- 部署和维护成本高；
- OSS 后端性能路径并非都使用 ANN；
- 部分 reranker 名称与教科书实现存在差异；
- Graphiti 只返回搜索对象，不等于完整 Context Builder；
- Zep 托管能力不能按 OSS 代码复现。

### 4.10 关键源码

- [`graphiti_core/nodes.py`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/nodes.py)
- [`graphiti_core/edges.py`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/edges.py)
- [`graphiti_core/search/search.py`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/search/search.py)
- [`search_config_recipes.py`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/search/search_config_recipes.py)
- [Graphiti Search 官方文档](https://help.getzep.com/graphiti/working-with-data/searching)
- [Zep Graph Search](https://help.getzep.com/searching-the-graph)
- [Zep / Graphiti 论文](https://arxiv.org/abs/2501.13956)

---

## 5. Letta

### 5.1 设计定位

Letta 的关键不是某个向量检索算法，而是把记忆设计成 Agent 自己管理的分层上下文，接近操作系统的内存层次：

```text
当前 Context Window
  ↕
Core / System Memory
  ↕
External / Archival Memory
  ↕
完整 Conversation History
```

当前 Letta Code 主路径已经转向 MemFS；旧 V1 Block / Archival API 仍在文档中，但不能混成同一实现。

### 5.2 当前 MemFS 存储

MemFS 使用 Markdown 文件：

```text
system/persona.md
system/human.md
projects/relay.md
preferences/coding-style.md
```

文件可带 frontmatter：

```yaml
---
description: 用户长期偏好与协作约束
read_only: true
---
```

主要语义：

- 路径是记忆地址；
- `description` 用于发现和选择；
- `system/*.md` 是高价值常驻记忆；
- 其他文件是按需读取的外部记忆；
- 每个 Agent 的 MemFS 是一个 Git repository；
- 编辑后产生 commit，天然具备版本和回滚能力。

### 5.3 Conversation 存储

所有消息持久化。最近消息留在当前上下文，较旧内容通过 compaction 摘要代表。

本地后端使用目录、manifest 和 `messages.jsonl`；Cloud 的实际数据库和搜索服务未公开。

### 5.4 召回触发

Letta 将召回分成几种不同机制：

| 记忆层 | 触发方式 |
|---|---|
| `system/*.md` | 每轮自动注入 |
| 其他 MemFS 文件 | Agent 根据路径、描述或链接主动读取 |
| Conversation History | Recall subagent 或消息搜索 |
| Dreaming / Reflection | 后台根据 step 或 compaction 事件更新 MemFS |
| `/remember` | 用户显式要求持久化 |

这意味着 Letta 的召回能力高度依赖 Agent 是否知道“自己还有外部记忆”以及是否正确调用工具。

### 5.5 检索

当前 MemFS 默认检索较朴素：

- 文件树；
- 文件名与路径；
- description；
- 文件读取；
- grep；
- `[[path]]` 引用。

可选 `@letta-ai/memfs-search` 才增加 keyword/semantic/hybrid 搜索，它不是默认核心能力。

Conversation Search：

- Cloud 接口公开 vector / FTS / hybrid、日期和范围过滤；
- Recall prompt 描述 hybrid 采用 vector + FTS + RRF；
- Cloud 服务端实现未公开；
- 本地后端目前主要是 FTS-lite，vector/hybrid 参数会退化。

### 5.6 上下文注入

- System Memory：直接编译到 system prompt；
- External Memory：只有结构和 description 默认可见，读取结果作为工具上下文进入；
- Recall：搜索结果或 Recall Subagent 摘要进入当前会话；
- Compaction：旧消息由摘要替代；
- MemFS 更新通常影响未来编译的上下文，而不是倒改已经构建好的当前轮。

### 5.7 V1 Archival Memory

旧接口仍提供：

- `archival_memory_insert`；
- `archival_memory_search`；
- semantic query；
- tag；
- 时间范围；
- top-k。

它可以理解为“向量化 passage 存储”，但不应写成当前 Letta Code MemFS 的默认实现。

### 5.8 优势与边界

优势：

- 明确区分常驻与按需记忆；
- Agent 可以主动修改未来上下文；
- 文件和 Git 使记忆透明、可审计；
- 适合身份、规则、项目知识等长期状态。

边界：

- 默认 MemFS 搜索不强；
- 搜索效果依赖工具使用策略；
- 本地与 Cloud 能力不完全一致；
- 常驻内容过多会直接挤占上下文；
- 当前 MemFS 与旧 Block/Archival 文档容易混淆。

### 5.9 关键来源

- [MemFS 官方文档](https://docs.letta.com/concepts/memfs/)
- [Memory & Dreaming](https://docs.letta.com/letta-code/memory/)
- [`memory.ts`](https://github.com/letta-ai/letta-code/blob/0ea62a0bd7999aece4d3e5c972f55c665fe7046a/src/tools/impl/memory.ts)
- [`memory-filesystem.ts`](https://github.com/letta-ai/letta-code/blob/0ea62a0bd7999aece4d3e5c972f55c665fe7046a/src/agent/memory-filesystem.ts)
- [Archival Memory 兼容文档](https://docs.letta.com/guides/core-concepts/memory/archival-memory/)

---

## 6. LangMem / LangGraph

### 6.1 设计定位

LangMem / LangGraph 不规定完整的 Agent 记忆操作系统，而是提供：

- thread state 持久化；
- namespaced JSON Store；
- semantic search；
- metadata filter；
- memory 管理与搜索工具；
- hot-path 或后台管理原语。

记忆放在哪里、什么时候查、怎样进入 Prompt，主要由应用 Graph 决定。

### 6.2 Checkpointer 与 Store 分离

#### Checkpointer

保存：

- thread-scoped graph state；
- messages；
- node writes；
- checkpoint；
- 恢复和 time travel 信息。

它解决会话连续性，不等于跨会话长期记忆。

#### BaseStore

长期记忆采用：

```text
namespace: tuple[str, ...]
key: string
value: JSON document
created_at
updated_at
```

搜索结果增加 `score`。

示例：

```text
namespace = ("users", user_id, "profile")
key       = "dietary_constraints"
value     = {"allergies": ["peanut"]}
```

或：

```text
namespace = ("users", user_id, "episodes")
key       = episode_id
value     = {"content": "...", "task": "..."}
```

### 6.3 记忆类型是策略，不是固定物理表

LangMem 将记忆分为：

- Semantic：事实和知识；
- Episodic：过去经历和 few-shot example；
- Procedural：行为规则和 Prompt。

但底层通常仍是 JSON document。框架不会强制每种类型必须采用某张表或某个后端。

### 6.4 持久化后端

BaseStore 可使用：

- InMemoryStore；
- PostgreSQL；
- Redis；
- MongoDB；
- Upstash；
- 自定义实现。

是否支持向量、过滤和稳定排序取决于具体后端及配置。Checkpointer 和 Store 的后端能力也不能混为一谈。

### 6.5 召回模式

LangMem 支持三种常见模式。

#### Agent 主动搜索

`create_search_memory_tool` 暴露搜索工具，Agent 决定是否调用。

#### 应用每轮自动搜索

在 Graph node 或 dynamic prompt 中：

```text
store.search(namespace, query=current_context)
  → 选择 top-k
  → 拼入 system prompt
```

#### Memory Manager 候选召回

应用显式调用 MemoryStoreManager 后，它会搜索已有相关记忆，再执行更新或整合。这个 Manager 可以在主链路运行，也可以放后台任务；LangMem 本身不是常驻调度服务。

### 6.6 BaseStore 搜索

支持：

- namespace prefix；
- key 直接读取；
- JSON field filter；
- query semantic search；
- limit / offset。

语义搜索需要配置 embedding/index。没有索引时，不能因为 API 里有 `query` 就假设后端一定做向量检索。

不同后端的无 query 默认排序也不一定一致，应用不应依赖隐式顺序。

### 6.7 MemoryStoreManager 的多查询

Manager 可以：

- 通过单独 query model 生成一个或多个搜索 query；
- 或从 conversation 构造不同范围的 query；
- 每个 query 获取候选；
- 按 `(namespace, key)` 去重；
- 按 score 合并截断；
- 将候选交给后续管理过程。

这里体现了一个重要设计：**query formulation 与 memory extraction 可以是两套独立流程。**

### 6.8 上下文注入

LangGraph Store 不负责统一 Context Builder。应用可以选择：

- Profile `get` 后完整注入 system message；
- Collection semantic top-k 后注入；
- Search Tool 结果进入普通 tool message；
- 将记忆保存在 graph state；
- Dynamic Prompt 每轮重新构建。

因此 LangMem 的自由度高，但应用必须自己承担预算、冲突、排序和注入语义。

### 6.9 优势与边界

优势：

- schema 和后端自由；
- namespace 适合多租户与业务隔离；
- Checkpointer 与长期 Store 边界清楚；
- 容易嵌入现有 LangGraph 工作流；
- 支持 Profile 与 Collection 两种常见形态。

边界：

- 没有统一分层上下文；
- 没有默认图召回；
- 没有统一融合与 Reranker；
- 后端行为可能不同；
- 如果应用没调用 search 和注入，存下来的记忆不会自动影响回答。

### 6.10 关键来源

- [LangMem Core Concepts](https://langchain-ai.github.io/langmem/concepts/conceptual_guide/)
- [LangGraph Stores](https://docs.langchain.com/oss/python/langgraph/stores)
- [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- [`langmem/knowledge/extraction.py`](https://github.com/langchain-ai/langmem/blob/29cbe41e58528f92e9efa773c12e15c47be3808c/src/langmem/knowledge/extraction.py)
- [`langgraph/store/base`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py)

---

## 7. Supermemory

### 7.1 设计定位

Supermemory 将两类内容放在同一 Context Pool 中：

- Memory：持续演化的事实和关系；
- Document Chunk：用于 grounding 的原始文档片段。

其内部“自研 graph database / learning model”未完全开源，因此能够核验的主要是公开 API、文档和部分仓库代码。

### 7.2 存储视图

官方描述包含：

- 原始文档；
- chunk；
- embedding；
- memory fact；
- temporal graph；
- containerTag 隔离；
- 相关、更新和 forgotten memory。

`containerTag` 可以对应用户、项目、团队或组织，是主要隔离和上下文池边界。

### 7.3 搜索模式

统一搜索支持：

- `memories`：只查记忆；
- `documents`：只查文档 chunks；
- `hybrid`：合并两类结果。

搜索参数包括：

- query；
- containerTag；
- metadata filters；
- limit；
- threshold；
- rerank；
- rewriteQuery；
- recency bias；
- 是否附带 documents、summaries、related/forgotten memories。

### 7.4 召回链路

按公开文档可概括为：

```text
query
  → 可选 query rewrite，生成多个查询
  → embedding / search
  → memories 与 document chunks 并行或组合检索
  → metadata/container filter
  → 合并与去重
  → 可选 rerank
  → 返回 memory/chunk + score + 关联上下文
```

Hybrid 的产品价值是同时解决：

- personalization：用户事实和长期变化；
- grounding：原始文档证据。

### 7.5 优势与边界

优势：

- Memory 与 RAG 统一；
- query rewrite、hybrid、rerank 是一等 API；
- 可以回退到原始 chunk；
- containerTag 的使用方式直观。

边界：

- 核心 graph engine 和排序权重未公开；
- 文档中的延迟与效果属于厂商实现，不能直接外推；
- “related/forgotten”具体选择算法缺乏完整源码证据；
- 适合作为产品路线参考，不适合照着公开接口复刻内部算法。

### 7.6 关键来源

- [How It Works](https://supermemory.ai/docs/concepts/how-it-works)
- [Searching Memories](https://supermemory.ai/docs/memory-api/searching/searching-memories)
- [公开 Search 文档源码](https://github.com/supermemoryai/supermemory/blob/694ad812/apps/docs/search/overview.mdx)

---

## 8. Cognee

### 8.1 设计定位

Cognee 强调三种存储协作：

```text
Relational Store：文档、chunk、metadata、provenance
Vector Store：chunk 与 DataPoint embedding
Graph Store：实体与关系
```

新版 API 用 `remember / recall / improve / forget` 表达记忆生命周期。

### 8.2 物理部署

本地开发可以使用嵌入式组件；生产可选择专用数据库。Cognee 1.0 还强调 Postgres-first：

- relational data；
- pgvector；
- graph backend；
- session/cache；
- metadata

可以统一部署在 Postgres 中。

这说明“逻辑上分三层”不一定要求“物理上部署三套数据库”。

### 8.3 召回

公开路线包括：

- vector semantic search；
- BM25 lexical search；
- graph traversal；
- hybrid retrieval；
- RRF fusion；
- query routing；
- question decomposition；
- dataset scope；
- evidence reference。

典型流程：

```text
query
  → 选择或组合 retrieval method
  → vector / BM25 / graph candidates
  → dataset 与 metadata filter
  → RRF fusion
  → 返回带 evidence 的结果
```

### 8.4 优势与边界

优势：

- provenance、向量和图职责明确；
- 可使用单 Postgres 降低运维复杂度；
- Pipeline / Task 扩展性强；
- 混合检索路线完整。

边界：

- 框架覆盖数据 ingestion、知识图和 Agent Memory，范围较大；
- 不同后端能力和性能并不等价；
- 1.0 新接口仍在演进；
- 厂商博客中的性能数字需要独立验证。

### 8.5 关键来源

- [Cognee Architecture](https://docs.cognee.ai/core-concepts/architecture)
- [Cognee Overview](https://docs.cognee.ai/core-concepts/overview)
- [Cognee GitHub](https://github.com/topoteretes/cognee)
- [Cognee 1.0](https://www.cognee.ai/inside-cognee-1-0)

---

# 第三部分：横向比较

## 9. 核心能力矩阵

| 维度 | Mem0 OSS | Graphiti OSS | Letta Code | LangMem / LangGraph | Supermemory | Cognee |
|---|---|---|---|---|---|---|
| 主要记忆单元 | Fact text | Episode / Entity / Fact Edge | Markdown memory file | Namespaced JSON | Memory + Chunk | DataPoint / Entity / Chunk |
| 原始事件保留 | History 较弱 | 强，Episode 一等公民 | 强，conversation | Checkpointer | 强，document chunks | 强，relational provenance |
| 常驻 Profile | 应用自行实现 | 无内建 | 强，system memory | 可实现 | 产品能力 | 可实现 |
| 向量检索 | 强 | Node/Edge/Community | 可选 | Store 可选 | 强 | 强 |
| BM25 / FTS | 后端可选 | 强 | 本地较基础 | 后端决定 | 有 | 有 |
| 图关系 | OSS 仅实体索引 | 强 | 文件链接为主 | 无内建 | 闭源图 | 强 |
| 双时钟 | OSS 有限 | 强 | Git/history，不是双时钟图 | schema 自定义 | temporal graph 描述 | schema/pipeline 自定义 |
| 多路融合 | 加权融合 | RRF/MMR 等 | Cloud history search 有 hybrid | 应用自行实现 | hybrid + rerank | RRF |
| Reranker | 可选 | 多种 | Cloud 部分能力 | 应用自行实现 | 可选 | 检索层支持 |
| Context Builder | 无 | OSS 较弱，Zep Auto 强 | Harness 强 | 应用负责 | 托管服务负责部分 | Recall API |
| 存储透明度 | OSS 高 | OSS 高 | 本地 MemFS 高 | 高 | 核心较低 | 高 |

## 10. 六种路线的本质差异

### Mem0：事实搜索服务

核心问题：

> 给定一个自然语言 query，找出最相关的若干条长期事实。

适合偏好、事实和轻量个性化。

### Graphiti：时序上下文图

核心问题：

> 如何同时保存事件、实体、事实关系、来源和时间，并通过混合搜索找到结构化上下文。

适合关系复杂、事实会变化、需要历史追踪的系统。

### Letta：Agent 自有上下文操作系统

核心问题：

> 哪些内容永远在上下文，哪些内容由 Agent 按需分页，Agent 怎样修改未来的自己。

适合长期 Agent identity、规则和项目工作记忆。

### LangMem / LangGraph：应用可组合记忆原语

核心问题：

> 给开发者一个通用 Store 和工具，由业务 Graph 决定记忆 schema、生命周期和注入方式。

适合已有 LangGraph 应用和高度自定义业务。

### Supermemory：Memory + RAG 托管 Context Pool

核心问题：

> 如何在同一 API 中同时返回长期理解和原始证据。

适合希望直接购买托管记忆与检索能力的产品。

### Cognee：数据管线驱动的图向量知识层

核心问题：

> 如何把多源数据经过 Pipeline 变成同时可语义搜索和图遍历的持久知识。

适合企业文档、知识图和 Agent Memory 的组合场景。

---

# 第四部分：对 Relay 的映射

## 11. Relay 当前所处位置

Relay 已经拥有：

- `raw_event`：原始事件；
- Claim：开放文本记忆；
- Triple：闭集关系图；
- SQLite 真相源；
- FTS；
- `graph_id`；
- session/task scope；
- `created_at / expired_at / valid_at / invalid_at` 双时钟；
- `recalling` ContextAugmenter；
- 字符预算。

这使 Relay 在**存储确定性、时间和本地可控性**上更接近轻量 Graphiti，而不是纯向量记忆库。

当前主要缺口不在存储，而在 Recall Pipeline：

```text
当前：
LatestUser
  → CJK 2/3-gram FTS
  → 命中节点
  → 一跳事实
  → confidence × recency
  → facts 先占预算，claims 用剩余预算

缺少：
多查询构造
Profile 直接读取
Claim/Episode 向量候选
关键词/向量/图的候选并集
融合排序
相关性 Reranker
跨类型统一预算
召回解释与评测
```

## 12. 推荐的 Relay 目标结构

### 12.1 存储层保持五类对象

```text
raw_event
  原始对话与来源，不可损

profile
  少量稳定关键约束，直接读取

claim
  开放文本记忆，语义检索入口

fact_edge
  闭集关系、双时钟、图扩展

memory_index
  FTS / embedding / entity alias，可重建
```

不需要为了“行业主流”立即引入 Neo4j。当前规模下 SQLite 可以继续做真相源；embedding 表或轻量 ANN 只是索引。

### 12.2 召回分五阶段

```text
RecallRequest
  query
  recent_messages
  user_id / graph_id
  session_id
  task_scope_id
  time
  budget

1. Mandatory Context
   直接读取安全、身份、长期规则等 Profile

2. Candidate Generation
   FTS facts
   FTS claims
   vector claims/episodes
   recent/current-task
   graph expansion

3. Fusion
   首版用 RRF，避免不同分数硬归一化

4. Rerank
   relevance
   confidence
   temporal validity
   scope
   novelty/diversity

5. Context Assembly
   Profile / Fact / Episode 分配独立预算
   去重、冲突、来源和时间标注
```

### 12.3 为什么首版更适合 RRF

Relay 未来会同时出现：

- FTS rank；
- embedding cosine；
- graph distance；
- confidence；
- recency。

直接设计一条加权公式会陷入分数归一化和持续调参。RRF 只使用各通道排名，适合作为第一版可解释基线。

后续可以把：

- confidence；
- state；
- temporal validity；
- scope

作为硬过滤或 RRF 后的业务调整，而不是全部混进一个不可解释的总分。

### 12.4 Profile 应当单独存在

不能召回失败的内容：

- 严重过敏和禁忌；
- 用户身份；
- Agent 不可违反的长期约束；
- 当前任务的硬规则。

这类内容适合：

```text
固定 schema
直接 key 读取
严格长度上限
每轮或相关场景强制注入
```

这对应 Letta Core Memory 和 LangMem Profile 的经验。

### 12.5 Claim 是语义入口，Triple 是结构扩展

推荐职责：

```text
Claim / Episode：
  保存完整语义
  支持向量、BM25 和 rerank
  回答“这件事与当前问题是否相关”

Triple / Fact Edge：
  确定性关系
  时间与状态
  从命中实体扩展一跳
  回答“与这个实体还关联什么”
```

这比继续增强 Triple 的 n-gram 搜索更接近 Mem0、Graphiti 和 Cognee 的共同路线。

## 13. 不应直接照搬的东西

### 不直接照搬 Mem0 的“语义候选优先”

如果 BM25 和实体只给语义候选加分，低语义相似但精确命中的事实可能永远进不了候选池。Relay 更适合真正的候选并集，再融合。

### 不直接照搬 Graphiti 的重型图部署

Relay 是 Android 原生 SDK，本地资源、包体、功耗和迁移成本与服务端不同。保留图数据模型不等于必须引入图数据库。

### 不直接照搬 Letta 的 Agent 自治

让 Agent 自己决定何时记、何时搜，灵活但难以测试。Relay 的关键 Profile 和默认召回应保持确定性，Agent Tool 作为补充。

### 不直接照搬托管产品的闭源指标

Supermemory、Mem0 Platform、Zep Auto Search 的内部权重、索引和 Reranker 未完全公开，只能学习接口分层，不能把厂商效果声明当实现保证。

---

# 第五部分：评测建议

## 14. 召回需要独立 Eval

抽取 P/R 通过不代表召回通过。建议把评测拆成：

```text
Storage Accuracy
  该记忆是否正确落盘

Candidate Recall@K
  目标记忆是否进入候选池

Ranking MRR / nDCG
  目标记忆是否排在前面

Context Precision
  注入内容里有多少真正有用

Scope Safety
  是否混入其他用户 / task / session

Temporal Correctness
  是否召回已经失效或历史时点错误的事实

Answer Utility
  注入记忆是否真的改善最终回答
```

## 15. 失败样例分类

每条失败样例记录：

```text
用户问题
最近对话
当前 task/session
期望记忆 ID
实际候选
实际最终注入
失败阶段
```

失败阶段建议固定为：

1. `NOT_STORED`
2. `FILTERED_BY_SCOPE`
3. `QUERY_MISMATCH`
4. `CANDIDATE_MISS`
5. `RANKED_TOO_LOW`
6. `BUDGET_DROPPED`
7. `STALE_OR_CONFLICTING`
8. `INJECTED_BUT_UNUSED`

只有这样才能区分：

- 抽取问题；
- 召回问题；
- 排序问题；
- Context Builder 问题；
- 最终模型使用问题。

---

# 参考资料与证据边界

## 一手资料

### Mem0

- [How It Works](https://docs.mem0.ai/core-concepts/how-it-works)
- [Memory Search](https://docs.mem0.ai/core-concepts/memory-operations/search)
- [Mem0 OSS 固定源码](https://github.com/mem0ai/mem0/tree/39bc02330563764e7d4465f1ecff5f002d94da1a)

### Zep / Graphiti

- [Graphiti GitHub 固定源码](https://github.com/getzep/graphiti/tree/993e081a6d7948a0d8851c12a5fbdbeb49fed862)
- [Graphiti Searching](https://help.getzep.com/graphiti/working-with-data/searching)
- [Zep Graph Search](https://help.getzep.com/searching-the-graph)
- [Zep Temporal Knowledge Graph Paper](https://arxiv.org/abs/2501.13956)

### Letta

- [MemFS](https://docs.letta.com/concepts/memfs/)
- [Letta Code Memory](https://docs.letta.com/letta-code/memory/)
- [Letta Code 固定源码](https://github.com/letta-ai/letta-code/tree/0ea62a0bd7999aece4d3e5c972f55c665fe7046a)

### LangMem / LangGraph

- [LangMem Concepts](https://langchain-ai.github.io/langmem/concepts/conceptual_guide/)
- [LangGraph Stores](https://docs.langchain.com/oss/python/langgraph/stores)
- [LangMem 固定源码](https://github.com/langchain-ai/langmem/tree/29cbe41e58528f92e9efa773c12e15c47be3808c)
- [LangGraph 固定源码](https://github.com/langchain-ai/langgraph/tree/38031739e551638e373fb553453256c23feeb41f)

### Supermemory

- [How It Works](https://supermemory.ai/docs/concepts/how-it-works)
- [Searching Memories](https://supermemory.ai/docs/memory-api/searching/searching-memories)
- [Supermemory GitHub](https://github.com/supermemoryai/supermemory)

### Cognee

- [Architecture](https://docs.cognee.ai/core-concepts/architecture)
- [Overview](https://docs.cognee.ai/core-concepts/overview)
- [Cognee GitHub](https://github.com/topoteretes/cognee)

## 证据等级

| 内容 | 证据等级 | 使用方式 |
|---|---|---|
| 固定 commit 的开源实现 | 高 | 可描述具体数据流和 caveat |
| 官方 API / 产品文档 | 中高 | 可描述公开契约，不推断内部实现 |
| 厂商架构博客 | 中 | 用于理解方向，性能与效果声明需独立验证 |
| 第三方测评和营销对比 | 低 | 本文不用于核心结论 |

