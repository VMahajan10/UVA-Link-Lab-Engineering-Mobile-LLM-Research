#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include <vector>
#include <cstring>

// llama.cpp headers
#include "llama.h"

// Android logging macros
#define LOG_TAG "llama-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Structure to hold both model and context pointers
struct LlamaContext {
    llama_model* model;
    llama_context* ctx;
    
    LlamaContext() : model(nullptr), ctx(nullptr) {}
    
    ~LlamaContext() {
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_free_model(model);
            model = nullptr;
        }
    }
    
    bool isValid() const {
        return model != nullptr && ctx != nullptr;
    }
};

// Helper function to convert jstring to std::string
std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) {
        return "";
    }
    
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    if (cstr == nullptr) {
        return "";
    }
    
    std::string result(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return result;
}

// Helper function to convert std::string to jstring
jstring string_to_jstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// Helper function to safely cast jlong to LlamaContext pointer
LlamaContext* jlong_to_context(jlong ptr) {
    return reinterpret_cast<LlamaContext*>(ptr);
}

// Helper function to safely cast LlamaContext pointer to jlong
jlong context_to_jlong(LlamaContext* ctx) {
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Initialize llama.cpp context with the given model path
 * 
 * JNI Name Mangling: Java_com_research_llmbattery_LLMService_initializeNative
 * - Java_ prefix for all JNI functions
 * - com_research_llmbattery_LLMService: Full class name with underscores
 * - initializeNative: Method name
 * 
 * @param env JNI environment
 * @param obj Java object instance
 * @param modelPath Path to the GGUF model file
 * @return Pointer to llama context as jlong, or 0 if failed
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_research_llmbattery_LLMService_initializeNative(
    JNIEnv* env,
    jobject obj,
    jstring modelPath
) {
    LOGI("Initializing llama context with model path");
    
    try {
        // Convert Java string to C++ string
        std::string model_path = jstring_to_string(env, modelPath);
        if (model_path.empty()) {
            LOGE("Model path is empty");
            return 0;
        }
        
        LOGD("Model path: %s", model_path.c_str());
        
        // Initialize llama backend
        llama_backend_init();
        LOGD("Initialized llama backend");
        
        // Set model parameters
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0; // CPU only for mobile
        
        // Load model
        llama_model* model = llama_load_model_from_file(model_path.c_str(), model_params);
        if (model == nullptr) {
            LOGE("Failed to load model from: %s", model_path.c_str());
            llama_backend_free();
            return 0;
        }
        
        LOGI("Successfully loaded model");
        
        // Set context parameters optimized for mobile
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.seed = 42;
        ctx_params.ctx_size = 2048;  // Context window size
        ctx_params.n_batch = 512;    // Batch size
        ctx_params.n_threads = 4;    // Number of threads for mobile
        ctx_params.n_ctx = 2048;     // Context size
        ctx_params.rope_freq_base = 10000.0f;
        ctx_params.rope_freq_scale = 1.0f;
        
        // Create context
        llama_context* ctx = llama_new_context_with_model(model, ctx_params);
        if (ctx == nullptr) {
            LOGE("Failed to create llama context");
            llama_free_model(model);
            llama_backend_free();
            return 0;
        }
        
        LOGI("Successfully created llama context");
        
        // Create wrapper structure
        LlamaContext* llama_ctx = new LlamaContext();
        llama_ctx->model = model;
        llama_ctx->ctx = ctx;
        
        LOGI("Successfully initialized llama context");
        return context_to_jlong(llama_ctx);
        
    } catch (const std::exception& e) {
        LOGE("Exception in initializeNative: %s", e.what());
        llama_backend_free();
        return 0;
    } catch (...) {
        LOGE("Unknown exception in initializeNative");
        llama_backend_free();
        return 0;
    }
}

/**
 * Run inference with the given prompt
 * 
 * JNI Name Mangling: Java_com_research_llmbattery_LLMService_inferNative
 * 
 * @param env JNI environment
 * @param obj Java object instance
 * @param contextPtr Pointer to llama context
 * @param prompt Input prompt for inference
 * @return Generated response as jstring, or empty string if failed
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_research_llmbattery_LLMService_inferNative(
    JNIEnv* env,
    jobject obj,
    jlong contextPtr,
    jstring prompt
) {
    LOGI("Running inference with prompt");
    
    try {
        // Validate context pointer
        if (contextPtr == 0) {
            LOGE("Invalid context pointer");
            return string_to_jstring(env, "");
        }
        
        // Get context from pointer
        LlamaContext* llama_ctx = jlong_to_context(contextPtr);
        if (llama_ctx == nullptr || !llama_ctx->isValid()) {
            LOGE("Invalid context pointer or context not initialized");
            return string_to_jstring(env, "");
        }
        
        // Convert Java string to C++ string
        std::string prompt_str = jstring_to_string(env, prompt);
        if (prompt_str.empty()) {
            LOGE("Prompt is empty");
            return string_to_jstring(env, "");
        }
        
        LOGD("Prompt: %s", prompt_str.c_str());
        
        // Tokenize input
        std::vector<llama_token> tokens_list;
        tokens_list.resize(prompt_str.length() + 1);
        
        int n_tokens = llama_tokenize(llama_ctx->ctx, prompt_str.c_str(), prompt_str.length(), 
                                     tokens_list.data(), tokens_list.size(), true);
        if (n_tokens < 0) {
            LOGE("Failed to tokenize input");
            return string_to_jstring(env, "");
        }
        tokens_list.resize(n_tokens);
        
        LOGD("Tokenized %d tokens", n_tokens);
        
        // Create batch for initial tokens
        llama_batch batch = llama_batch_init(512, 0);
        batch.n_tokens = n_tokens;
        
        for (int i = 0; i < n_tokens; i++) {
            batch.token[i] = tokens_list[i];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == n_tokens - 1);
        }
        
        // Decode initial batch
        if (llama_decode(llama_ctx->ctx, batch) != 0) {
            LOGE("Failed to decode initial batch");
            llama_batch_free(batch);
            return string_to_jstring(env, "");
        }
        
        // Generate response
        std::string response;
        int max_tokens = 256; // Maximum response length
        int n_ctx = llama_n_ctx(llama_ctx->ctx);
        
        for (int i = 0; i < max_tokens; i++) {
            // Get logits for the last token
            float* logits = llama_get_logits_ith(llama_ctx->ctx, batch.n_tokens - 1);
            int n_vocab = llama_n_vocab(llama_ctx->model);
            
            // Sample next token (using greedy sampling for simplicity)
            llama_token new_token_id = 0;
            float max_logit = logits[0];
            for (int j = 1; j < n_vocab; j++) {
                if (logits[j] > max_logit) {
                    max_logit = logits[j];
                    new_token_id = j;
                }
            }
            
            // Check for end of sequence
            if (new_token_id == llama_token_eos(llama_ctx->model)) {
                LOGD("End of sequence token generated");
                break;
            }
            
            // Convert token to string and append
            char token_str[256];
            int n_chars = llama_token_to_piece(llama_ctx->model, new_token_id, token_str, 
                                             sizeof(token_str), true);
            if (n_chars > 0) {
                response += std::string(token_str, n_chars);
            }
            
            // Check if we've reached context limit
            if (batch.n_tokens >= n_ctx - 1) {
                LOGD("Reached context limit");
                break;
            }
            
            // Prepare next batch with single token
            batch.n_tokens = 1;
            batch.token[0] = new_token_id;
            batch.pos[0] = batch.n_tokens - 1;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = true;
            
            // Decode next token
            if (llama_decode(llama_ctx->ctx, batch) != 0) {
                LOGE("Failed to decode next token");
                break;
            }
        }
        
        // Cleanup
        llama_batch_free(batch);
        
        LOGI("Generated response: %s", response.c_str());
        return string_to_jstring(env, response);
        
    } catch (const std::exception& e) {
        LOGE("Exception in inferNative: %s", e.what());
        return string_to_jstring(env, "");
    } catch (...) {
        LOGE("Unknown exception in inferNative");
        return string_to_jstring(env, "");
    }
}

/**
 * Free llama context and cleanup resources
 * 
 * JNI Name Mangling: Java_com_research_llmbattery_LLMService_freeNative
 * 
 * @param env JNI environment
 * @param obj Java object instance
 * @param contextPtr Pointer to llama context to free
 */
extern "C" JNIEXPORT void JNICALL
Java_com_research_llmbattery_LLMService_freeNative(
    JNIEnv* env,
    jobject obj,
    jlong contextPtr
) {
    LOGI("Freeing llama context");
    
    try {
        // Validate context pointer
        if (contextPtr == 0) {
            LOGE("Invalid context pointer for cleanup");
            return;
        }
        
        // Get context from pointer
        LlamaContext* llama_ctx = jlong_to_context(contextPtr);
        if (llama_ctx == nullptr) {
            LOGE("Invalid context pointer for cleanup");
            return;
        }
        
        // Free context and model using the destructor
        delete llama_ctx;
        
        LOGI("Successfully freed llama context and model");
        
    } catch (const std::exception& e) {
        LOGE("Exception in freeNative: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception in freeNative");
    }
}
