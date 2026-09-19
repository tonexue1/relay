# Relay

Android 原生的 Agent 运行时：**运行时、私有记忆、原生 UI 住在设备里，模型放在云端。**

对标位置接近 OkHttp / Coil——给 App 开发者 `implementation` 进去的基础库，而不是又一个聊天套壳。当前版本 **0.1.0**，以 Gradle 多模块源码集成，尚未发布到 Maven Central。

## 能做什么

- **端云同一套 `Provider`**：云端 OpenAI 兼容接口（含 DeepSeek）和端上 llama.cpp 都实现同一个聊天/流式端口。
- **Agent loop**：工具调用、上下文裁剪、记忆注入、可恢复的选择表单。
- **端上记忆**：跨会话事实与检索，图/账本沉淀在设备，不跟某一家模型绑死。
- **原生 UI**：Compose 组件把模型产出的 spec 渲进对话（Markdown、卡片、选择表），而不是塞进 WebView。
- **多 Agent 编排**：Pipeline、Supervisor、GroupChat 等拓扑，由 `orchestra` 提供。

更完整的定位见 [docs/vision.md](docs/vision.md)，分层设计见 [docs/architecture.md](docs/architecture.md)。

## 模块

| 模块 | 职责 |
|---|---|
| `relay/llm` | `Provider` 端口、OpenAI 兼容云实现、拦截器 |
| `relay/ondevice` | llama.cpp（arm64 JNI）端侧推理，可选 |
| `relay/agent-core` | Agent 循环、工具、上下文编排 |
| `relay/memory` | 端上记忆存储与检索 |
| `relay/orchestra` | 多 Agent 拓扑 |
| `relay/artifacts` | 生成物模型与校验 |
| `relay/ui-kit` | Compose 对话 / 控件 |

样例 App（`samples/`）：

| 样例 | 说明 |
|---|---|
| `assistant` | 带记忆的助理 |
| `playground` | 按模块调试（llm / ondevice / agent / memory） |
| `clip` | 选中文本 → 改写 / 深挖 |
| `werewolf` | 多角色编排演示 |

## 环境

- JDK 17+（Android Studio 自带 JBR 即可）
- Android SDK 37，minSdk 28
- 端侧推理还需要 NDK r27+、CMake 3.22.1+，以及 llama.cpp 子模块
- 真机/模拟器跑端侧模型需要 **arm64**

```bash
git clone --recurse-submodules git@github.com:tonexue1/relay.git
cd relay
# 若已经 clone 过：
git submodule update --init --depth 1 third_party/llama.cpp
```

把 Android SDK 路径写进根目录 `local.properties`（此文件已 gitignore，不要提交）：

```
sdk.dir=/Users/you/Library/Android/sdk
```

云端样例可额外写入（仅本机调试，会打进 debug APK 的 BuildConfig）：

```
relay.deepseek.apiKey=sk-...
```

没有 key 时，在 App 里手动填写即可。

## 编译

使用仓库自带的 Gradle Wrapper。

```bash
# 库与 JVM 测试
./gradlew test

# 单个模块
./gradlew :relay:memory:test
./gradlew :samples:assistant:testDebugUnitTest

# 可安装的 debug APK
./gradlew :samples:assistant:assembleDebug
./gradlew :samples:playground:assembleDebug
```

产物默认在：

- `samples/assistant/build/outputs/apk/debug/assistant-debug.apk`
- `samples/playground/build/outputs/apk/debug/playground-debug.apk`

`ondevice` 会编 llama.cpp 原生库，第一次较慢。只做云端链路时仍建议带上子模块，避免 Android 模块配置失败。

## 在应用里引用

当前按源码模块依赖，例如：

```kotlin
implementation(project(":relay:llm"))
implementation(project(":relay:agent-core"))
implementation(project(":relay:memory"))
implementation(project(":relay:ui-kit"))
```

`Provider` 只表示「一个模型后端」。缓存、重试、指标走拦截器；端云路由是宿主策略，不写进 `Provider`。

```kotlin
interface Provider {
    val info: ProviderInfo
    suspend fun chat(request: ChatRequest): ChatResponse
    fun stream(request: ChatRequest): Flow<ChatChunk>
}
```

## 许可

[Apache License 2.0](LICENSE)
