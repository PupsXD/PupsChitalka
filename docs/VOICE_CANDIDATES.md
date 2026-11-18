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
guidance (`v = v_cond + 0.5 * (v_cond - v_uncond)`).

Since PUP-003 the start is short. The 45–55 s was the graph's dynamic shapes, which ONNX Runtime
resolved again on every load — and a cached `.ort` copy of the published graph did not help, as this
project measured before (86 s). `quant_pre_process` from `onnxruntime.quantization` resolves them once,
and ONNX Runtime's own optimized copy of *that* graph loads in about a second:

| Graph | Load on the PC, 4 threads | Note |
| --- | ---: | --- |
| `model.onnx` as published | 57.0 s | 29,224 nodes, 235 MB |
| after `quant_pre_process` | 2.6 s | 14,864 nodes, 226 MB |
| … with the decoder cut to 5 steps (`model-fast.onnx`) | 1.7 s | 8,170 nodes, 212 MB |
| … and ONNX Runtime's `.ort` copy next to it | **1.0–1.2 s** | +211 MB on disk, written on first start |

The weights are untouched: at noise 0 the prepared graph's audio correlates 0.99999989 with the
published one (max difference 8.8·10⁻⁶). `tools/voices/vosk-prepare.py` does all of this to a model by
hand and prints the timings and that correlation. The app downloads the prepared graph from
[NGC404/sgspeech-vosk-tts-ru-0.10-prepared](https://huggingface.co/NGC404/sgspeech-vosk-tts-ru-0.10-prepared)
(Apache 2.0) during install, checks its SHA-256, deletes the graph from the Alpha Cephei archive,
rewrites the decoder itself (`VoskDecoderSteps.kt`; `max difference 0.0` against
`vosk-fewer-steps.py` on the same graph) and lets ONNX Runtime write the `.ort` copy on the first start.
A failed fetch or rewrite leaves `model.onnx` in place, so an install from before this change keeps
working — it just starts slowly.

The decoder was re-measured on ten voices (28/47/49/2 plus 0, 5, 10, 20, 35, 56) with
`tools/voices/vosk-compare-variants.py`, and again at noise 0, where the output is deterministic:

| Decoder | Estimator runs | RTF, 10 voices | UTMOS, voices 28/47/49/2 | UTMOS, 10 voices | UTMOS at noise 0 | log-mel to the original |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 steps, guidance (as published) | 20 | 0.313 | 3.18 | 2.89 | 2.635 | — |
| **5 steps, guidance (what the app installs)** | 10 | **0.171** | **3.23** | **2.88** | **2.655** | 0.56 dB |
| 8 steps, guidance on the first 3 | 11 | 0.176 | 3.13 | 2.87 | 2.545 | 0.44 dB |
| 8 steps, no guidance | 8 | 0.141 | 2.96 | 2.71 | 2.538 | 1.01 dB |

Whisper large-v3-turbo hears the whole passage correctly in every variant (CER 0.0%), so intelligibility
does not separate them; UTMOS and the distance to the original do. Eight estimator runs without guidance
are 17% faster than the ten guided ones, as counted, but cost 0.1–0.2 UTMOS — the guidance, not the step
size, is what keeps the voice close to the target. Eight steps guided on the first three are 21% closer
to the original by log-mel, yet 0.05–0.09 lower by UTMOS and slightly slower than today's ten passes.
Both 8-step variants therefore exist in `vosk-fewer-steps.py --steps 8 [--no-cfg | --cfg-steps 3]` and in
`VoskDecoderSteps.rewrite(steps, cfgSteps)`, and neither is what the app installs: it keeps 5 guided
steps. UTMOS of one and the same variant moves by ±0.1 between runs at the model's own noise (3.33 and
3.45 for one voice), which is why the noise-0 column exists; those values repeat exactly.

INT8 ruBERT (dynamic quantization, `vosk-prepare.py --int8`): 624 MB → 156 MB, load 0.85 → 0.47 s and
39 → 10 ms a sentence on the PC. On the stand it scores UTMOS 2.74 against 2.66 and CER 0.0%, and its
audio sits 2.5 dB away from the FP32 run — the phoneme durations move, so it is another take rather than
a degraded one. The app uses `bert-int8.onnx` when that file lies next to `bert.onnx`; it does not
download it, because nothing hosts it yet, so the ~480 MB of memory it would save stay on the table.

The text frontend is a Kotlin port of vosk-tts 0.3.61, checked sentence by sentence against the Python
code (`VoskFrontendTest`), and the whole engine against Python on the phone with the model's noise
switched off: the same number of samples, correlation 1.00000 (`VoskTtsDeviceTest`). Six threads were
slower than four (RTF 0.86 against 0.78). ruBERT, 654 MB of the download, costs 20–50 ms a sentence; the
price of Vosk is memory, about 1.2 GB while reading. Installing on the S24 Ultra took about three and a
half minutes over Wi-Fi for the older 834 MB download; the prepared graph adds 226 MB to it, and the
`.ort` copy another 211 MB on disk.

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

