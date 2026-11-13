// JNI bridge to whisper.cpp: load a ggml model, transcribe 16 kHz mono float PCM, free.
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <thread>
#include <android/log.h>
#include "whisper.h"
#include "ggml-backend.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VoiceTyperWhisper", __VA_ARGS__)

namespace {
std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::atomic<bool> g_abort{false};

bool abort_cb(void * /*user_data*/) { return g_abort.load(); }

#ifdef VT_BACKEND_DL
std::string g_backend_file;  // e.g. libggml-cpu-android_armv8.6_1.so

// ggml logs to stderr by default (lost on Android): send it to logcat and remember which backend was loaded
void ggml_to_logcat(enum ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, "VoiceTyperGgml", "%s", text);
    std::string t(text ? text : "");
    auto pos = t.find("libggml-cpu-");
    if (t.find("loaded") != std::string::npos && pos != std::string::npos) {
        auto end = t.find(".so", pos);
        g_backend_file = t.substr(pos, end == std::string::npos ? std::string::npos : end - pos);
    }
}
#endif
}  // namespace

extern "C" {

/**
 * arm64: loads the best ggml CPU backend for this processor (libggml-cpu-android_armv*.so in the app's native
 * library folder). Must run once before the first model. Returns the loaded backend's name ("" on 32-bit builds).
 */
JNIEXPORT jstring JNICALL
Java_com_hugocodings_voicetyper_WhisperNative_loadBackends(JNIEnv *env, jclass, jstring dir) {
#ifdef VT_BACKEND_DL
    static bool loaded = false;
    if (!loaded) {
        std::string d = jstr(env, dir);
        ggml_log_set(ggml_to_logcat, nullptr);
        ggml_backend_load_all_from_path(d.c_str());
        loaded = true;
    }
    std::string out = g_backend_file.empty() ? std::string("aucun backend CPU chargé") : g_backend_file;
    if (ggml_backend_reg_count() == 0) out = "aucun backend";
    LOGI("backend: %s", out.c_str());
    return env->NewStringUTF(out.c_str());
#else
    (void) dir;
    return env->NewStringUTF("");
#endif
}

JNIEXPORT jlong JNICALL
Java_com_hugocodings_voicetyper_WhisperNative_init(JNIEnv *env, jclass, jstring path) {
    std::string p = jstr(env, path);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p.c_str(), cp);
    LOGI("init %s -> %p", p.c_str(), (void *) ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_hugocodings_voicetyper_WhisperNative_free(JNIEnv *, jclass, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

JNIEXPORT void JNICALL
Java_com_hugocodings_voicetyper_WhisperNative_abort(JNIEnv *, jclass) { g_abort = true; }

JNIEXPORT jstring JNICALL
Java_com_hugocodings_voicetyper_WhisperNative_systemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

/** language: "fr", "en" or "auto"; prompt: vocabulary hint (may be null). Returns null on failure. */
JNIEXPORT jstring JNICALL
Java_com_hugocodings_voicetyper_WhisperNative_transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray pcm,
                                                         jstring language, jstring prompt, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx || !pcm) return nullptr;
    g_abort = false;
    std::string lang = jstr(env, language);
    std::string hint = jstr(env, prompt);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    unsigned hw = std::thread::hardware_concurrency();
    wp.n_threads = threads > 0 ? threads : (int) std::max(1u, std::min(4u, hw == 0 ? 4u : hw));
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.translate = false;
    wp.no_timestamps = true;
    wp.no_context = true;
    wp.suppress_blank = true;
    wp.language = lang.empty() ? "auto" : lang.c_str();
    wp.detect_language = false;
    wp.initial_prompt = hint.empty() ? nullptr : hint.c_str();
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = nullptr;

    jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    int rc = whisper_full(ctx, wp, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (rc != 0) {
        LOGI("whisper_full failed: %d", rc);
        return nullptr;
    }
    std::string text;
    const int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; ++i) text += whisper_full_get_segment_text(ctx, i);
    return env->NewStringUTF(text.c_str());
}

}  // extern "C"
