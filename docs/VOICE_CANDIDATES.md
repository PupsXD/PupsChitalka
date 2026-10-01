# Offline Russian narration: next experiments

The Galaxy S24 Ultra is the target device. A candidate must produce convincing Russian
long-form speech, finish cleanly, run entirely on-device after model download, and
generate audio quickly enough to keep reading without long pauses. These are separate
acceptance criteria: a pleasant eight-second sample alone does not qualify a model.

## Current choice (September 2026)

The measurements below rule out autoregressive LLM voices (Qwen3-TTS, CosyVoice, F5 with
many flow steps) for continuous reading on the phone's CPU. The reader therefore routes each
sentence to the best fast voice for its language and removes the pauses between sentences
with look-ahead synthesis instead of waiting for a faster big model.

| Language | Voice | Why | Status |
| --- | --- | --- | --- |
| Russian | Silero v5 (`v5_5_ru`) through the [RuVoice](https://github.com/kost-t-human/ruvoice-tts) system TTS engine | Non-autoregressive Russian model with neural stress and homograph resolution plus a large normalizer (numbers with cases, dates, abbreviations). Its author reports RTF 0.21 on a Galaxy A32 and about 0.05 on Snapdragon 4 Gen 2. | Integrated through Android `TextToSpeech.synthesizeToFile`; needs a listening comparison with the Qwen samples and an RTF check on the S24 Ultra |
| English | Kokoro v1.0 (sherpa-onnx, FP32 recommended) | The best-rated small English model; a third-party Android project reports RTF ≈ 0.67 with 4 threads on a Snapdragon 865/870 phone, so the much faster S24 Ultra should stay ahead of playback. The FP32 build is both cleaner and faster than INT8 (see below). | Integrated as an in-app download; RTF on the S24 Ultra to be measured |
| Russian, test | Vosk TTS 0.10 (ONNX Runtime, decoder cut to 5 steps on install) | 57 voices in one model, 33 male, so narrator and characters differ at no cost; the listener preferred its sound | Integrated as an in-app download; measured on the S24 Ultra, see below |
| Fallback / other | Supertonic 3 (INT8 or full) | Very fast, 31 languages, already installed by existing users | Integrated; now 4 threads and configurable flow steps |
| Any | Installed system engines (Google, Samsung) | No download, many voices | Integrated; network voices are marked |

What still has to be verified on the phone, since the cloud session that implemented this
could not run the APK:

1. Listen to RuVoice (`aidar`, `baya`, `kseniya`, `xenia`, `eugene` and the `cis` pack) on
   the passage used for Qwen and on a dialogue-heavy chapter.
2. Time to first sound and whether the look-ahead buffer stays full for each voice
   (the reader shows «Готовлю голос…» whenever playback waits for synthesis).
3. Kokoro FP32 at 1.5× speed: the container measurement below predicts a comfortable margin,
   but the phone's thermal behaviour over a long chapter is untested.

## October 2026: more Russian voices, and Vosk TTS as a test engine

RuVoice ships two male voices, and the listener liked one. One passage was rendered with 96 voices on
a PC (`tools/voices/render-ru-voices.py`) and each was measured (`tools/voices/analyze-ru-voices.py`):
speed, median pitch (YIN), what Whisper large-v3-turbo hears (CER) and UTMOS, a predicted naturalness
score trained on English listening tests, so a rough sort only.

| Source | Male voices | RTF on the PC, 4 threads | UTMOS, median (range) | Notes |
| --- | ---: | ---: | --- | --- |
| Silero `v5_5_ru` (RuVoice) | 2 | 0.06 | 2.92 (2.74–3.10) | The reference |
| Silero `v5_cis_base_nostress` (RuVoice pack `cis_ru`) | 10, and 2 that sound male | 0.06 | 2.54 (1.89–3.14) | No question intonation; RuVoice keeps one model in memory, so mixing it with a stock voice reloads the model at every switch |
| Vosk TTS `ru-0.10-multi` | 33 of 57 | 0.60 | 2.82 (2.25–3.52) | Five male voices score above eugene; the listener found it clearly better |
| Piper `ru_RU` | 3 | 0.07 | 2.56 (2.44–2.84) | dmitri misreads words (CER 7.7%) |
| MOSS-TTS-Nano 100M, cloned voice | any | 1.59 | 2.75 | Lost half of the passage (CER 44%) |

Vosk TTS therefore became a second Russian engine, marked as a test. As published it does not suit a
phone: on the Galaxy S24 Ultra the acoustic model loads for 45–55 s and synthesizes at RTF 0.78–0.82.
96.5% of the time goes to its Matcha-TTS decoder, which the ONNX file unrolls into 20 estimator runs:
10 Euler steps of dt = 0.1, each with a conditional and an unconditional pass mixed by classifier-free
guidance (`v = v_cond + 0.5 * (v_cond - v_uncond)`). Keeping every other step with dt = 0.2 halves the
work; dropping the guidance halves it again but costs quality:

| Decoder | Graph nodes | UTMOS, mean of 4 voices | Load on the S24 Ultra | RTF on the S24 Ultra, 4 threads |
| --- | ---: | ---: | ---: | ---: |
| 10 steps, guidance (as published) | 29,224 | 3.32 | 45–55 s | 0.78–0.82 |
| **5 steps, guidance (what the app installs)** | 15,789 | 3.29 | 14–18 s | 0.33–0.46 |
| 10 steps, no guidance | 14,514 | 3.14 | not measured | not measured |
| 5 steps, no guidance | 8,434 | 3.04 | 5.6 s | 0.24 |

The app rewrites the downloaded model on the phone (`VoskDecoderSteps.kt`, 8.6 s; its output is
bit-identical to `tools/voices/vosk-fewer-steps.py --steps 5`). Installing from the app on the S24
Ultra took about three and a half minutes over Wi-Fi: 834 MB in 170 s, then unpacking, the dictionary
index and the rewrite. The text frontend is a Kotlin port of
vosk-tts 0.3.61, checked sentence by sentence against the Python code (`VoskFrontendTest`), and the
whole engine against Python on the phone with the model's noise switched off: the same number of
samples, correlation 1.00000 (`VoskTtsDeviceTest`). Saving ONNX Runtime's optimized graph did not help:
it loaded slower (86 s on the PC) and ran slower. Six threads were slower than four (RTF 0.86 against
0.78). ruBERT, 654 MB of the download, costs 20–50 ms a sentence; the price of Vosk is memory, about
1.2 GB while reading.

Not measured: reading for an hour (heat, throttling), phones weaker than the S24 Ultra, and stress in
homographs, where Vosk takes the most frequent variant without looking at the context. The model's
speaker list carries the names of the people recorded; the app shows numbers only.

## Measured in the development container

The cloud session measured the app's exact sherpa-onnx 1.13.8 configurations (4 threads,
Supertonic 10 flow steps, Kokoro `en-us`) on an Intel Xeon 2.1 GHz with 4 vCPUs. These numbers
compare the variants with each other; the S24 Ultra's absolute speed will differ.

