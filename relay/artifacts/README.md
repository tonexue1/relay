# `:relay:artifacts`

对话外的生成物：有版本、可校验、可反馈。不管怎么生成、怎么画。

Maven：`io.github.tonexue1:relay-artifacts:0.1.0`（jar）

## 提供什么 API

| 类型 | 作用 |
|---|---|
| [`ArtifactRepository`](src/main/kotlin/relay/artifacts/ArtifactModels.kt) | `create` / `revise` / `read` / `activate` / `addFeedback` |
| [`FileArtifactRepository`](src/main/kotlin/relay/artifacts/FileArtifactRepository.kt) | 落盘（body + `manifest.json`） |
| `ArtifactRef` / `ArtifactVersion` / `ArtifactRecord` | 引用与历史 |
| `ArtifactValidator` | 按 mime 做结构/体积检查 |
| `ArtifactFeedback` / `ArtifactAnnotation` | 用户批注，不进模型 unless 宿主塞回去 |

`revise` 必须带 `baseVersion`。`activate` 切换当前展示版本。

## 能力边界

- **做**：不可变 body（sha256）、版本链、校验报告、反馈旁路。
- **不做**：渲染（[ui-kit](../ui-kit/README.md)）、LLM、多 agent 便签（那是 [orchestra 的 ArtifactStore](../orchestra/README.md)）。
- 默认限制单篇 body 大小（`FileArtifactRepository` 构造参数）。
- 不是对象存储 CDN；就是本机目录。

## 依赖了什么

- 无其它 `relay-*`。kotlinx-serialization。
- 被谁用：[ui-kit](../ui-kit/README.md) 的 `write_*_artifact` / `read_artifact` 工具。
