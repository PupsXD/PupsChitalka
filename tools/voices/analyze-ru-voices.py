#!/usr/bin/env python3
"""Adds measured numbers to every voice that render-ru-voices.py wrote into voices.json.

  pitch  median F0 of the voice, Hz (YIN on voiced frames): sorts voices from deep to high and
         shows which are male; below ≈165 Hz is a man's voice
  cer    character error rate of what Whisper hears against the passage, letters only, %: how clearly the voice
         says every word. It does not see stress: «з+амок» and «зам+ок» are the same letters.

  utmos  predicted naturalness 1–5 (UTMOS22 strong, trained on English listening tests; with
         --utmos). Alpha Cephei uses it for Russian voices too; read it as a rough sort, not a verdict.

  python tools/voices/analyze-ru-voices.py build/voices/candidates [--whisper large-v3-turbo] [--recompute | --utmos]

Needs: numpy, faster-whisper and ffmpeg on PATH. The Whisper model is downloaded on first use.
"""
import argparse
import json
import re
import subprocess
from pathlib import Path

import numpy as np

RATE = 16000


def load(path):
    raw = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32)


def yin_pitch(audio, rate=RATE, low=60.0, high=400.0, threshold=0.15):
    """Median F0 over voiced 40 ms frames with the YIN difference function (de Cheveigné & Kawahara, 2002)."""
    frame = int(0.04 * rate)
    min_lag, max_lag = int(rate / high), int(rate / low)
    loud = np.percentile(np.abs(audio), 95) or 1.0
    values = []
    for start in range(0, len(audio) - frame - max_lag, frame // 2):
        x = audio[start:start + frame + max_lag].astype(np.float64)
        if np.sqrt(np.mean(x[:frame] ** 2)) < 0.05 * loud:
            continue
        diff = np.array([np.sum((x[:frame] - x[lag:lag + frame]) ** 2) for lag in range(max_lag + 1)])
        cmnd = np.ones_like(diff)
        cumulative = np.cumsum(diff[1:])
        cmnd[1:] = diff[1:] * np.arange(1, max_lag + 1) / np.where(cumulative == 0, 1, cumulative)
        below = np.where(cmnd[min_lag:] < threshold)[0]
        if len(below) == 0:
            continue
        lag = min_lag + below[0]
        while lag + 1 <= max_lag and cmnd[lag + 1] < cmnd[lag]:
            lag += 1
        values.append(rate / lag)
    return float(np.median(values)) if values else 0.0


def letters(text):
    """Only the letters: «вполголоса» and «в полголоса» are the same speech."""
    return re.sub(r"[^а-я]+", "", text.lower().replace("ё", "е"))


def cer(reference, hypothesis):
    a, b = letters(reference), letters(hypothesis)
    row = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        prev, row[0] = row[0], i
        for j, cb in enumerate(b, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (ca != cb))
    return 100.0 * row[-1] / max(1, len(a))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--whisper", default="large-v3-turbo")
    parser.add_argument("--recompute", action="store_true", help="only recount CER from the stored transcripts")
    parser.add_argument("--utmos", action="store_true", help="only add UTMOS, a predicted naturalness score 1–5")
    args = parser.parse_args()
    report_path = args.folder / "voices.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if args.recompute:
        for voice in report["voices"].values():
            if "heard" in voice:
                voice["cer"] = round(cer(report["passage"], voice["heard"]), 1)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
        return
    if args.utmos:
        import torch
        torch.set_num_threads(8)
        mos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True)
        for key, voice in report["voices"].items():
            audio = torch.from_numpy(load(args.folder / f"{key}.mp3").copy()).unsqueeze(0)
            with torch.no_grad():
                voice["utmos"] = round(float(mos(audio, RATE)), 2)
            print(f"{key:40s} UTMOS {voice['utmos']:.2f}", flush=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
        return
    from faster_whisper import WhisperModel
    asr = WhisperModel(args.whisper, device="cpu", compute_type="int8", cpu_threads=8)
    for key, voice in report["voices"].items():
        audio = load(args.folder / f"{key}.mp3")
        segments, _ = asr.transcribe(audio, language="ru", beam_size=5, condition_on_previous_text=False)
        heard = " ".join(s.text.strip() for s in segments)
        voice["pitch"] = round(yin_pitch(audio))
        voice["heard"] = heard
        voice["cer"] = round(cer(report["passage"], heard), 1)
        print(f"{key:40s} F0 {voice['pitch']:4d} Hz  CER {voice['cer']:5.1f}%  {heard}", flush=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
