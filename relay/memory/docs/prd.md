# Relay Memory PRD

| | |
|---|---|
| 状态 | 评审草案 |
| 日期 | 2026-08-28 |
| 模块 | `:relay:memory` |
| 设计 | [architecture.md](./architecture.md) · [schema.md](./schema.md) · [api.md](./api.md) |

---

## 1. 目标

助手和小说角色能记住「现在是什么」和「发生过什么」，下一轮能取回来。过敏这类必带项必须直接读到，不靠搜索碰运气。用户换一种说法，经历仍能被搜到。小说按故事时间取「当时」的当前值，不把后面章节的位置提前带进来。

模型不能直接写库。不做知识图谱。

---

## 2. 谁用

| 谁 | 做什么 |
|---|---|
| 个人助手 | 记过敏、住址、项目经历；点外卖必带过敏；聊到面试能回忆那场 |
| 小说角色 | 只记本角色知道的事；不过未来章节；位置/目标取生成章当时的值 |
| 宿主 App | 裁决抽取结果、声明本轮必带字段、挂字段别名、注入 embedding |
| Memory SDK | 落库、隔离、点时读取、检索、给解释 |

---

## 3. 范围

**做**

- 四种记忆：原文、State、Episode、Reflection（能存能取，不自动写）
- 字段目录 + 别名：AI 可新建 `fieldId`；「过敏」可挂到 `allergies`；必带只走规范名
- 写入：capture → Proposal → 宿主裁决 → commit；用户手改后抽取器只能写 CANDIDATE
- 召回：必带 State 按 `at` 点时直读；FTS + 向量 + 最近 + Tag 四路并集；SESSION/TASK 按 `scope_id` 硬过滤
- 小说：显式 `includeOwners`；Episode 幂等键含 `owner_id`
- 存量迁出：OpenClaim → Episode；有映射的 Triple → State，其余 → Episode

**不做**

- 图、闭集谓语、邻居遍历、节点合并
- 自动生成 Reflection
- 获知时钟与发生时钟分离
- HNSW / RRF / schema 自动迁移
- SDK 猜本轮该带哪些字段
- 自动合并两个字段的值
- 用聊天模型当 embedding

---

## 4. 需求

### 存

1. 原文只追加。提交成功或宿主整批拒绝后标 `COMMITTED`。空抽取不自动当消费。
2. State 同一 `(space, owner, scope, fieldId)` 只有一个当前值。改值留历史，并写 `valid_from` / `valid_to`。
3. `fieldId` 就是规范名。`state_field` 记字段；`state_field_alias` 记别名；`memory_item` 记值。没有目录行，点名读取失败。
4. 宿主可多种子字段。AI 提新名走 `ensureStateField`。新字段默认 CANDIDATE。别名由宿主挂到规范名；值不自动合并。两槽都有当前值且 payload 不一致 → `AMBIGUOUS_FIELD`。
5. Episode 只追加。幂等键是 `(space_id, owner_id, idempotency_key)`。
6. Reflection 必须挂证据，无证据只能 CANDIDATE。谁写、何时写由宿主决定。
7. `USER_LOCK` 看该 owner 下这一字段**所有 scope**。任一行当前值是 `USER_EDIT`，抽取器在任何 scope 都不能升当前值，只能 CANDIDATE。不许用 SESSION 盖 PROFILE 手改。
8. 向量异步写，失败不回滚正文。FTS 与正文同事务。小说 State / Reflection 的 `valid_from` 必填。

### 取

9. 必带字段先解析别名再按规范名直读，不搜索。`at` 取该时刻有效的 ACTIVE 版本，不是「全书最后一次写入」。缺默认 `Blocked`。
10. 四路并集。硬过滤在 LIMIT 前：space；owner（加 `includeOwners`）；SESSION 仅当前 `sessionId`；TASK 仅当前 `taskScopeId`。STATE / Reflection 也按 `at` 滤 `valid_*`，不用「全书 is_current」。Episode：`occurred_at ≤ at`。
11. Query = 最后一句用户 + 最近一两句 + 任务标题。最近排序用业务时间 `occurred_at` / `valid_from`，不用墙钟。
12. 跨 space 为空。默认只读 `ownerId`。世界记忆必须显式 `includeOwners`。业务时间用 `occurred_at` / `valid_*`，不用墙钟。小说 Episode 的 `occurred_at`、State/Reflection 的 `valid_from` 必填。
13. `Ready` / `Blocked` 分形。空结果、没权限、字段冲突不能长一样。
14. 向量或 FTS 坏了，必带点时读取仍可用。

---

## 5. 验收

以下不过，不算做成。

- 并发写同一字段，只有一个当前值
- 重放同一 run，同一 owner 的 Episode 不重复；两角色同键各写一条都成功
- 「过敏」挂到 `allergies` 后，必带 `allergies` 读到同一槽；两槽值冲突 → Blocked；`alias` 不得占用已有 `field_id`
- 用户手改 PROFILE 过敏后，抽取器写 SESSION 当前值也被拒，只能 CANDIDATE
- 新字段「巴拉巴拉」占槽后能点到；未占槽点不到
- 必带缺失 → `Blocked`；预算极小必带仍在
- 新会话 FTS/向量带不上上周 SESSION 的「今晚打算」
- `at=现在` 时旧住址（已闭 `valid_to`）不进四路搜索
- 跨 owner 的 FTS 和向量都是零条，除非 `includeOwners` 写明
- 小说 `at=第30章`：第80章 Episode 不出现；第80章改过的位置在必带和搜索里都不出现
- 删掉向量表，必带 + FTS + Recent 仍可用

---

## 6. 落地顺序

1. 按本文接口和表落地，测试先绿。
2. OpenClaim → Episode；有映射的 Triple → State，其余 → Episode。
3. 召回对比通过后再切默认读取。
4. 再拆旧图工具。先测后切。

宿主负责：种子字段与别名、本轮必带列表、Proposal 裁决、`includeOwners`、embedding 注入。小说建议多种子：`location`、`current_goal`。Memory 不代替这些。
