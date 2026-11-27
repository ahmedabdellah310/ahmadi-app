"""Measure real CPU training step time for Experiment 1, to decide GPU-wait vs CPU-run."""
import importlib.util
import json
import pathlib
import sys
import time

import numpy as np
import torch
import torch.nn as nn

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("t", HERE / "10_train_exp1.py")
t = importlib.util.module_from_spec(spec)
sys.modules["t"] = t
spec.loader.exec_module(t)

torch.set_num_threads(max(1, (torch.get_num_threads() or 8)))
print(f"[cpu] torch threads = {torch.get_num_threads()}")

data = json.loads((t.CORPUS / "exp1-train.json").read_text(encoding="utf-8"))
clips = data["clips"]
itos, stoi = t.build_vocab(clips)
feats = np.load(t.FEATS / f"exp1_{len(clips)}_{t.MEL_FRAMES}.npy", mmap_mode="r")
meta = json.loads((t.FEATS / f"exp1_{len(clips)}_{t.MEL_FRAMES}.json").read_text())
lens = meta["lens"]
targets = [t.encode(c["words"], stoi) for c in clips]

model = t.EncCTC(len(itos))
model.train()
opt = torch.optim.AdamW(model.parameters(), lr=3e-4)
ctc = nn.CTCLoss(blank=0, zero_infinity=True)

BS = t.BATCH
n_steps = 5
times = []
for s in range(n_steps + 1):
    b = list(range(s * BS, s * BS + BS))
    x = torch.from_numpy(np.asarray(feats[b], dtype=np.float32))
    tg = [targets[i] for i in b]
    tl = torch.tensor([len(v) for v in tg], dtype=torch.long)
    flat = torch.tensor([c for v in tg for c in v], dtype=torch.long)
    il = torch.tensor([lens[i] for i in b], dtype=torch.long)
    t0 = time.time()
    opt.zero_grad(set_to_none=True)
    logits = model(x)
    lp = torch.log_softmax(logits.float(), dim=-1).transpose(0, 1)
    loss = ctc(lp, flat, il, tl)
    loss.backward()
    opt.step()
    dt = time.time() - t0
    if s:  # skip warmup
        times.append(dt)
    print(f"  step {s}  {dt:.2f}s  loss {loss.item():.3f}")

mean = float(np.mean(times))
steps_per_epoch = len(clips) // BS
print(f"\nmean step: {mean:.2f}s   steps/epoch: {steps_per_epoch}")
print(f"epoch: {mean*steps_per_epoch/60:.1f} min")
for e in (5, 10, 30):
    print(f"  {e:>2} epochs -> {mean*steps_per_epoch*e/3600:.2f} h")
