#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <time.h>
#include <mutex>
#include <string>
#include <vector>

#include "ggml.h"
#include "ggml-cpu.h"
#include "llama.h"

#define LOG_TAG "relay_llama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct EngineState {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    ggml_threadpool_t pool = nullptr;
    std::atomic<bool> cancel{false};
    std::mutex mu;
};

EngineState g_state;
std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, [] {
        llama_backend_init();
    });
}

std::string jstring_to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return out;
}

std::vector<std::string> jstring_array_to_utf8(JNIEnv * env, jobjectArray values) {
    std::vector<std::string> out;
    if (values == nullptr) {
        return out;
    }
    const jsize n = env->GetArrayLength(values);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        out.push_back(jstring_to_utf8(env, item));
        if (item != nullptr) {
            env->DeleteLocalRef(item);
        }
    }
    return out;
}

/**
 * Reads tokenizer.chat_template from the loaded GGUF and runs llama.cpp's
 * built-in (non-Jinja) matcher. Unknown templates fall back to ChatML, which
 * is also what a null template argument selects.
 */
bool apply_chat_template_locked(
        const std::vector<std::string> & roles,
        const std::vector<std::string> & contents,
        std::string & out) {
    if (g_state.model == nullptr || roles.size() != contents.size() || roles.empty()) {
        return false;
    }
    std::vector<llama_chat_message> chat;
    chat.reserve(roles.size());
    for (size_t i = 0; i < roles.size(); ++i) {
        chat.push_back({roles[i].c_str(), contents[i].c_str()});
    }

    const char * tmpl = llama_model_chat_template(g_state.model, /* name */ nullptr);
    int32_t n = llama_chat_apply_template(
            tmpl, chat.data(), chat.size(), /* add_ass */ true, nullptr, 0);
    if (n < 0) {
        LOGI("nativeApplyChatTemplate: GGUF template not in built-in list, falling back to chatml");
        tmpl = nullptr;
        n = llama_chat_apply_template(
                tmpl, chat.data(), chat.size(), true, nullptr, 0);
    }
    if (n < 0) {
        LOGE("nativeApplyChatTemplate: llama_chat_apply_template failed");
        return false;
    }
    out.assign(static_cast<size_t>(n), '\0');
    n = llama_chat_apply_template(
            tmpl, chat.data(), chat.size(), true, out.data(), n);
    if (n < 0) {
        LOGE("nativeApplyChatTemplate: second apply failed");
        return false;
    }
    out.resize(static_cast<size_t>(n));
    LOGI("nativeApplyChatTemplate: %d bytes tmpl=%s", n, tmpl ? "gguf" : "chatml");
    return true;
}

void free_loaded_locked() {
    if (g_state.ctx != nullptr) {
        llama_detach_threadpool(g_state.ctx);
        llama_free(g_state.ctx);
        g_state.ctx = nullptr;
    }
    // Outlives the context on purpose: detaching first guarantees no graph is still
    // referencing the pool when its worker threads are joined.
    if (g_state.pool != nullptr) {
        ggml_threadpool_free(g_state.pool);
        g_state.pool = nullptr;
    }
    if (g_state.model != nullptr) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
    g_state.cancel.store(false);
}

/**
 * Builds the ggml threadpool that runs every graph.
 *
 * The default pool inherits whatever affinity the calling thread had, which lets the
 * kernel park workers on efficiency cores. That is pathological for ggml: its
 * per-operator barrier is an unbounded spin loop, so one slow or descheduled worker
 * makes every other worker burn a full timeslice spinning. Pinning one worker per
 * performance core removes both the slow stragglers and the risk of two spinning
 * workers colliding on a single core.
 */
ggml_threadpool_t make_threadpool_locked(JNIEnv * env, int n_threads, jintArray cpu_indices) {
    ggml_threadpool_params tpp;
    ggml_threadpool_params_init(&tpp, n_threads);

    const jsize n_idx = cpu_indices != nullptr ? env->GetArrayLength(cpu_indices) : 0;
    if (n_idx > 0) {
        std::vector<jint> indices(static_cast<size_t>(n_idx));
        env->GetIntArrayRegion(cpu_indices, 0, n_idx, indices.data());
        bool any = false;
        for (const jint cpu : indices) {
            if (cpu >= 0 && cpu < GGML_MAX_N_THREADS) {
                tpp.cpumask[cpu] = true;
                any = true;
            }
        }
        // Strict placement hands each worker its own core instead of letting them all
        // float across the mask, which two spin-waiting workers must never do.
        tpp.strict_cpu = any;
    }

    // SCHED_FIFO is unavailable to unprivileged Android apps, so anything above NORMAL
    // only produces a failed pthread_setschedparam and a warning.
    tpp.prio = GGML_SCHED_PRIO_NORMAL;

    // Start paused: ggml applies worker 0's affinity to whichever thread drives the
    // first kickoff, and that is the generate thread, not this one.
    tpp.paused = true;

    return ggml_threadpool_new(&tpp);
}

