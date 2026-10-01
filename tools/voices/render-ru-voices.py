#!/usr/bin/env python3
"""Renders one Russian passage with every candidate voice, to compare them by ear and by numbers.

Engines (models are not in the repo; download them into --models first, see `--help`):
  silero   Silero v5_5_ru: aidar, eugene (the voices RuVoice ships)
  pitch    the same two voices lower and higher with Silero's own pitch control
  cis      Silero v5_cis_base_nostress: the 29 ru_* voices of RuVoice's «cis_ru» pack
  vosk     Vosk TTS vosk-model-tts-ru-0.10-multi: 57 voices
  piper    Piper ru_RU denis, dmitri, ruslan (medium) through sherpa-onnx

Every engine gets the passage cut into the same sentences, with the same pause between them, and
runs on the same number of CPU threads. Silero's two models get stress from Silero Stress (what
RuVoice uses); Vosk and Piper put stress themselves. For each voice the script writes an mp3 and a
row in voices.json: seconds of audio and real-time factor on this computer;
analyze-ru-voices.py adds pitch and intelligibility.

  python tools/voices/render-ru-voices.py --models <dir> --out build/voices/candidates
  python tools/voices/render-ru-voices.py --models <dir> --out <dir> --engines cis,vosk

Needs: torch, silero-stress, vosk-tts, sherpa-onnx, numpy, soundfile and ffmpeg on PATH.
Model files expected in --models:
  v5_5_ru.pt, v5_cis_base_nostress.pt      https://models.silero.ai/models/tts/ru/<name>
  vosk-model-tts-ru-0.10-multi/            https://alphacephei.com/vosk/models/vosk-model-tts-ru-0.10-multi.zip
  vits-piper-ru_RU-<voice>-medium/         https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

import numpy as np
import soundfile

THREADS = 4
PAUSE = 0.30  # seconds of silence between sentences

PASSAGE = (
    "Дункан долго смотрел на туман за кормой. "
    "— Ты слышишь? — спросил он вполголоса. "
    "— Ничего там нет, капитан! — проворчал боцман. — Третий день один и тот же туман. "
    "Дункан усмехнулся, но рука его всё ещё лежала на рукояти. "
    "— Тогда почему все молчат? Проверь замок на нижней палубе и доложи мне к восьми часам."
)


def sentences(text):
    """The passage as the reader would send it: sentence by sentence, dialogue dashes as commas."""
    parts = re.split(r"(?<=[.?!])\s+", text)
    result = []
    for part in parts:
        part = re.sub(r"^—\s*", "", part.strip())
        part = re.sub(r"\s+—\s+", ", ", part)
        if part:
            result.append(part)
    return result


def join(chunks, rate):
    silence = np.zeros(int(PAUSE * rate), dtype=np.float32)
    out = []
    for chunk in chunks:
        out += [np.asarray(chunk, dtype=np.float32).reshape(-1), silence]
    return np.concatenate(out[:-1])


def silero_engine(models, model_name, speakers):
    """[speakers] None means every ru_* voice of the model."""
    import torch
    torch.set_num_threads(THREADS)
    from silero_stress import load_accentor
    torch.set_num_threads(THREADS)  # silero_stress sets one thread on import
    accentor = load_accentor()
    model = torch.package.PackageImporter(str(models / f"{model_name}.pt")).load_pickle("tts_models", "model")
    speakers = speakers or [s for s in model.speakers if s.startswith("ru_")]
    stressed = [accentor(s) for s in sentences(PASSAGE)]

    def render(speaker):
        # «eugene@low»: the same voice with Silero's own pitch control, as RuVoice applies setPitch
        name, _, pitch = speaker.partition("@")
        flags = dict(speaker=name, sample_rate=48000, put_accent=False, put_yo=False, put_stress_homo=False, put_yo_homo=False)
        chunks = [
            (model.apply_tts(ssml_text=f'<speak><prosody pitch="{pitch}">{s}</prosody></speak>', **flags) if pitch
             else model.apply_tts(text=s, **flags)).numpy()
            for s in stressed
        ]
        return join(chunks, 48000), 48000

    render(speakers[0])  # warm-up, not timed
    return {"text": " ".join(stressed), "speakers": speakers, "render": render}


def vosk_engine(models):
    import onnxruntime
    original = onnxruntime.SessionOptions

    def options():
        o = original()
        o.intra_op_num_threads = THREADS
        return o

    onnxruntime.SessionOptions = options
    from vosk_tts import Model, Synth
    model = Model(model_path=str(models / "vosk-model-tts-ru-0.10-multi"))
    synth = Synth(model)
    rate = model.config["audio"]["sample_rate"]
    ids = model.config["speaker_id_map"]

    def render(speaker):
        chunks = [synth.synth_audio(s, speaker_id=ids[speaker]).astype(np.float32) / 32768.0 for s in sentences(PASSAGE)]
        return join(chunks, rate), rate

    render(next(iter(ids)))
    return {"text": " ".join(sentences(PASSAGE)), "speakers": list(ids), "render": render}


def piper_engine(models, voices=("denis", "dmitri", "ruslan")):
    import sherpa_onnx
    engines = {}
    for voice in voices:
        d = models / f"vits-piper-ru_RU-{voice}-medium"
        config = sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                    model=str(d / f"ru_RU-{voice}-medium.onnx"),
                    tokens=str(d / "tokens.txt"),
                    data_dir=str(d / "espeak-ng-data"),
                ),
                num_threads=THREADS,
            ),
        )
        engines[voice] = sherpa_onnx.OfflineTts(config)

    def render(voice):
        tts = engines[voice]
        chunks, rate = [], tts.sample_rate
        for s in sentences(PASSAGE):
            audio = tts.generate(s, sid=0, speed=1.0)
            chunks.append(audio.samples)
        return join(chunks, rate), rate

    for voice in voices:
        render(voice)
    return {"text": " ".join(sentences(PASSAGE)), "speakers": list(voices), "render": render}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--models", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--engines", default="silero,pitch,cis,vosk,piper")
    parser.add_argument("--speakers", default="", help="only these voices, comma-separated (to re-time a few)")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    report_path = args.out / "voices.json"
    report = json.loads(report_path.read_text(encoding="utf-8")) if report_path.exists() else {"voices": {}}
    report["passage"] = PASSAGE
    report["threads"] = THREADS

    for name in args.engines.split(","):
        if name == "silero":
            engine = silero_engine(args.models, "v5_5_ru", ["aidar", "eugene"])
        elif name == "pitch":
            engine = silero_engine(args.models, "v5_5_ru", ["aidar@low", "aidar@high", "eugene@low", "eugene@high"])
        elif name == "cis":
            engine = silero_engine(args.models, "v5_cis_base_nostress", None)
        elif name == "vosk":
            engine = vosk_engine(args.models)
        elif name == "piper":
            engine = piper_engine(args.models)
        else:
            raise SystemExit(f"unknown engine {name}")
        report.setdefault("texts", {})[name] = engine["text"]
        only = set(filter(None, args.speakers.split(",")))
        for speaker in [s for s in engine["speakers"] if not only or s in only]:
            started = time.perf_counter()
            audio, rate = engine["render"](speaker)
            spent = time.perf_counter() - started
            seconds = len(audio) / rate
            key = f"{name}-{speaker}".replace("@", "-")
            wav = args.out / f"{key}.wav"
            soundfile.write(wav, audio, rate)
            subprocess.run(
                ["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-ac", "1", "-ar", "24000",
                 "-b:a", "48k", str(args.out / f"{key}.mp3")],
                check=True,
            )
            wav.unlink()
            report["voices"][key] = {
                "engine": name, "speaker": speaker, "seconds": round(seconds, 2),
                "rtf": round(spent / seconds, 3)
            }
            print(f"{key:40s} {seconds:5.1f} s  RTF {spent / seconds:.3f}", flush=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
