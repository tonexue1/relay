# `:relay:memory` 文档

目标设计，供评审。无图。抽完存，再取。

| 顺序 | 文档 | 看什么 |
|---|---|---|
| 1 | [prd.md](./prd.md) | 做不做、验收 |
| 2 | [architecture.md](./architecture.md) | 职责、写入、召回 |
| 3 | [schema.md](./schema.md) | 表 |
| 4 | [api.md](./api.md) | 接口 |
| 5 | [design.md](./design.md) | 落地：包、时序、状态机、切片 |
| 附 | [framework-survey.md](./framework-survey.md) | 行业对照，不是 spec |

首版：向量在；Reflection 能存能取、不自动写；AI 可新建 `fieldId`；别名、点时 State、手改锁、`includeOwners` 在合同里。