std::string token_to_piece(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /* special */ true);
    if (n < 0) {
        std::string larger(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, token, larger.data(), -n, 0, true);
        if (n < 0) {
            return {};
        }
        larger.resize(static_cast<size_t>(n));
        return larger;
    }
    return std::string(buf, static_cast<size_t>(n));
}

int64_t now_ns() {
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
}

void write_timings(JNIEnv * env, jlongArray out, int64_t prefill_ns, int64_t ttft_ns, int64_t decode_ns) {
    if (env == nullptr || out == nullptr || env->GetArrayLength(out) < 3) {
        return;
    }
    const jlong values[3] = {prefill_ns, ttft_ns, decode_ns};
    env->SetLongArrayRegion(out, 0, 3, values);
}

bool emit_bytes(JNIEnv * env, jobject callback, jmethodID on_token, const std::string & piece) {
    if (piece.empty()) {
        return true;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(piece.size()));
    if (arr == nullptr) {
        LOGE("emit_bytes: NewByteArray failed");
        return false;
    }
    env->SetByteArrayRegion(
            arr,
            0,
            static_cast<jsize>(piece.size()),
            reinterpret_cast<const jbyte *>(piece.data()));
    env->CallVoidMethod(callback, on_token, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_relay_ondevice_engine_JniLlamaEngine_nativeLoad(
        JNIEnv * env,
        jobject /* thiz */,
        jstring model_path,
        jint n_ctx,
        jint n_threads,
        jintArray cpu_indices) {
    ensure_backend();
    const std::string path = jstring_to_utf8(env, model_path);
    if (path.empty()) {
        LOGE("nativeLoad: empty path");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_state.mu);
    free_loaded_locked();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    g_state.model = llama_model_load_from_file(path.c_str(), mparams);
    if (g_state.model == nullptr) {
        LOGE("nativeLoad: failed to load model at %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? static_cast<uint32_t>(n_ctx) : 4096;
    // Larger batch = faster prompt prefills (decode many tokens per call).
    cparams.n_batch = std::min<uint32_t>(cparams.n_ctx, 512);
    cparams.n_ubatch = cparams.n_batch;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    g_state.ctx = llama_init_from_model(g_state.model, cparams);
    if (g_state.ctx == nullptr) {
        LOGE("nativeLoad: failed to create context");
        llama_model_free(g_state.model);
        g_state.model = nullptr;
        return JNI_FALSE;
    }

    g_state.pool = make_threadpool_locked(env, cparams.n_threads, cpu_indices);
    if (g_state.pool == nullptr) {
        // Not fatal: llama falls back to a disposable pool with default placement.
        LOGE("nativeLoad: threadpool creation failed, using default placement");
    } else {
        llama_attach_threadpool(g_state.ctx, g_state.pool, g_state.pool);
    }

    LOGI("nativeLoad: loaded %s (n_ctx=%u threads=%d pinned=%d)",
         path.c_str(),
         cparams.n_ctx,
         cparams.n_threads,
         cpu_indices != nullptr ? env->GetArrayLength(cpu_indices) : 0);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_relay_ondevice_engine_JniLlamaEngine_nativeUnload(
        JNIEnv * /* env */,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_state.mu);
    free_loaded_locked();
}

JNIEXPORT void JNICALL
Java_relay_ondevice_engine_JniLlamaEngine_nativeCancel(
        JNIEnv * /* env */,
        jobject /* thiz */) {
    g_state.cancel.store(true);
}

JNIEXPORT jbyteArray JNICALL
Java_relay_ondevice_engine_JniLlamaEngine_nativeApplyChatTemplate(
        JNIEnv * env,
        jobject /* thiz */,
        jobjectArray roles_j,
        jobjectArray contents_j) {
    const std::vector<std::string> roles = jstring_array_to_utf8(env, roles_j);
    const std::vector<std::string> contents = jstring_array_to_utf8(env, contents_j);

    std::string formatted;
    {
        std::lock_guard<std::mutex> lock(g_state.mu);
        if (!apply_chat_template_locked(roles, contents, formatted)) {
            return nullptr;
        }
    }

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(formatted.size()));
    if (arr == nullptr) {
        LOGE("nativeApplyChatTemplate: NewByteArray failed");
        return nullptr;
    }
    env->SetByteArrayRegion(
            arr,
            0,
            static_cast<jsize>(formatted.size()),
            reinterpret_cast<const jbyte *>(formatted.data()));
    return arr;
}

JNIEXPORT jint JNICALL
Java_relay_ondevice_engine_JniLlamaEngine_nativeGenerate(
        JNIEnv * env,
        jobject /* thiz */,
        jstring prompt_j,
        jint max_tokens,
        jfloat temperature,
        jfloat top_p,
        jobject callback,
        jlongArray timings_ns) {
    if (callback == nullptr) {
        LOGE("nativeGenerate: null callback");
        return -1;
    }

    jclass callback_class = env->GetObjectClass(callback);
    // Kotlin fun interface TokenCallback.onToken(ByteArray)
    jmethodID on_token = env->GetMethodID(callback_class, "onToken", "([B)V");
    if (on_token == nullptr) {
        LOGE("nativeGenerate: missing onToken([B)V");
        env->ExceptionClear();
        return -1;
    }

    const std::string prompt = jstring_to_utf8(env, prompt_j);
    if (prompt.empty()) {
        LOGE("nativeGenerate: empty prompt");
        return -2;
    }

    std::unique_lock<std::mutex> lock(g_state.mu);
    if (g_state.model == nullptr || g_state.ctx == nullptr) {
        LOGE("nativeGenerate: model not loaded");
        return -3;
    }

    g_state.cancel.store(false);

    // Each stream runs on a fresh thread, and ggml only applies worker 0's affinity on
    // the kickoff that follows a pause. Re-arming on the way out keeps every generation
    // pinned, not just the first one.
    struct PausePoolOnExit {
        ~PausePoolOnExit() {
            if (g_state.pool != nullptr) {
                ggml_threadpool_pause(g_state.pool);
            }
        }
    } pause_guard;

    llama_model * model = g_state.model;
    llama_context * ctx = g_state.ctx;
    const llama_vocab * vocab = llama_model_get_vocab(model);

    llama_memory_clear(llama_get_memory(ctx), true);

    // ChatML already embeds special tokens -- do NOT also prepend BOS (add_special=false).
    // parse_special=true so <|im_start|> / <|im_end|> tokenize as specials.
    const int32_t n_prompt_max = static_cast<int32_t>(prompt.size()) + 8;
    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt_max));
    int32_t n_prompt = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(),
            n_prompt_max,
            /* add_special */ false,
            /* parse_special */ true);
    if (n_prompt < 0) {
        prompt_tokens.resize(static_cast<size_t>(-n_prompt));
        n_prompt = llama_tokenize(
                vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                prompt_tokens.data(),
                static_cast<int32_t>(prompt_tokens.size()),
                false,
                true);
    }
    if (n_prompt < 0) {
        LOGE("nativeGenerate: tokenize failed");
        return -4;
    }
    prompt_tokens.resize(static_cast<size_t>(n_prompt));
    LOGI("nativeGenerate: prompt_tokens=%d n_ctx=%u", n_prompt, llama_n_ctx(ctx));

    const uint32_t n_ctx = llama_n_ctx(ctx);
    if (static_cast<uint32_t>(n_prompt) >= n_ctx) {
        LOGE("nativeGenerate: prompt longer than context (%d >= %u)", n_prompt, n_ctx);
        return -5;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    const float temp = temperature > 0.0f ? temperature : 0.7f;
    const float topp = top_p > 0.0f && top_p <= 1.0f ? top_p : 0.9f;
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topp, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Wall-clock slices, flushed on every exit after this point:
    //   prefill = prompt llama_decode only
    //   ttft    = t0 -> first sampled non-EOG token (includes prefill + first sample)
    //   decode  = first token -> last generation llama_decode (subsequent tokens)
    int64_t prefill_ns = 0;
    int64_t ttft_ns = 0;
    int64_t decode_ns = 0;
    int64_t t_first = 0;
    struct FlushTimings {
        JNIEnv * env;
        jlongArray out;
        int64_t * prefill;
        int64_t * ttft;
        int64_t * decode;
        ~FlushTimings() { write_timings(env, out, *prefill, *ttft, *decode); }
    } flush{env, timings_ns, &prefill_ns, &ttft_ns, &decode_ns};

    // Prefill the prompt in n_batch-sized chunks (not one token per decode).
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(ctx));
    const int64_t t0 = now_ns();
    for (int32_t i = 0; i < n_prompt; ) {
        if (g_state.cancel.load()) {
            prefill_ns = now_ns() - t0;
            llama_sampler_free(smpl);
            return -100;
        }
        const int32_t n_eval = std::min(n_batch, n_prompt - i);
        llama_batch batch = llama_batch_get_one(
                prompt_tokens.data() + i,
                n_eval);
        if (llama_decode(ctx, batch) != 0) {
            prefill_ns = now_ns() - t0;
            LOGE("nativeGenerate: prompt decode failed at %d (n_eval=%d)", i, n_eval);
            llama_sampler_free(smpl);
            return -6;
        }
        i += n_eval;
    }
    const int64_t t_prefill_end = now_ns();
    prefill_ns = t_prefill_end - t0;

    // Each generated token occupies one more KV slot. Without this clamp a long
    // max_tokens on a short remaining window dies mid-sentence in llama_decode.
    const int32_t requested_gen = max_tokens > 0 ? max_tokens : 256;
    const int32_t room = static_cast<int32_t>(n_ctx - static_cast<uint32_t>(n_prompt));
    const int32_t max_gen = std::min(requested_gen, room);
    if (max_gen < requested_gen) {
        LOGI("nativeGenerate: clamp max_gen %d -> %d (n_ctx=%u n_prompt=%d)",
             requested_gen, max_gen, n_ctx, n_prompt);
    }
    int32_t n_generated = 0;

    while (n_generated < max_gen) {
        if (g_state.cancel.load()) {
            if (t_first != 0) {
                decode_ns = now_ns() - t_first;
            }
            llama_sampler_free(smpl);
            return -100;
        }

        llama_token token = llama_sampler_sample(smpl, ctx, -1);
        llama_sampler_accept(smpl, token);

        if (llama_vocab_is_eog(vocab, token)) {
            if (t_first == 0) {
                ttft_ns = now_ns() - t0;
            } else {
                decode_ns = now_ns() - t_first;
            }
            break;
        }

        if (t_first == 0) {
            t_first = now_ns();
            ttft_ns = t_first - t0;
        }

        const std::string piece = token_to_piece(vocab, token);
        if (!piece.empty()) {
            lock.unlock();
            const bool ok = emit_bytes(env, callback, on_token, piece);
            lock.lock();
            if (!ok) {
                decode_ns = now_ns() - t_first;
                g_state.cancel.store(true);
                llama_sampler_free(smpl);
                return -9;
            }
            if (g_state.model != model || g_state.ctx != ctx) {
                decode_ns = now_ns() - t_first;
                llama_sampler_free(smpl);
                return -7;
            }
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, batch) != 0) {
            decode_ns = now_ns() - t_first;
            LOGE("nativeGenerate: generation decode failed");
            llama_sampler_free(smpl);
            return -8;
        }
        ++n_generated;
    }

    if (t_first != 0 && decode_ns == 0) {
        decode_ns = now_ns() - t_first;
    }

    llama_sampler_free(smpl);
    const double prefill_s = prefill_ns / 1e9;
    const double decode_s = decode_ns / 1e9;
    const double prefill_tps = prefill_s > 0.0 ? static_cast<double>(n_prompt) / prefill_s : 0.0;
    const int32_t decode_tokens = n_generated > 0 ? n_generated : 0;
    const double decode_tps = decode_s > 0.0 ? static_cast<double>(decode_tokens) / decode_s : 0.0;
    LOGI("nativeGenerate: done prompt=%d completion=%d prefill_ms=%lld ttft_ms=%lld decode_ms=%lld "
         "prefill_tps=%.1f decode_tps=%.1f",
         n_prompt,
         n_generated,
         static_cast<long long>(prefill_ns / 1000000),
         static_cast<long long>(ttft_ns / 1000000),
         static_cast<long long>(decode_ns / 1000000),
         prefill_tps,
         decode_tps);
    const int prompt_part = std::min(n_prompt, 0xFFFF);
    const int completion_part = std::min(n_generated, 0xFFFF);
    return (prompt_part << 16) | completion_part;
}

}  // extern "C"