| Model | RTF (synthesis time / audio time) | Note |
| --- | --- | --- |
| Supertonic 3 INT8 | 0.33 (0.51 at 16 steps) | Russian and English alike |
| Supertonic 3 full precision | 0.29 | Slightly faster than INT8 |
| Kokoro v1.0 INT8 | 1.00 | Too slow to stay ahead of playback on this CPU |
| Kokoro v1.0 FP32 | 0.40 | 2.5× faster than INT8 |

The INT8 Kokoro export uses dynamic quantization: it replaces the 90 convolutions with
`ConvInteger` and adds 98 `DynamicQuantizeLinear` nodes that recompute activation scales on every
call. ONNX Runtime has no fast path for that pattern, so the smaller file is the slower one. The
app therefore offers the full Kokoro model first and keeps INT8 as the compact option.

Listening samples, rendered sentence by sentence with the reader's segmentation, normalization
and pauses: Supertonic 3 full, Russian [F1](samples/ru-supertonic-f1.mp3) and
[M1](samples/ru-supertonic-m1.mp3); Kokoro FP32, English [Heart](samples/en-kokoro-heart.mp3),
[Michael](samples/en-kokoro-michael.mp3) and [Emma](samples/en-kokoro-emma.mp3); Supertonic 3 full,
English [F1](samples/en-supertonic-f1.mp3) for comparison. Whisper small transcribes every sample
back to the source passage (numbers as digits); the only slips are unstressed endings such as
«ответила он» in Supertonic's Russian. RuVoice cannot run outside Android, so its voices are
compared in the app with the preview button.

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
