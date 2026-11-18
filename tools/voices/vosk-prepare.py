#!/usr/bin/env python3
"""Prepares Vosk TTS 0.10 (Alpha Cephei, Apache 2.0) so it starts in seconds instead of a minute.

The published acoustic model has dynamic shapes that ONNX Runtime resolves again on every
InferenceSession load: 45-65 s for the 235 MB graph, and caching an optimized `.ort` copy does not
help either. `onnxruntime.quantization.shape_inference.quant_pre_process` resolves the shapes once,
which brings the load to about 2.6 s; ONNX Runtime's own optimized copy of that graph loads in about
1.2 s. Weights and precision are untouched — this is not quantization.

  model-prepared.onnx  the graph after quant_pre_process (drop-in for model.onnx)
  model-fast.onnx      the same, with the decoder cut to fewer, larger steps (vosk-fewer-steps.py)
  model-fast.onnx.ort  ONNX Runtime's optimized copy, with --ort; good only on the machine that built it
  bert-int8.onnx       ruBERT in INT8, with --int8: 624 MB → 157 MB, 4× faster, a little less precise

The app does the same on a phone in a different order: it downloads model-prepared.onnx from
HuggingFace (NGC404/sgspeech-vosk-tts-ru-0.10-prepared, the same quant_pre_process step), rewrites
the decoder itself in VoskDecoderSteps.kt, and lets ONNX Runtime write the .ort copy on first start.
This script is for the stand and for preparing a model by hand.

  python tools/voices/vosk-prepare.py <vosk model dir> [--out <dir>] [--steps 5] [--cfg-steps 5]
                                     [--int8] [--ort] [--correlation]

The defaults are what the app installs: 5 steps of which all keep the guidance. The stand measured
that variant as the closest to the published model; 8 steps with no guidance are 17% faster and lose
0.1–0.2 UTMOS, 8 steps with 3 guided are no faster and lose 0.05–0.09 (see vosk-compare-variants.py).

Needs: onnx, onnxruntime, numpy, vosk-tts. Writes nothing into <vosk model dir>.
"""
import argparse
import os
import shutil
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort

HERE = Path(__file__).parent
THREADS = 4
SENTENCE = "Дункан усмехнулся, но рука его всё ещё лежала на рукояти."


def load_module(name):
    import importlib.util
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def options(threads=THREADS):
    o = ort.SessionOptions()
    o.intra_op_num_threads = threads
    o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return o


def load(path, cache=None):
    """Seconds InferenceSession took to come up with the model at [path]."""
    started = time.perf_counter()
    session = ort.InferenceSession(str(path), options(), providers=["CPUExecutionProvider"])
    spent = time.perf_counter() - started
    return session, spent


def link_or_copy(source, target):
    try:
        os.link(source, target)
    except OSError:
        shutil.copyfile(source, target)


def frontend_inputs(model_dir, model_path, text=SENTENCE, speaker=28):
    """What the Python frontend hands the acoustic model: phoneme ids and word embeddings."""
    work = Path(tempfile.mkdtemp(prefix="vosk-frontend-"))
    for name in ("config.json", "dictionary"):
        link_or_copy(Path(model_dir) / name, work / name)
    (work / "bert").mkdir()
    link_or_copy(Path(model_dir) / "bert" / "vocab.txt", work / "bert" / "vocab.txt")
    link_or_copy(Path(model_dir) / "bert" / "model.onnx", work / "bert" / "model.onnx")
    link_or_copy(model_path, work / "model.onnx")
    from vosk_tts import Model, Synth
    model = Model(model_path=str(work))
    synth = Synth(model)
    bert = synth.get_word_bert(text, nopunc=True)
    ids, embeddings = synth.g2p_multistream(text, bert)
    x = np.expand_dims(np.transpose(np.array(ids, dtype=np.int64)), 0)
    return {
        "input": x,
        "input_lengths": np.array([x.shape[2]], dtype=np.int64),
        "scales": np.array([0.0, 1.0, 0.0], dtype=np.float32),
        "sid": np.array([speaker], dtype=np.int64),
        "bert": np.expand_dims(np.transpose(np.array(embeddings, dtype=np.float32)), 0),
    }, work


