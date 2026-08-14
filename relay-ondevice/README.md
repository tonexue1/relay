# relay-ondevice

End-side `Provider` backed by llama.cpp (arm64 CPU via JNI).

## Requirements

- Android NDK r27+ (AGP downloads `ndkVersion` from `build.gradle.kts` when missing)
- CMake 3.22.1+
- arm64 device/emulator for real inference (unit tests use a Fake engine)

## Submodule

```bash
git submodule update --init --depth 1 third_party/llama.cpp
```

## Demo flow

1. Open **relay-ondevice** in the demo app
2. Download Qwen2.5-3B Q4_K_M (~1.8 GB) into app `filesDir/models`
3. Load → Send (stream or unary)