## November 2026: Russian Kokoro (zaakirio/kokoro-ru) as a downloadable engine

A Russian fine-tune of Kokoro-82M, 24 kHz, three voices: sveta and masha share one checkpoint,
dima has his own. It is the first Russian model here that sounds like the English Kokoro rather
than like a formant synthesizer, and it is wired as a download in «Голоса и озвучка».

**One checkpoint in memory.** The two fp32 checkpoints are 326 MB each, 652 MB together — more than
the app may spend on speech. A narration therefore loads only the narrator's file
(`KokoroRu.checkpointFor`), reads characters' lines with it (`speakerIn` returns null for a voice of
the other file) and lets another engine take a role voice from the second download. The character
picker never offers the other file's voice and the synthesis path refuses it, so nothing alternates
the two checkpoints — that would re-read 326 MB per line. Dima is offered as a narrator, not as a
role under Sveta or Masha.

**What the repository contains** (`https://huggingface.co/zaakirio/kokoro-ru`): `onnx/model.onnx`
(fp32, 326 MB) plus `model_quantized.onnx` (138 MB), `model_fp16.onnx`, and the same three for dima;
`voices/{sveta,masha,dima}.bin` — raw float32 style packs of 510 rows × 256; `config.json` with
Kokoro's 114-symbol `vocab`; an `espeak-data` tree, including a `ru_dict` recompiled from
espeak-ng master so that it honours the combining acute U+0301; and `ru_g2p.py`, the reference
frontend.

**Graph compatibility.** The ONNX export has the same layout as
`onnx-community/Kokoro-82M-v1.0-ONNX` that sherpa-onnx already loads: inputs `input_ids` (int64),
`style` (float32, 256), `speed` (float32), output `waveform`. Two things are missing from the file,
and the app adds both during install:

- the metadata sherpa-onnx reads — `sample_rate`, `version` (must be ≥ 2, otherwise the English
  `PiperPhonemizeLexicon` is used and `lang` is ignored), `n_speakers`, `has_espeak`, `voice`,
  `style_dim=510,1,256`. ONNX is protobuf, so the app appends field 14 (`metadata_props`) to the end
  of the downloaded file instead of rewriting 326 MB (`KokoroOnnxMetadata`);
- `tokens.txt`, built from `config.json` plus four aliases (`ɭ→l`, `ɵ→o`, `ʐ→ʒ`, `ʑ→ʒ`) for the
  symbols espeak-ng emits for Russian but Kokoro never trained on — the same folding `ru_g2p.py`
  does in `NORMALIZE`. Without them sherpa-onnx skips those phonemes ("Skip unknown phonemes").

**Russian G2P.** `ru_g2p.py` runs RUAccent, marks stress as «+» before the vowel, converts it to
U+0301, respells a few orthoepic exceptions and applies espeak-ng with the acute-aware `ru_dict`,
then applies positional vowel reduction to the IPA. The app cannot run RUAccent or misaki on the
phone, so it does the part that matters most and is already present: `ru_stress.bin` — the RUAccent
dictionary of 949 837 unambiguous word forms — marks the words it knows with U+0301
(`StressStyle.DICTIONARY_ACUTE`), and espeak-ng inside sherpa-onnx reads those marks through the
model's own dictionary. Homographs the dictionary deliberately omits (за́мок/замо́к) keep espeak's
rules. **Vowel reduction (akanye) is not applied:** espeak emits a and o apart where standard
Russian merges them. That is the known quality gap of this integration.

**Measured on the development PC** (Intel Xeon 2.1 GHz, 4 vCPUs, sherpa-onnx 1.13.8 wheel — exactly
the AAR version the app ships — 4 threads):

| Checkpoint | RTF | Notes |
| --- | ---: | --- |
| `onnx/model.onnx` fp32, sveta | 0.29–0.35 | the one the app downloads |
| `onnx/model.onnx` fp32, masha | 0.35 | same file, second style pack |
| `onnx/model_dima.onnx` fp32 | 0.31 | the male voice |
| `onnx/model_quantized.onnx` q8 | 0.81–0.88 | 2.8× slower than fp32, as with English Kokoro INT8 |

Intelligibility: faster-whisper `small` on the 51-word passage returns 11.8% WER, the errors being
unstressed vowels («Осень» heard as «Осин») — the reduction gap above. Samples:
`docs/samples/ru-kokoro-sveta.mp3`, `ru-kokoro-dima.mp3`.

Not measured: the phone. Load time, memory (upstream reports 2.39 GB peak in Python; the ONNX
session itself is smaller), long-chapter thermals and the FP32 download over mobile data are all
open. The app routes Russian narrator lines to this engine and leaves dialogues to whatever the cast
settings say; English Kokoro and this model never read each other's language.

