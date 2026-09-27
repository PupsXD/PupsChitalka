# Qwen3-TTS OpenCL on Galaxy S24 Ultra

This is a research build of a separate Android sample. The main Озвучка reader still uses Supertonic for live narration.

## Device and setup

- Galaxy S24 Ultra (SM-S928B), Snapdragon 8 Gen 3, Adreno 750, Android 16.
- Qwen3-TTS 0.6B Q4_K_M sample package: about 843 MB, installed in the sample app's private storage.
- CPU reference from the same phone and Russian passage: 8.64 seconds of audio after approximately eight minutes of generation. The user preferred its voice to Supertonic.
- GGML OpenCL was compiled with the Adreno kernels. The phone's public `libOpenCL.so` was used for linking; the app loaded the model and reported `Active: GPUOpenCL`.

## OpenCL observation

Passage: `Он остановился у окна. За стеклом медленно падал снег. «Ты вернёшься?» — спросила она почти шёпотом.`

During the first OpenCL run, the UI showed approximately 4.1 seconds of audio generated after 33 seconds, 15.7 seconds after 1 minute 48 seconds, and 36.1 seconds after 3 minutes 56 seconds. These are interim estimates, **not** an end-to-end benchmark or proof of usable audio. The model did not stop near the CPU sample's 8.64-second length. At 12:58:03 local device time, the process crashed during `AudioTokenizerDecoder::decode`, with a null pointer in `ggml_gallocr_alloc_graph` / `ggml_backend_sched_alloc_graph`. No WAV was saved for this OpenCL run.

Two later taps intended to run with a 128-token cap did not create a generation record or diagnostic log. Inspection showed that navigation to Studio and tapping Generate were issued without waiting for the navigation animation; these attempts cannot be interpreted as model failures.

On 2026-09-27, a **confirmed bounded Q8_0 OpenCL run** started for `Привет, как дела?` with Russian language ID 2069, six CPU threads, and a 128 audio-token cap. `Active: GPUOpenCL` was visible before generation, and `QwenGpuProbe` logged the backend at 22:33:37.921. At 22:33:48, the process aborted with `SIGABRT` in `ggml-opencl` (`ggml_cl_compute_forward`), called from the text generator. No WAV was saved. This is a separate, earlier failure than the first Q4_K_M decoder-allocation crash. OpenCL cannot yet be used for narration in the main reader.

## Build and next checks

The sample source has an experimental OpenCL selection in Settings. Build it with `-Pqwen.opencl=true` using its Gradle wrapper. `build/opencl-sdk/OpenCL-Headers` contains the official Khronos headers; `build/opencl-sdk/lib/arm64-v8a/libOpenCL.so` is a local linker copy from the connected phone. These are research build inputs, not files in the main reader APK.

Next, diagnose the `ggml-opencl` abort in the generator and the earlier decoder allocation crash. Only after both stages exit cleanly should a valid WAV be compared with CPU. The selected backend name alone does not establish acceleration of both generator and vocoder.

## Quality comparison prepared

Settings offers Q8_0 alongside Q4_K_M. Both packages were installed in separate app-private directories to prevent the native tokenizer auto-selection from mixing quantizations. The Q8_0 download is 1,283,766,112 bytes. The model sizes and SHA-256 identifiers come from the published [talker](https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/raw/main/qwen-talker-0.6b-base-Q8_0.gguf) and [tokenizer](https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/raw/main/qwen-tokenizer-12hz-Q8_0.gguf) pointers; the files were hash-verified both on PC and phone. The reproducible download script is [here](../tools/download_qwen_q8.ps1).

CPU comparison, Galaxy S24 Ultra, `Привет, как дела?`, Russian language ID 2069, six threads, 128 audio-token cap:

| Variant | Generation time | WAV duration | Real-time factor | Sample |
| --- | ---: | ---: | ---: | --- |
| Q4_K_M | 97.879 s | 1.60 s | 61.17 | [WAV](samples/qwen-q4-short.wav) |
| Q8_0 | 124.254 s | 1.28 s | 97.07 | [WAV](samples/qwen-q8-short.wav) |

Both runs completed without an engine error and produced valid WAV files. Audio quality and exact spoken content still require listening. These durations differ, so the time difference cannot be read as a clean model-speed ratio. A separate [GGUF implementation's model card](https://huggingface.co/cstr/qwen3-tts-0.6b-base-GGUF) reports markedly worse content fidelity with its Q4_K talker than Q8_0, which motivates listening but does not establish the result for this runtime or conversion.
