#!/usr/bin/env python3
"""Makes Vosk TTS 0.10's acoustic model cheaper by rewiring its unrolled flow-matching decoder.

The exported model runs 10 Euler steps (dt = 0.1). At each step the estimator network runs twice,
with and without the condition, and the two velocities are mixed by classifier-free guidance:
    v = v_cond + 0.5 * (v_cond - v_uncond);   x = x + 0.1 * v
That is 20 estimator runs, about 95% of the synthesis time. This script can
  --no-cfg        use v = v_cond (one estimator run per step), and/or
  --cfg-steps 3   keep guidance only on the first three steps that are kept,
  --steps 5       keep every other step with dt = 0.2 (the kept steps already start at t = 0, 0.2, …),
  --steps 8       keep 8 of the 10 steps; a skipped step's dt goes to the step before it, so the
                  trajectory still ends at t = 1 (0.1 · 10). With guidance on every kept step that is
                  16 estimator runs; --no-cfg makes it 8 instead of 5 · 2 = 10 with CFG.
and then drops every node no output needs any more.

  python tools/voices/vosk-fewer-steps.py model.onnx out.onnx [--no-cfg | --cfg-steps K] [--steps N]

Accepts the graph as published and the same graph after onnxruntime's quant_pre_process (which moves
the dt constants from Constant nodes to initializers; see tools/voices/vosk-prepare.py).
"""
import argparse
import re

import numpy as np
import onnx
from onnx import helper, numpy_helper

STEPS_IN_MODEL = 10
DT = 0.1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("src")
    parser.add_argument("dst")
    parser.add_argument("--no-cfg", action="store_true", help="no classifier-free guidance at all (--cfg-steps 0)")
    parser.add_argument("--cfg-steps", type=int, default=None,
                        help="keep guidance on the first K kept steps (default: every step, or none with --no-cfg)")
    parser.add_argument("--steps", type=int, default=STEPS_IN_MODEL, choices=range(1, STEPS_IN_MODEL + 1))
    args = parser.parse_args()
    if args.no_cfg and args.cfg_steps is not None:
        raise SystemExit("--no-cfg and --cfg-steps are the same knob; give one")
    cfg_steps = 0 if args.no_cfg else (args.steps if args.cfg_steps is None else args.cfg_steps)
    if not 0 <= cfg_steps <= args.steps:
        raise SystemExit(f"--cfg-steps must be between 0 and {args.steps}")

    model = onnx.load(args.src)
    g = model.graph
    consumers = {}
    for n in g.node:
        for i in n.input:
            consumers.setdefault(i, []).append(n)
    constants = {n.output[0]: n for n in g.node if n.op_type == "Constant"}
    initializers = {i.name: i for i in g.initializer}

    def scalar(name):
        """The value of a float32 scalar constant, however the model stores it."""
        if name in initializers:
            return float(numpy_helper.to_array(initializers[name]))
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
    layout = [(s["p"], s["dt_value"]) for s in steps]
    if [s["p"] for s in steps] != list(range(STEPS_IN_MODEL)) or any(
            s["dt_value"] is None or abs(s["dt_value"] - DT) > 1e-5 for s in steps):
        raise SystemExit(f"unexpected decoder layout: {layout}")

    kept = [i * STEPS_IN_MODEL // args.steps for i in range(args.steps)]
    dropped = [s for s in steps if s["p"] not in kept]

    # Drop the steps that are left out: whoever read their x now reads the x they started from.
    for s in dropped:
        update = s["update"]
        x_in = next(i for i in update.input if i != s["scaled"].output[0])
        for c in consumers.get(update.output[0], []):
            c.input[:] = [x_in if i == update.output[0] else i for i in c.input]
        for o in g.output:
            if o.name == update.output[0]:
                raise SystemExit("the last step cannot be skipped")

    # A kept step that covers several original steps integrates with the summed dt.
    added = {}
    for s in steps:
        if s["p"] not in kept:
            continue
        following = next((p for p in kept if p > s["p"]), STEPS_IN_MODEL)
        multiplier = following - s["p"]
        if multiplier == 1:
            continue
        # Some steps share one dt constant, so a name can already be there: it is the same value.
        name = f"{s['dt']}_x{multiplier}"
        if name not in added:
            added[name] = DT * multiplier
            g.initializer.append(numpy_helper.from_array(np.array(DT * multiplier, dtype=np.float32), name))
        s["scaled"].input[list(s["scaled"].input).index(s["dt"])] = name

    # Guidance only where it was asked for; elsewhere the conditional velocity is used as it is.
    for number, s in enumerate([s for s in steps if s["p"] in kept]):
        if number < cfg_steps:
            continue
        scaled = s["scaled"]
        scaled.input[list(scaled.input).index(s["mix"].output[0])] = s["v_cond"]

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
    kept_nodes = [n for n in g.node if id(n) in needed]
    used = {i for n in kept_nodes for i in n.input}
    before = len(g.node)
    del g.node[:]
    g.node.extend(kept_nodes)
    kept_init = [i for i in g.initializer if i.name in used]
    del g.initializer[:]
    g.initializer.extend(kept_init)
    onnx.save(model, args.dst)
    print(f"{args.dst}: nodes {before} -> {len(kept_nodes)}, steps={args.steps}, cfg_steps={cfg_steps}, "
          f"initializers {len(initializers)} -> {len(kept_init)}")


if __name__ == "__main__":
    main()
