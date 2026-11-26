from pathlib import Path
import sys
root = Path(sys.argv[1]) / "src"
def edit(path, old, new):
    p = root / path
    s = p.read_text()
    assert s.count(old) == 1, (path, old, s.count(old))
    p.write_text(s.replace(old, new))
edit("qwen3_asr.h", "    int n_threads;", "    int n_threads;\n    int n_gpu_layers;")
edit("qwen3_asr.cpp", "    p.n_threads = 4;", "    p.n_threads = 4;\n    p.n_gpu_layers = -1;")
edit("qwen3_asr.cpp", "ggml_backend_t backend, ggml_backend_t backend_cpu) {", "ggml_backend_t backend, ggml_backend_t backend_cpu, int layer_limit) {")
edit("qwen3_asr.cpp", "int n_gpu_layers_env = -1;", "int n_gpu_layers_env = layer_limit;")
edit("qwen3_asr.cpp", 'if (const char* s = std::getenv("CRISPASR_N_GPU_LAYERS")) {', 'if (const char* s = std::getenv("CRISPASR_N_GPU_LAYERS"); layer_limit < 0 && s) {')
edit("qwen3_asr.cpp", "path, ctx->backend, ctx->backend_cpu))", "path, ctx->backend, ctx->backend_cpu, params.n_gpu_layers))")
edit("qwen3_asr.cpp", "core_gguf::is_gpu_tensor_blk, &threshold,", """[](const char* name, void* data) {
            const int n = *static_cast<int*>(data);
            const int il = core_gguf::blk_layer_of(name);
            // Keep encoder on Vulkan, but large token/output tables on CPU.
            return il >= 0 ? il < n : std::strncmp(name, "audio.", 6) == 0;
        }, &threshold,""")
edit("qwen3_asr.cpp", "fuse_enabled && !blocks.empty()", "fuse_enabled && !(params.use_gpu && params.n_gpu_layers >= 0) && !blocks.empty()")
edit("crispasr_c_api.cpp", "        s->qwen3_ctx = qwen3_asr_init_from_file(model_path, p);", "        p.n_gpu_layers = g_open_n_gpu_layers_tls;\n        s->qwen3_ctx = qwen3_asr_init_from_file(model_path, p);")
edit("crispasr_c_api.cpp", "std::max(4096, (int)ids.size() + q3_max_new + 16)", "std::max(128, (int)ids.size() + q3_max_new + 16)")
print("Applied Qwen3 layer-limit, mixed placement, fusion and cache sizing fixes")
