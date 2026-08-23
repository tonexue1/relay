# Memory eval · 抽取 / 做梦

当前架构的评测集：云端 `CloudTripleExtractor` 双输出（开放 Claim + 闭集 Triple）+ 夜里 `merge_nodes`。不是 [eval/dream](../dream/README.md) 里被否的端侧 3B 抽取。

金标在 `relay/memory` 测试源码里，过闭集谓语、别名和自洽打分：

| 文件 | 内容 |
|---|---|
| `ExtractEvalCorpus.kt` | 抽取：陷阱 + 埋事实 + retract + 批量短句 |
| `DreamEvalCorpus.kt` | 做梦：该并 / 不该并 / 大批近义节点 |

默认 `./gradlew :relay:memory:test` **不打模型**。它验金标纪律，以及做梦金标 merge 作用在引擎上之后活图对不对。

## 规模

跑测试时打印以代码为准。量级是：抽取 140+ 条（陷阱 35+，批量 100+），做梦金标 merge 60+ 次（含 40 对近义节点）。

抽取陷阱专门打这些错：

- 妈妈喜欢花生 ≠ 用户喜欢花生
- 花生过敏 + 火锅 ≠ 火锅过敏
- 作业 / 工龄 / 英语 / JNI 的谓语别串
- 离职 = `plans 跳槽`，可另有 `plans 休息`
- 取消签证要 `retract`，含糊的「那趟取消了」不要猜
- 闲聊、改需求、机票、提醒抽空
- 项目经历：`worked_on` / `has_component` / `uses_technology` / `target_role`
- 闭集接不住的架构、职责和条件策略必须进 Claim，不能空稿丢失
- malformed / truncated 响应不得消费原文

做梦金标：

- `离职`/`换工作` → `跳槽`；`美式咖啡` → `美式`；`我妈` → `妈妈`
- 杭州 / 上海、猫 / 狗、花生 / 青霉素 **不许并**

## 真模型

Key：`RELAY_DEEPSEEK_API_KEY` 或仓库根 `local.properties` 的 `relay.deepseek.apiKey`。

```bash
# 高强度抽取（陷阱+埋事实+retract，不含批量短句）+ 3 条夜里 Agent
./gradlew :relay:memory:test --tests relay.memory.extract.eval.LiveMemoryEvalTest -Drelay.liveEval=true

# 再加上批量短句
./gradlew :relay:memory:test --tests relay.memory.extract.eval.LiveMemoryEvalTest -Drelay.liveEval=all
```

过闸：Triple 与 Claim 分别 P≥70% R≥50%，且陷阱 `forbidden` 一条都不能中。夜里 Agent 只跑 `DreamEvalCorpus.liveSubsetIds`，活图要满足 `liveMust` / `liveMustNot`。

2026-08-20 真模型（陷阱 42 条，不含批量）：抽取 **P=0.890 R=0.910**，`forbidden=4` 未过闸（`has_task 需求`、`parent_of`/`child_of` 反了、含糊取消却 retract 美国）。夜里 3 条里 `美式咖啡→美式` 没并上。

2026-08-23 Episode + Claim 改造后（45 条，不含 bulk）：Triple **P=0.989 R=0.989**、Claim **P=1.000 R=1.000**、`forbidden=0`，通过抽取闸。

预测不要提交。若要落盘自己重定向测试 stdout。
