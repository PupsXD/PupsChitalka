# Offline Russian narration: next experiments

The Galaxy S24 Ultra is the target device. A candidate must produce convincing Russian
long-form speech, finish cleanly, run entirely on-device after model download, and
generate audio quickly enough to keep reading without long pauses. These are separate
acceptance criteria: a pleasant eight-second sample alone does not qualify a model.

## Measured baseline on this phone

| Engine | Observation | Status |
| --- | --- | --- |
| Supertonic 3, INT8 and full | Works in the main reader; user finds the delivery mechanical. | Usable fallback |
| Kokoro-ru Q8, Sveta and Masha | Both samples judged worse than Qwen by the user. | Do not integrate |
| Qwen3-TTS 0.6B Q4_K_M, CPU | 8.64 s of Russian audio took about eight minutes; user preferred the voice to Supertonic. | Voice promising, speed unacceptable |
| Qwen3-TTS 0.6B Q8_0, CPU | On the same 17-character phrase, 1.28 s of audio took 124.254 s (RTF 97.07). | Valid WAV, speed unacceptable; quality awaits listening |
| Qwen3-TTS 0.6B Q4_K_M, CPU, controlled phrase | 1.60 s of audio took 97.879 s (RTF 61.17). | Valid WAV, speed unacceptable |
| Qwen3-TTS 0.6B Q4_K_M, Adreno OpenCL | Backend activated, but the first run generated too long, then crashed in decoder allocation before saving WAV. | No valid speed or quality result |
| Qwen3-TTS 0.6B Q8_0, Adreno OpenCL | A confirmed 128-token short-phrase run aborted in `ggml-opencl` generator compute after about 10 s; no WAV. | Blocked by native crash |

The CPU comparison used `Привет, как дела?`, Russian language ID 2069, six threads,
and a 128 audio-token cap. The samples are [Q4_K_M](samples/qwen-q4-short.wav)
and [Q8_0](samples/qwen-q8-short.wav). The Q8 file contains fewer audio samples
despite the identical input; judge pronunciation and prosody by listening to both.
This one utterance cannot rank quality or typical throughput of long-form reading.

The Qwen GPU run's interim token rate is not an end-to-end benchmark. See
[QWEN_GPU_POC.md](QWEN_GPU_POC.md) for the observed timings and crash.

## Order of experiments

1. **Listen to the two CPU samples.** Q8_0 was installed and verified on the phone
   (1,283,766,112 bytes for talker plus codec) and both variants produced WAVs from
   the same input. The user should compare the files for pronunciation, pacing,
   and artifacts before choosing a quality target. The published files are
   [here](https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/tree/main).
2. **Qwen OpenCL debugging.** The bounded Q8_0 run confirmed a native abort in
   generator compute, while the earlier Q4_K_M run reached the decoder and crashed
   there. Isolate the transformer and vocoder backends, then require a valid WAV
   with the correct spoken text before comparing real-time factors.
3. **Russian F5-TTS v2.** The [model card](https://huggingface.co/Misha24-10/F5-TTS_RUSSIAN)
   describes Russian fine-tuning and stress marks. An [ONNX export path](https://github.com/DakeQQ/F5-TTS-ONNX)
   exists, but Android compatibility, memory use, latency, and voice quality remain
   unmeasured. Its weights are CC-BY-NC-4.0, so assess distribution constraints before
   offering them in a general-purpose app.
4. **CosyVoice 3.** The [official repository](https://github.com/FunAudioLLM/CosyVoice)
   reports Russian support and natural prosody. Its published performance numbers are
   for other hardware; there is no demonstrated S24 Ultra deployment in this project.
   Pursue only if Qwen or F5 cannot meet both quality and latency targets.

The Russian-stress-tuned Qwen 1.7B checkpoint is not the immediate next test: its
larger talker is unlikely to solve the measured latency bottleneck without a working
accelerated runtime. Its possible pronunciation benefit is still worth revisiting
after mobile generation speed is established.
