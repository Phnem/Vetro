// JNI-мост Vetro → whisper.cpp. Звук приходит 16 кГц моно float; наружу — язык и реплики
// «t0_ms\tt1_ms\tтекст». Контекст модели создаётся один раз (nativeInit) и живёт до nativeFree.
#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

namespace {

struct ProgressBridge {
    JavaVM *vm;
    jobject sink;
    jmethodID method;
};

void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *user) {
    auto *bridge = static_cast<ProgressBridge *>(user);
    JNIEnv *env = nullptr;
    if (bridge->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) return;
    env->CallVoidMethod(bridge->sink, bridge->method, progress);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_myapplication_media_subtitles_whisper_WhisperCppEngine_nativeInit(JNIEnv *env, jclass, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    // GPU-бэкендов в сборке нет: на Android стабилен CPU (NEON, dotprod).
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_media_subtitles_whisper_WhisperCppEngine_nativeFree(JNIEnv *, jclass, jlong context) {
    if (context != 0) whisper_free(reinterpret_cast<whisper_context *>(context));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_myapplication_media_subtitles_whisper_WhisperCppEngine_nativeTranscribe(
        JNIEnv *env, jclass, jlong context, jfloatArray pcm, jstring language, jint threads, jboolean words, jobject progress_sink) {
    auto *ctx = reinterpret_cast<whisper_context *>(context);
    const jsize count = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(count));
    env->GetFloatArrayRegion(pcm, 0, count, samples.data());

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string lang_code(lang);
    env->ReleaseStringUTFChars(language, lang);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang_code == "auto" ? "auto" : lang_code.c_str();
    params.detect_language = false;
    params.translate = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    // Реплики — по фразам, а не одним куском на 30 секунд. Пословно (words) — для синхронизации с
    // текстом книги: каждое слово со своими акустическими метками, а не раскладка фразы поровну.
    params.token_timestamps = words == JNI_TRUE;
    params.max_len = words == JNI_TRUE ? 1 : 0;
    params.split_on_word = words == JNI_TRUE;
    params.suppress_blank = true;
    params.no_context = true;

    JavaVM *vm = nullptr;
    env->GetJavaVM(&vm);
    jclass sink_class = env->GetObjectClass(progress_sink);
    ProgressBridge bridge{vm, progress_sink, env->GetMethodID(sink_class, "onProgress", "(I)V")};
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &bridge;

    std::vector<std::string> lines;
    if (whisper_full(ctx, params, samples.data(), static_cast<int>(samples.size())) == 0) {
        const int detected = whisper_full_lang_id(ctx);
        lines.emplace_back(detected >= 0 ? whisper_lang_str(detected) : "");
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) {
            // Метки whisper — в сотых долях секунды.
            const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
            const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
            lines.emplace_back(std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + whisper_full_get_segment_text(ctx, i));
        }
    } else {
        lines.emplace_back("");
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(lines.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < lines.size(); ++i) {
        jstring s = env->NewStringUTF(lines[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return result;
}
