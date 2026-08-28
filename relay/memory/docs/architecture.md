# 架构

抽完就存。取的时候：本轮必带的当前值按 `at` 直读，其余四路搜索取并集。

表见 [schema.md](./schema.md)，接口见 [api.md](./api.md)。

---

## 1. 职责

```text
抽取器     只出 Proposal
宿主 App   裁决写什么、本轮必带哪些字段、挂别名、声明 includeOwners
Memory     校验并落库、隔离、点时读取、检索、解释
```

Memory 不判断：是不是同一件事、两个字段该不该并成一条、这轮该带哪些字段、值合不合理。别名是宿主挂的映射，不是语义合并。

---

## 2. 四种记忆

| 类型 | 回答 | 改法 |
|---|---|---|
| RawEvent | 当时原话 | 只追加 |
| State | 某一时刻是什么 | 同一字段一个当前头；历史用 valid_from/to |
| Episode | 发生过什么 | 只追加 |
| Reflection | 这些经历说明什么 | 版本化，必须挂证据 |

Scope：`PROFILE` / `TASK` / `SESSION`。覆盖仍是 `SESSION > TASK > PROFILE`，但只在**同一 `at`、且 SESSION/TASK 的 `scope_id` 匹配请求**时比较。

`fieldId` 是规范名。目录 `state_field`，别名 `state_field_alias`，值 `memory_item`。AI 可 `ensureStateField` 建新槽。别名不得占用另一个已有 `field_id`。必带和 `getStates` 先把别名解析成规范名。「过敏」→ `allergies` 是映射，不是把两个当前值捏成一个。两规范名都有当前值且 payload 不同 → `AMBIGUOUS_FIELD`。

`is_current` 仍表示「全书/全库这条字段的最新头」，给写入 CAS 用。**读取「当时是什么」不靠 `is_current`，靠 `at` + `valid_from`/`valid_to`。** 助手 `at=现在` 时与当前头重合。小说 `at=第30章` 必须读第30章有效的那一版，即使书已写到第80章。

CANDIDATE 永不当当前值。撤回当前值必须清当前头，并给旧行补 `valid_to`。

时间：助手 `WALL_CLOCK`，小说 `STORY_TIME`，与 space 不一致直接拒。业务过滤只用 `occurred_at` / `valid_from` / `valid_to`。墙钟列（`created_at` 等）不参与召回过滤。小说写入：Episode 的 `occurred_at`、State / Reflection 的 `valid_from` 必填。首版不加「获知 vs 发生」双时钟：只把该角色已知道的事写入该 `owner`，`occurred_at` / `valid_from` = 经历或获知的章节。

世界事实不默认混进角色。要读世界仓，宿主显式 `includeOwners`（如 `_world`）。

---

## 3. 写入

```text
1. capture          原文 → raw_event
2. 抽取器           Proposal
3. 宿主裁决         接受 / 待确认 / 丢掉
4. commit           校验并落库
5. 原文 COMMITTED   FTS 同事务；embedding 异步
```

空抽取不自动消费。宿主整批拒绝也要 `COMMITTED`。

新字段默认 CANDIDATE。`overwrite_policy`：

```text
EXTRACTOR_CAN_CURRENT     抽取器可写当前值（低风险）
EXTRACTOR_CANDIDATE_ONLY  抽取器只能 CANDIDATE
USER_LOCK                 该字段任一 scope 当前值是 USER_EDIT 时，抽取器只能 CANDIDATE
```

过敏等种子用 `USER_LOCK`。锁的是 `(space, owner, fieldId)`，**跨 PROFILE / TASK / SESSION**。任一 scope 的当前值是 `USER_EDIT`，抽取器不得在任何 scope 写 CURRENT（含另开 SESSION 来盖 overlay）。只能 CANDIDATE。`overrideUserEdit` 仅 USER_EDIT / HOST。

Episode 幂等：`(space_id, owner_id, idempotency_key)`。两角色同章同键各写一条，都成功。

State 覆盖：旧行 `is_current=0`，`valid_to=本次 valid_from`；新行 `is_current=1`，填 `valid_from`。

---

## 4. 召回

两段，不要混。

**必带 State：点时直读。** 别名 → 规范名。在 `at` 上取 `lifecycle_state=ACTIVE` 且 `valid_from≤at` 且 (`valid_to` 空或 `>at`) 的版本。不搜索。缺默认 `Blocked`。未占槽、只有 CANDIDATE、冲突，都是明确状态，不是空列表。预算裁不掉必带。

**四路并集（同一套 `at`）：**

硬过滤在 LIMIT 前：

```text
space_id = 请求
owner_id IN (ownerId ∪ includeOwners)
SESSION → scope_id = sessionId
TASK    → scope_id = taskScopeId
PROFILE → 始终可入
STATE / REFLECTION → ACTIVE
                    AND valid_from ≤ at
                    AND (valid_to IS NULL OR valid_to > at)
EPISODE → occurred_at IS NOT NULL AND occurred_at ≤ at
墙钟列不参与；最近 ORDER BY occurred_at 或 valid_from，不用 created_at
```

`is_current` 不参与搜索。第80章才生效的位置 `valid_from=80`，`at=30` 时必带和搜索都没有它。

四路：FTS5 / BM25；向量（同一过滤后再 cosine）；最近（当前 session/task，`ORDER BY` 业务时间）；Tag。

Query = 最后一句用户 + 最近一两句 + 任务标题。

世界仓与角色仓同一 `fieldId`：必带/`getStates` 默认只用 `ownerId` 那条；`includeOwners` 命中的行可进搜索，但必带冲突时 `AMBIGUOUS_FIELD`，不静默选一条。

---

## 5. 首版不做

自动生成 Reflection、获知/发生双时钟、HNSW、RRF、schema 自动回填、SDK 猜必带字段、自动并值。
