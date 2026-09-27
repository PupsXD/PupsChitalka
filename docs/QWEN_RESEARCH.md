# Reproducing the Qwen3-TTS Android experiment

The main reader does not bundle Qwen or its model weights. The experimental app is
based on [`Danmoreng/qwen3-tts-android`](https://github.com/Danmoreng/qwen3-tts-android)
at commit `35738304b31c425cc08fb5405372fedfd6531b3b`. The tracked
[patch](qwen3-tts-android.patch) adds Q8_0 selection, separate model directories,
an experimental backend selector, a 128-token generation cap, and timing logs.
Its upstream native submodule is required; the patch alone is not an APK.

From the root of this repository:

```powershell
git clone --recursive https://github.com/Danmoreng/qwen3-tts-android.git .research/qwen3-tts-android
git -C .research/qwen3-tts-android checkout 35738304b31c425cc08fb5405372fedfd6531b3b
git -C .research/qwen3-tts-android submodule update --init --recursive
git -C .research/qwen3-tts-android apply ../../docs/qwen3-tts-android.patch
```

The default build uses CPU. For the experimental Adreno path, the upstream Gradle
build expects Khronos OpenCL headers under
`.research/qwen3-tts-android/build/opencl-sdk/OpenCL-Headers` and an arm64 linker
library at `build/opencl-sdk/lib/arm64-v8a/libOpenCL.so`. The library used in the
device test was copied from the connected Galaxy S24 Ultra's
`/vendor/lib64/libOpenCL.so` (63,632 bytes). Compile with
`gradlew.bat :app:assembleDebug -Pqwen.opencl=true`. These local build inputs,
model weights, generated files, and APKs are intentionally excluded from Git.

The [Q8_0 downloader](../tools/download_qwen_q8.ps1) verifies the published file
sizes and SHA-256 hashes. See [device results](QWEN_GPU_POC.md) for both CPU
samples and the current OpenCL crash. The sample app's unit-test task currently
has no test sources; its `:app:assembleDebug` succeeds, while actual synthesis
behavior was checked on the phone.
