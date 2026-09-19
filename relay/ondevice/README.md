# `:relay:ondevice`

把 llama.cpp 做成 [`Provider`](../llm/README.md)。可选；云端链路不依赖本模块。

Maven：`io.github.tonexue1:relay-ondevice:0.1.0`（AAR，仅 **arm64-v8a**）

## 提供什么 API

| 类型 | 作用 |
|---|---|
| [`OnDeviceProvider`](src/main/kotlin/relay/ondevice/OnDeviceProvider.kt) | 端侧 `Provider`：`load` / `unload` / `chat` / `stream` |
| [`ModelSpec`](src/main/kotlin/relay/ondevice/model/ModelSpec.kt) / `OnDeviceModels` | 可下载 GGUF 目录（默认 Qwen2.5-3B Q4_K_M） |
| [`ModelStore`](src/main/kotlin/relay/ondevice/model/ModelStore.kt) | 下载、断点续传、sha256 校验 |
| [`ToolCallEvaluation`](src/main/kotlin/relay/ondevice/ToolCallEvaluation.kt) | 固定题集测 tool calling |
| `LlamaEngine` | JNI 引擎端口；测试可换 Fake |

`load` / `unload` 是阻塞 JNI，不要在主线程调用。先 `load` 再 `chat`/`stream`。

## 能力边界

- **做**：本机 GGUF 推理、流式、用约束文本协议解析 tool call。
- **不做**：NPU、x86 模拟器 native、自动下载进 `Provider`（下载在 `ModelStore`）、端云路由。
- 权重文件不进仓库、不进 AAR；AAR 只带 `librelay_llama.so`。
- `Qwen25_15B` 只是占位，没有校验和，不能选。
- 真机推理要 arm64；单元测试走 Fake engine。

## 依赖了什么

- **Relay**：[`llm`](../llm/README.md) 的 `Provider`、`ChatRequest`、`ToolDef`。
- **原生**：NDK r27+、CMake 3.22.1、子模块 `third_party/llama.cpp`。

```bash
git submodule update --init --depth 1 third_party/llama.cpp
```

样例流程：playground → 下载 GGUF 到 `filesDir/models` → Load → 发送。