def prepare(model_dir, out_dir, steps, cfg_steps, int8, want_ort, correlation):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    original = Path(model_dir) / "model.onnx"
    prepared = out / "model-prepared.onnx"
    fast = out / "model-fast.onnx"

    from onnxruntime.quantization.shape_inference import quant_pre_process
    started = time.perf_counter()
    quant_pre_process(str(original), str(prepared), skip_symbolic_shape=False)
    print(f"quant_pre_process: {time.perf_counter() - started:.1f} s, "
          f"{len(onnx.load(str(prepared), load_external_data=False).graph.node)} nodes")

    fewer = load_module("vosk-fewer-steps")
    sys.argv = ["vosk-fewer-steps", str(prepared), str(fast), "--steps", str(steps), "--cfg-steps", str(cfg_steps)]
    fewer.main()

    timings = {}
    for name, path in (("original", original), ("prepared", prepared), ("fast", fast)):
        session, spent = load(path)
        timings[name] = (spent, session)

    ort_file = Path(str(fast) + ".ort")
    if want_ort:
        if ort_file.exists():
            ort_file.unlink()
        caching = options()
        caching.optimized_model_filepath = str(ort_file)
        started = time.perf_counter()
        ort.InferenceSession(str(fast), caching, providers=["CPUExecutionProvider"])
        print(f"optimized copy written in {time.perf_counter() - started:.1f} s, {ort_file.stat().st_size // 2**20} MB")
        started = time.perf_counter()
        ort.InferenceSession(str(ort_file), options(), providers=["CPUExecutionProvider"])
        timings["optimized copy"] = (time.perf_counter() - started, None)

    if int8:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        bert_int8 = out / "bert-int8.onnx"
        started = time.perf_counter()
        quantize_dynamic(str(Path(model_dir) / "bert" / "model.onnx"), str(bert_int8), weight_type=QuantType.QInt8)
        print(f"bert INT8: {time.perf_counter() - started:.1f} s, "
              f"{(Path(model_dir) / 'bert' / 'model.onnx').stat().st_size // 2**20} MB → {bert_int8.stat().st_size // 2**20} MB")

    if correlation:
        args, work = frontend_inputs(model_dir, fast)
        audio = {}
        for name in ("original", "prepared", "fast"):
            audio[name] = timings[name][1].run(None, args)[0].squeeze()
        for name in ("prepared", "fast"):
            a, b = audio["original"], audio[name]
            n = min(a.size, b.size)
            dot = float(np.dot(a[:n], b[:n]))
            norm = float(np.linalg.norm(a[:n]) * np.linalg.norm(b[:n]))
            print(f"{name} vs original at noise 0: samples {a.size} vs {b.size}, "
                  f"correlation {dot / norm:.8f}, max difference {float(np.abs(a[:n] - b[:n]).max()):.2e}")
        shutil.rmtree(work, ignore_errors=True)

    print()
    for name, (spent, _) in timings.items():
        print(f"{name:14s} {spent:6.2f} s to load")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("model_dir", type=Path, help="vosk-model-tts-ru-0.10-multi as downloaded")
    parser.add_argument("--out", type=Path, default=None, help="where to write the prepared files (default: <model dir>/prepared)")
    parser.add_argument("--steps", type=int, default=5)
    parser.add_argument("--cfg-steps", type=int, default=5, help="kept steps that still run guidance (default: all)")
    parser.add_argument("--int8", action="store_true", help="also quantize bert.onnx")
    parser.add_argument("--ort", action="store_true", help="also write ONNX Runtime's optimized copy")
    parser.add_argument("--correlation", action="store_true", help="compare the audio with the original at noise 0 (loads all three graphs)")
    args = parser.parse_args()
    prepare(
        args.model_dir,
        args.out or args.model_dir / "prepared",
        args.steps, args.cfg_steps, args.int8, args.ort, args.correlation,
    )


if __name__ == "__main__":
    main()
