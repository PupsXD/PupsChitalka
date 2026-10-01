#!/usr/bin/env python3
"""Checks a decoder rewritten on the JVM (VoskDecoderSteps.halve) against the Python rewrite.

  python tools/voices/vosk-check-steps.py <vosk model dir> <kotlin model-5steps.onnx> <python s5.onnx>

Both models get the same sentence and voice without noise (scales 0, 1, 0), so they must give the
same audio; it also prints the node counts.
"""
import sys

import numpy as np
import onnx
import onnxruntime


def main(model_dir, kotlin_path, python_path):
    from vosk_tts import Model, Synth
    model = Model(model_path=model_dir)
    synth = Synth(model)
    text = "Дункан усмехнулся, но рука его всё ещё лежала на рукояти."
    bert = synth.get_word_bert(text, nopunc=True)
    ids, embs = synth.g2p_multistream(text, bert)
    x = np.expand_dims(np.transpose(np.array(ids, dtype=np.int64)), 0)
    args = {"input": x, "input_lengths": np.array([x.shape[2]], dtype=np.int64),
            "scales": np.array([0.0, 1.0, 0.0], dtype=np.float32), "sid": np.array([28], dtype=np.int64),
            "bert": np.expand_dims(np.transpose(np.array(embs, dtype=np.float32)), 0)}
    audio = {}
    for name, path in (("kotlin", kotlin_path), ("python", python_path)):
        nodes = len(onnx.load(path, load_external_data=False).graph.node)
        session = onnxruntime.InferenceSession(path, providers=["CPUExecutionProvider"])
        audio[name] = session.run(None, args)[0].squeeze()
        print(f"{name}: {nodes} nodes, {audio[name].size} samples")
    a, b = audio["kotlin"], audio["python"]
    n = min(a.size, b.size)
    print("max difference", float(np.abs(a[:n] - b[:n]).max()), "same length", a.size == b.size)


if __name__ == "__main__":
    main(*sys.argv[1:4])
