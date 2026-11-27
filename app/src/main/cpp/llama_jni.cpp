// The Structure — JNI bridge to llama.cpp v1.1.0.
//
// One model, one context. The Kotlin side (LlamaBridge) owns the lifecycle:
//   nativeInit() -> nativeLoadModel(path, ctxSize, threads)
//   -> nativeGenerate(roles, contents, maxTokens, temperature, topP) [blocking,
//      streams tokens back through LlamaBridge.onTokenGenerated(String)]
//   -> nativeStop() to cancel a running generation
//   -> nativeFree() on shutdown.
//
// The model's own chat template is applied here (fetched with
// llama_model_chat_template, passed to llama_chat_apply_template),
// so the prompt always matches what the model was trained with. Generation uses the new v1.1.0 batch API
// (llama_batch_ext_* + llama_process), not the deprecated llama_batch API.
#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstdlib>
#include <ctime>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "HeyGobiLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
const llama_vocab *g_vocab = nullptr;
llama_context_params g_ctx_params{};
std::atomic<bool> g_stop{false};

JavaVM *g_vm = nullptr;
jobject g_bridge = nullptr; // global ref to com.heygobi.app.brain.LlamaBridge
jmethodID g_on_token = nullptr;    // onTokenGenerated(Ljava/lang/String;)V
jmethodID g_on_progress = nullptr; // onLoadProgress(F)V
jmethodID g_on_finished = nullptr; // onGenerationFinished()V

JNIEnv *env_for_current_thread() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return nullptr;
    }
    return env;
}

void call_on_token(const char *text, int32_t len) {
    JNIEnv *env = env_for_current_thread();
    if (env == nullptr || g_bridge == nullptr || g_on_token == nullptr) return;
    jstring jtext = env->NewStringUTF(std::string(text, static_cast<size_t>(len)).c_str());
    if (jtext == nullptr) return;
    env->CallVoidMethod(g_bridge, g_on_token, jtext);
    env->DeleteLocalRef(jtext);
}

void call_on_progress(float progress) {
    JNIEnv *env = env_for_current_thread();
    if (env == nullptr || g_bridge == nullptr || g_on_progress == nullptr) return;
    env->CallVoidMethod(g_bridge, g_on_progress, progress);
}

void call_on_finished() {
    JNIEnv *env = env_for_current_thread();
    if (env == nullptr || g_bridge == nullptr || g_on_finished == nullptr) return;
    env->CallVoidMethod(g_bridge, g_on_finished);
}

bool load_progress_callback(float progress, void * /*user_data*/) {
    call_on_progress(progress);
    return true; // keep loading
}

