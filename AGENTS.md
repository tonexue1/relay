# Repository Guidelines

## Project Structure & Module Organization

Relay is a Gradle multi-module Kotlin/Android project. Core libraries live in `relay/`: `llm` provides model and tool clients, `agent-core` defines agent primitives, `orchestra` coordinates execution, `memory` persists and retrieves memory, `artifacts` models generated output, `ondevice` wraps native inference, and `ui-kit` supplies Compose UI tools. Android sample apps are under `samples/` (`assistant`, `playground`, `clip`, `cursor`, and `werewolf`). Production and unit-test code follow `src/main/kotlin` and `src/test/kotlin`; Android resources live in `src/main/res`. Design notes and evaluations belong in `docs/` and `eval/`.

## Build, Test, and Development Commands

- `./gradlew test` runs the unit-test suite for all modules.
- `./gradlew :relay:memory:test` runs one library's tests while iterating.
- `./gradlew :samples:assistant:testDebugUnitTest` runs an Android sample's JVM tests.
- `./gradlew :samples:playground:assembleDebug` builds an installable debug APK.
- `./gradlew build` compiles and verifies the entire workspace; use it before a broad change is submitted.

Use the checked-in Gradle wrapper. The project targets Java 17; Android modules use SDK 37 and have a minimum SDK of 28. Native `ondevice` builds require the configured Android NDK/CMake toolchain.

## Coding Style & Naming Conventions

Follow Kotlin's official style (`kotlin.code.style=official`): four-space indentation, idiomatic null handling, and trailing commas where surrounding code uses them. Use `PascalCase` for types and Compose screens, `camelCase` for functions/properties, and lowercase package paths rooted at `relay.*`. Keep public APIs in focused packages (for example, `relay.memory.api`) and place implementation details in `engine`, `model`, or similarly specific packages. No standalone formatter is configured; match nearby code and let IDE Kotlin formatting handle whitespace.

## Testing Guidelines

Add or update a focused unit test for behavioral changes. Name tests `*Test.kt` and keep their package parallel to production code, e.g. `relay/memory/src/test/kotlin/relay/memory/api/Mem0FactTest.kt`. JVM modules use Kotlin Test/JUnit Platform; Android modules use JUnit and may use Robolectric. Tests must be deterministic: mock HTTP through MockWebServer and avoid real API calls unless an opt-in evaluation property explicitly enables them.

## Commit & Pull Request Guidelines

Use concise Conventional Commit-style subjects seen in history: `feat: add ...`, `fix: ...`, `refactor: ...`, or `docs: ...`. Keep each commit scoped to one concern. PRs should state the affected modules, explain the behavior change, list commands run, link any relevant issue/design document, and include screenshots or recordings for Compose/UI changes. Do not commit API keys: store local development values only in the gitignored `local.properties`.
