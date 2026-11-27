#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <string>
#include <memory>
#include <stdexcept>
#include <algorithm>
#include <chrono>
#include <cstdint>
struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    mtmd_context *audio = nullptr;
    std::string text;
    double encoder = 0, decoder = 0;
    ~Engine() { if(audio) mtmd_free(audio); if(ctx) llama_free(ctx); if(model) llama_model_free(model); }
};
static thread_local std::string error;
#define EXPORT extern "C" __attribute__((visibility("default")))
EXPORT const char *lc_error() { return error.c_str(); }
EXPORT void *lc_open(const char *model, const char *projector, int threads) {
    try {
        auto e = std::make_unique<Engine>();
        llama_backend_init();
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
        e->model = llama_model_load_from_file(model, mp);
        if(!e->model) throw std::runtime_error("decoder load failed");
        auto cp = llama_context_default_params();
        cp.n_ctx = 512; cp.n_batch = 256; cp.n_ubatch = 128;
        cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED; cp.offload_kqv = false;
        e->ctx = llama_init_from_model(e->model, cp);
        if(!e->ctx) throw std::runtime_error("decoder context failed");
        auto ap = mtmd_context_params_default(); ap.use_gpu = false; ap.n_threads = threads; ap.warmup = false;
        e->audio = mtmd_init_from_file(projector, e->model, ap);
        if(!e->audio || !mtmd_support_audio(e->audio)) throw std::runtime_error("audio projector load failed");
        return e.release();
    } catch(const std::exception &ex) { error=ex.what(); return nullptr; } catch(...) { error="native load failed"; return nullptr; }
}
EXPORT void lc_close(void *p) { delete static_cast<Engine*>(p); }
EXPORT const char *lc_transcribe(void *p, const float *pcm, int n) {
    try {
        auto &e = *static_cast<Engine*>(p);
        using clock = std::chrono::steady_clock;
        auto start = clock::now(); e.encoder = 0; e.decoder = 0;
        llama_memory_clear(llama_get_memory(e.ctx), true);
        std::string prompt = "<|im_start|>system\n<|im_end|>\n<|im_start|>user\n";
        prompt += mtmd_default_marker();
        prompt += "<|im_end|>\n<|im_start|>assistant\nlanguage Chinese<asr_text>";
        std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmap(mtmd_bitmap_init_from_audio(n, pcm), mtmd_bitmap_free);
        std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
        if(!bitmap || !chunks) throw std::runtime_error("audio allocation failed");
        mtmd_input_text input{prompt.data(), prompt.size(), true, true};
        const mtmd_bitmap *bitmaps[] = {bitmap.get()};
        if(mtmd_tokenize(e.audio, chunks.get(), &input, bitmaps, 1)) throw std::runtime_error("audio tokenize failed");
        llama_pos pos = 0;
        const size_t count = mtmd_input_chunks_size(chunks.get());
        for(size_t i=0;i<count;++i) {
            const auto *chunk=mtmd_input_chunks_get(chunks.get(), i);
            llama_pos next=pos;
            auto mark=clock::now();
            int rc=mtmd_helper_eval_chunk_single(e.audio,e.ctx,chunk,pos,0,256,i+1==count,&next);
            double ms=std::chrono::duration<double,std::milli>(clock::now()-mark).count();
            if(mtmd_input_chunk_get_type(chunk)==MTMD_INPUT_CHUNK_TYPE_TEXT) e.decoder+=ms; else e.encoder+=ms;
            if(rc) throw std::runtime_error("audio prompt evaluation failed");
            pos=next;
        }
        auto mark=clock::now();
        std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(),llama_sampler_free);
        if(!sampler) throw std::runtime_error("sampler failed");
        const auto *vocab=llama_model_get_vocab(e.model);
        e.text.clear();
        for(int i=0;i<32;++i) {
            auto tok=llama_sampler_sample(sampler.get(),e.ctx,-1);
            if(llama_vocab_is_eog(vocab,tok)) break;
            char buf[512]; int len=llama_token_to_piece(vocab,tok,buf,sizeof(buf),0,false);
            if(len>0) e.text.append(buf,len);
            if(e.text.find('|')!=std::string::npos || e.text.find('#')!=std::string::npos) break;
            llama_sampler_accept(sampler.get(),tok);
            auto batch=llama_batch_get_one(&tok,1);
            if(llama_decode(e.ctx,batch)) throw std::runtime_error("token decoding failed");
        }
        auto stop=e.text.find_first_of("|#"); if(stop!=std::string::npos) e.text.resize(stop);
        e.decoder+=std::chrono::duration<double,std::milli>(clock::now()-mark).count();
        return e.text.c_str();
    } catch(const std::exception &ex) { error=ex.what(); return nullptr; } catch(...) { error="native decode failed"; return nullptr; }
}
EXPORT double lc_timing(void *p,int i) { auto &e=*static_cast<Engine*>(p); return i==0?e.encoder:e.decoder; }
