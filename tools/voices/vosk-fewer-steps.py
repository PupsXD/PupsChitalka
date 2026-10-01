#!/usr/bin/env python3
"""Makes Vosk TTS 0.10's acoustic model cheaper by rewiring its unrolled flow-matching decoder.

The exported model runs 10 Euler steps (dt = 0.1). At each step the estimator network runs twice,
with and without the condition, and the two velocities are mixed by classifier-free guidance:
    v = v_cond + 0.5 * (v_cond - v_uncond);   x = x + 0.1 * v
That is 20 estimator runs, about 95% of the synthesis time. This script can
  --no-cfg     use v = v_cond (one estimator run per step), and/or
  --steps 5    keep every other step with dt = 0.2 (the kept steps already start at t = 0, 0.2, …)
and then drops every node no output needs any more.

  python tools/voices/vosk-fewer-steps.py model.onnx out.onnx [--no-cfg] [--steps 5]
"""
import argparse
import re

import numpy as np
import onnx
from onnx import helper, numpy_helper


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("src")
    parser.add_argument("dst")
    parser.add_argument("--no-cfg", action="store_true")
    parser.add_argument("--steps", type=int, default=10, choices=(10, 5))
    args = parser.parse_args()

    model = onnx.load(args.src)
    g = model.graph
    producer = {o: n for n in g.node for o in n.output}
    consumers = {}
    for n in g.node:
        for i in n.input:
            consumers.setdefault(i, []).append(n)
    constants = {n.output[0]: n for n in g.node if n.op_type == "Constant"}

    def scalar(name):
        node = constants.get(name)
        return float(numpy_helper.to_array(node.attribute[0].t)) if node is not None else None

    # One guidance mix per step: Sub(v_cond, v_uncond) where both come from estimator_{2p} and _{2p+1}.
    steps = []
    for sub in g.node:
        if sub.op_type != "Sub" or not sub.name.startswith("/matcha/decoder/Sub"):
            continue
        a, b = (re.match(r"/matcha/decoder/estimator(?:_(\d+))?/Mul_1_output_0$", i) for i in sub.input)
        if not (a and b):
            continue
        cond_index = int(a.group(1) or 0)
        weight = consumers[sub.output[0]][0]                      # Mul(Sub, 0.5)
        mix = consumers[weight.output[0]][0]                      # Add(v_cond, 0.5 * …)
        scaled = consumers[mix.output[0]][0]                      # Mul(dt, v)
        update = consumers[scaled.output[0]][0]                   # Add(x, dt * v)
        dt_input = next(i for i in scaled.input if i != mix.output[0])
        steps.append({"p": cond_index // 2, "v_cond": sub.input[0], "mix": mix, "scaled": scaled,
                      "update": update, "dt": dt_input, "dt_value": scalar(dt_input)})
    steps.sort(key=lambda s: s["p"])
    if [s["p"] for s in steps] != list(range(10)) or any(abs(s["dt_value"] - 0.1) > 1e-5 for s in steps):
        raise SystemExit(f"unexpected decoder layout: {[(s['p'], s['dt_value']) for s in steps]}")

    if args.no_cfg:
        for s in steps:
            s["scaled"].input[list(s["scaled"].input).index(s["mix"].output[0])] = s["v_cond"]

    if args.steps == 5:
        for s in steps:
            if s["p"] % 2 == 0:
                name = f"{s['dt']}_x2"
                g.node.insert(0, helper.make_node("Constant", [], [name], value=numpy_helper.from_array(np.array(0.2, dtype=np.float32))))
                s["scaled"].input[list(s["scaled"].input).index(s["dt"])] = name
            else:
                update = s["update"]
                x_in = next(i for i in update.input if i != s["scaled"].output[0])
                for c in consumers.get(update.output[0], []):
                    c.input[:] = [x_in if i == update.output[0] else i for i in c.input]
                for o in g.output:
                    if o.name == update.output[0]:
                        raise SystemExit("the last step cannot be skipped")

    # Drop everything the outputs no longer need.
    producer = {o: n for n in g.node for o in n.output}
    needed, stack = set(), [o.name for o in g.output]
    while stack:
        name = stack.pop()
        node = producer.get(name)
        if node is None or id(node) in needed:
            continue
        needed.add(id(node))
        stack.extend(i for i in node.input if i)
    kept = [n for n in g.node if id(n) in needed]
    used = {i for n in kept for i in n.input}
    before = len(g.node)
    del g.node[:]
    g.node.extend(kept)
    inits = [i for i in g.initializer if i.name in used]
    del g.initializer[:]
    g.initializer.extend(inits)
    onnx.save(model, args.dst)
    print(f"{args.dst}: nodes {before} -> {len(kept)}, no_cfg={args.no_cfg}, steps={args.steps}")


if __name__ == "__main__":
    main()