std::string jstring_to_std(JNIEnv *env, jstring jstr) {
    if (jstr == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_heygobi_app_brain_LlamaBridge_nativeInit(JNIEnv *env, jobject thiz) {
    env->GetJavaVM(&g_vm);
    if (g_bridge != nullptr) {
        env->DeleteGlobalRef(g_bridge);
        g_bridge = nullptr;
    }
    g_bridge = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_on_token = env->GetMethodID(cls, "onTokenGenerated", "(Ljava/lang/String;)V");
    g_on_progress = env->GetMethodID(cls, "onLoadProgress", "(F)V");
    g_on_finished = env->GetMethodID(cls, "onGenerationFinished", "()V");
    llama_backend_init();
    std::srand(static_cast<unsigned>(std::time(nullptr)));
    LOGI("nativeInit done");
}

JNIEXPORT jboolean JNICALL
Java_com_heygobi_app_brain_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/, jstring jpath, jint ctx_size, jint n_threads) {
    const std::string path = jstring_to_std(env, jpath);
    if (path.empty()) return JNI_FALSE;

    // Free any previously loaded model first.
    if (g_ctx != nullptr) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only: this phone has no usable GPU backend
    mparams.progress_callback = load_progress_callback;
    mparams.progress_callback_user_data = nullptr;

    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (g_model == nullptr) {
        LOGE("llama_model_load_from_file failed: %s", path.c_str());
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);

    g_ctx_params = llama_context_default_params();
    g_ctx_params.n_ctx = static_cast<uint32_t>(ctx_size);
    g_ctx_params.n_batch = 512;
    g_ctx_params.n_threads = n_threads;
    g_ctx_params.n_threads_batch = n_threads;

    g_ctx = llama_init_from_model(g_model, g_ctx_params);
    if (g_ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        return JNI_FALSE;
    }
    LOGI("model loaded: %s (ctx=%d threads=%d)", path.c_str(), ctx_size, n_threads);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_heygobi_app_brain_LlamaBridge_nativeIsLoaded(JNIEnv * /*env*/, jobject /*thiz*/) {
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_heygobi_app_brain_LlamaBridge_nativeStop(JNIEnv * /*env*/, jobject /*thiz*/) {
    g_stop.store(true);
}

// Generate a reply for the given chat history.
// roles[i]/contents[i] are parallel arrays (e.g. "system", "user", "assistant").
// Returns the number of generated tokens, or a negative error code:
//   -1 model not loaded, -2 prompt too long for the context, -3 decode error.
JNIEXPORT jint JNICALL
Java_com_heygobi_app_brain_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/,
        jobjectArray jroles, jobjectArray jcontents,
        jint max_tokens, jfloat temperature, jfloat top_p) {
    if (g_model == nullptr || g_ctx == nullptr || g_vocab == nullptr) return -1;
    const jsize n_msg = env->GetArrayLength(jroles);
    if (n_msg <= 0 || n_msg != env->GetArrayLength(jcontents)) return -1;

    // Build the llama_chat_message array (strings owned by this scope).
    std::vector<std::string> role_strs;
    std::vector<std::string> content_strs;
    std::vector<llama_chat_message> msgs;
    role_strs.reserve(static_cast<size_t>(n_msg));
    content_strs.reserve(static_cast<size_t>(n_msg));
    msgs.reserve(static_cast<size_t>(n_msg));
    for (jsize i = 0; i < n_msg; i++) {
        auto jrole = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto jcontent = static_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
        role_strs.push_back(jstring_to_std(env, jrole));
        content_strs.push_back(jstring_to_std(env, jcontent));
        env->DeleteLocalRef(jrole);
        env->DeleteLocalRef(jcontent);
        msgs.push_back(llama_chat_message{role_strs.back().c_str(), content_strs.back().c_str()});
    }

    // Apply the model's own chat template (add_ass=true: end with the
    // assistant turn start). Buffer sized per the API docs.
    // v1.1.0: llama_chat_apply_template takes the template string itself
    // (not the model). The model's template comes from
    // llama_model_chat_template(); a null template makes the function
    // fall back to the built-in "chatml" template.
    const char * tmpl = llama_model_chat_template(g_model, nullptr);
    size_t total_chars = 0;
    for (const auto &c : content_strs) total_chars += c.size();
    int32_t buf_size = static_cast<int32_t>(2 * total_chars + 1024);
    std::vector<char> buf(static_cast<size_t>(buf_size));
    int32_t prompt_len = llama_chat_apply_template(
        tmpl, msgs.data(), static_cast<size_t>(n_msg),
        true, buf.data(), buf_size);
    if (prompt_len > buf_size) {
        buf.assign(static_cast<size_t>(prompt_len) + 1, '\0');
        prompt_len = llama_chat_apply_template(
            tmpl, msgs.data(), static_cast<size_t>(n_msg),
            true, buf.data(), prompt_len + 1);
    }
    if (prompt_len <= 0) {
        LOGE("llama_chat_apply_template failed");
        return -3;
    }

    // Tokenize the formatted prompt.
    const int32_t n_ctx = static_cast<int32_t>(g_ctx_params.n_ctx);
    std::vector<llama_token> prompt_tokens(static_cast<size_t>(prompt_len) + 64);
    const int32_t n_prompt = llama_tokenize(
        g_vocab, buf.data(), prompt_len, prompt_tokens.data(),
        static_cast<int32_t>(prompt_tokens.size()), true, false);
    if (n_prompt <= 0) {
        LOGE("llama_tokenize failed");
        return -3;
    }
    prompt_tokens.resize(static_cast<size_t>(n_prompt));
    if (n_prompt > n_ctx - max_tokens - 16) {
        LOGE("prompt too long: %d tokens, ctx %d", n_prompt, n_ctx);
        return -2;
    }

    // Fresh context for this turn (simplest correct KV handling).
    llama_free(g_ctx);
    g_ctx = llama_init_from_model(g_model, g_ctx_params);
    if (g_ctx == nullptr) {
        LOGE("context re-init failed");
        return -3;
    }

    // Feed the prompt.
    llama_batch_ext *batch = llama_batch_ext_init(g_ctx);
    for (int32_t i = 0; i < n_prompt; i++) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, prompt_tokens[static_cast<size_t>(i)]);
        if (idx < 0) { llama_batch_ext_free(batch); return -3; }
        const llama_pos pos = i;
        llama_batch_ext_set_pos(batch, idx, &pos);
        if (i == n_prompt - 1) llama_batch_ext_set_output_logits(batch, idx, true);
    }
    if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
        LOGE("llama_process (prompt) failed");
        llama_batch_ext_free(batch);
        return -3;
    }

    // Sampler chain: top-k, top-p, min-p, temperature, then the sampler.
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *chain = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(std::rand())));

    g_stop.store(false);
    int32_t n_generated = 0;
    int32_t n_past = n_prompt;
    char piece[256];
    while (n_generated < max_tokens) {
        if (g_stop.load()) break;
        const llama_token tok = llama_sampler_sample(chain, g_ctx, -1);
        if (tok == LLAMA_TOKEN_NULL) break;
        if (llama_vocab_is_eog(g_vocab, tok)) break;
        const int32_t piece_len = llama_token_to_piece(g_vocab, tok, piece, sizeof(piece), 0, false);
        if (piece_len > 0) call_on_token(piece, piece_len);
        n_generated++;

        llama_batch_ext_clear(batch);
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tok);
        if (idx < 0) break;
        const llama_pos pos = n_past;
        llama_batch_ext_set_pos(batch, idx, &pos);
        llama_batch_ext_set_output_logits(batch, idx, true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) break;
        n_past++;
    }

    llama_sampler_free(chain);
    llama_batch_ext_free(batch);
    call_on_finished();
    return n_generated;
}

JNIEXPORT jint JNICALL
Java_com_heygobi_app_brain_LlamaBridge_nativeTokenCount(
        JNIEnv *env, jobject /*thiz*/, jstring jtext) {
    if (g_vocab == nullptr) return -1;
    const std::string text = jstring_to_std(env, jtext);
    std::vector<llama_token> tokens(text.size() + 64);
    const int32_t n = llama_tokenize(
        g_vocab, text.c_str(), static_cast<int32_t>(text.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), true, false);
    return n;
}

JNIEXPORT void JNICALL
Java_com_heygobi_app_brain_LlamaBridge_nativeFree(JNIEnv *env, jobject /*thiz*/) {
    if (g_ctx != nullptr) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    if (g_bridge != nullptr) {
        env->DeleteGlobalRef(g_bridge);
        g_bridge = nullptr;
    }
    LOGI("nativeFree done");
}

} // extern "C"
