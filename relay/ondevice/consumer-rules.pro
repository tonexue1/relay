# Keep JNI entry points for librelay_llama.so
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class relay.ondevice.engine.JniLlamaEngine { *; }
