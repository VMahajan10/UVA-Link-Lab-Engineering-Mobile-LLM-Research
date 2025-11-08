#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.cpp/include/llama.h"

#define TAG "LLamaJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct llama_context_wrapper {
    llama_model* model;
    llama_context* ctx;
};

// Helper: Convert jstring to C++ string
std::string jstring2string(JNIEnv* env, jstring jStr) {
    if (!jStr) return "";
    const char* chars = env->GetStringUTFChars(jStr, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    return str;
}

// Helper: Clear batch
void batch_clear(llama_batch& batch) {
    batch.n_tokens = 0;
}

// Helper: Add token to batch
void batch_add(llama_batch& batch, llama_token id, llama_pos pos, const std::vector<llama_seq_id>& seq_ids, bool logits) {
    if (batch.n_tokens >= 512) {
        LOGE("Batch size exceeded");
        return;
    }
    batch.token[batch.n_tokens] = id;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = seq_ids.size();
    for (size_t i = 0; i < seq_ids.size(); i++) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits[batch.n_tokens] = logits;
    batch.n_tokens++;
}

// Helper: Tokenize text
std::vector<llama_token> tokenize(const llama_vocab* vocab, const std::string& text, bool add_special) {
    int n_tokens = text.length() + 2 * add_special;
    std::vector<llama_token> result(n_tokens);
    n_tokens = llama_tokenize(vocab, text.data(), text.length(), result.data(), result.size(), add_special, false);
    if (n_tokens < 0) {
        result.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, text.data(), text.length(), result.data(), result.size(), add_special, false);
    }
    result.resize(n_tokens);
    return result;
}

// Helper: Convert token to piece
std::string token_to_piece(const llama_vocab* vocab, llama_token token) {
    std::string piece;
    piece.resize(256);
    int n_chars = llama_token_to_piece(vocab, token, &piece[0], piece.size(), 0, false);
    if (n_chars < 0) {
        piece.resize(-n_chars);
        n_chars = llama_token_to_piece(vocab, token, &piece[0], piece.size(), 0, false);
    } else {
        piece.resize(n_chars);
    }
    return piece;
}

extern "C" {

// Initialize llama.cpp with model
JNIEXPORT jlong JNICALL
Java_com_research_llmbattery_LLMService_nativeInit(
    JNIEnv* env, 
    jobject /* this */,
    jstring jModelPath,
    jint nThreads,
    jint nCtx
) {
    std::string modelPath = jstring2string(env, jModelPath);
    LOGD("Initializing model: %s", modelPath.c_str());
    
    // Initialize llama backend
    llama_backend_init();
    
    // Load model (updated API)
    llama_model_params model_params = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(modelPath.c_str(), model_params);
    
    if (!model) {
        LOGE("Failed to load model from %s", modelPath.c_str());
        return 0;
    }
    
    LOGD("Model loaded successfully");
    
    // Create context (updated API)
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    
    llama_context* ctx = llama_init_from_model(model, ctx_params);
    
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }
    
    LOGD("Context created successfully");
    
    // Create wrapper
    auto* wrapper = new llama_context_wrapper();
    wrapper->model = model;
    wrapper->ctx = ctx;
    
    return reinterpret_cast<jlong>(wrapper);
}

// Generate text
JNIEXPORT jstring JNICALL
Java_com_research_llmbattery_LLMService_nativeGenerate(
    JNIEnv* env,
    jobject /* this */,
    jlong contextPtr,
    jstring jPrompt,
    jint maxTokens
) {
    if (contextPtr == 0) {
        LOGE("Invalid context pointer");
        return env->NewStringUTF("Error: Invalid context");
    }
    
    auto* wrapper = reinterpret_cast<llama_context_wrapper*>(contextPtr);
    std::string prompt = jstring2string(env, jPrompt);
    
    LOGD("Generating response for prompt: %s", prompt.c_str());
    
    // Get vocab
    const llama_vocab* vocab = llama_model_get_vocab(wrapper->model);
    
    // Tokenize prompt
    std::vector<llama_token> tokens = tokenize(vocab, prompt, true);
    int n_tokens = tokens.size();
    
    LOGD("Tokenized prompt: %d tokens", n_tokens);
    
    // Generate response
    std::string response;
    
    // Create batch
    llama_batch batch = llama_batch_init(512, 0, 1);
    
    // Add prompt tokens
    for (int i = 0; i < n_tokens; i++) {
        batch_add(batch, tokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;
    
    // Decode prompt
    if (llama_decode(wrapper->ctx, batch) != 0) {
        LOGE("Failed to decode prompt");
        llama_batch_free(batch);
        return env->NewStringUTF("Error: Failed to decode");
    }
    
    // Generate tokens
    int n_generated = 0;
    int n_vocab = llama_vocab_n_tokens(vocab);
    
    while (n_generated < maxTokens) {
        // Sample next token (greedy)
        auto* logits = llama_get_logits_ith(wrapper->ctx, batch.n_tokens - 1);
        
        llama_token new_token_id = 0;
        float max_logit = logits[0];
        for (int i = 1; i < n_vocab; i++) {
            if (logits[i] > max_logit) {
                max_logit = logits[i];
                new_token_id = i;
            }
        }
        
        // Check for EOS (updated API)
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }
        
        // Decode token to text
        std::string piece = token_to_piece(vocab, new_token_id);
        response += piece;
        
        // Prepare next batch
        batch_clear(batch);
        batch_add(batch, new_token_id, n_tokens + n_generated, {0}, true);
        
        // Decode
        if (llama_decode(wrapper->ctx, batch) != 0) {
            LOGE("Failed to decode token");
            break;
        }
        
        n_generated++;
    }
    
    llama_batch_free(batch);
    
    LOGD("Generated %d tokens", n_generated);
    
    return env->NewStringUTF(response.c_str());
}

// Free resources
JNIEXPORT void JNICALL
Java_com_research_llmbattery_LLMService_nativeFree(
    JNIEnv* env,
    jobject /* this */,
    jlong contextPtr
) {
    if (contextPtr == 0) return;
    
    auto* wrapper = reinterpret_cast<llama_context_wrapper*>(contextPtr);
    
    if (wrapper->ctx) {
        llama_free(wrapper->ctx);
    }
    if (wrapper->model) {
        llama_model_free(wrapper->model);
    }
    
    delete wrapper;
    
    llama_backend_free();
    
    LOGD("Resources freed");
}

} // extern "C"
