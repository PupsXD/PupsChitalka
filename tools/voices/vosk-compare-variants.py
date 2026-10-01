#!/usr/bin/env python3
"""Renders the test passage with variants of Vosk's acoustic model (see vosk-fewer-steps.py) and
measures each: load time, RTF on this PC, UTMOS and Whisper CER; writes mp3s and variants.json.

  python tools/voices/vosk-compare-variants.py <vosk model dir> <variants dir> <out dir> [speaker ids]

Speaker ids default to 28 and 47 (men) and 49 and 2 (women), the best by UTMOS in the 0.10 model.
"""
import importlib.util
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime
import soundfile
import torch

HERE = Path(__file__).parent


def load_module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main(model_dir, variants_dir, out_dir, speakers="28,47,49,2"):
    render = load_module("render-ru-voices")
    analyze = load_module("analyze-ru-voices")
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    options = onnxruntime.SessionOptions()
    options.intra_op_num_threads = render.THREADS
    from vosk_tts import Model, Synth
    model = Model(model_path=model_dir)
    synth = Synth(model)
    from faster_whisper import WhisperModel
    asr = WhisperModel("large-v3-turbo", device="cpu", compute_type="int8", cpu_threads=8)
    mos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True)
    report = {}
    for variant in sorted(Path(variants_dir).glob("*.onnx")):
        started = time.perf_counter()
        model.onnx = onnxruntime.InferenceSession(str(variant), options, providers=["CPUExecutionProvider"])
        load = time.perf_counter() - started
        synth.synth_audio("Прогрев.", speaker_id=0)
        for sid in map(int, speakers.split(",")):
            started = time.perf_counter()
            chunks = [synth.synth_audio(s, speaker_id=sid).astype(np.float32) / 32768.0 for s in render.sentences(render.PASSAGE)]
            spent = time.perf_counter() - started
            audio = render.join(chunks, 22050)
            key = f"{variant.stem}-{sid:02d}"
            soundfile.write(out / f"{key}.wav", audio, 22050)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(out / f"{key}.wav"), "-ac", "1", "-ar", "24000",
                            "-b:a", "48k", str(out / f"{key}.mp3")], check=True)
            (out / f"{key}.wav").unlink()
            heard_audio = analyze.load(out / f"{key}.mp3")
            segments, _ = asr.transcribe(heard_audio, language="ru", beam_size=5, condition_on_previous_text=False)
            heard = " ".join(s.text.strip() for s in segments)
            with torch.no_grad():
                utmos = float(mos(torch.from_numpy(heard_audio.copy()).unsqueeze(0), analyze.RATE))
            report[key] = {"variant": variant.stem, "speaker": sid, "load_s": round(load, 1), "seconds": round(len(audio) / 22050, 2),
                           "rtf": round(spent / (len(audio) / 22050), 3), "utmos": round(utmos, 2),
                           "cer": round(analyze.cer(render.PASSAGE, heard), 1), "heard": heard}
            print(f"{key:20s} load {load:5.1f}s  RTF {report[key]['rtf']:.3f}  UTMOS {utmos:.2f}  CER {report[key]['cer']:.1f}", flush=True)
        (out / "variants.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main(*sys.argv[1:])
