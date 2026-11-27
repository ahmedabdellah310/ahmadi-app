"""
RATL Rung 3 - Experiment 1b training: same model, ~5x the data.

EncCTC, build_vocab, encode, WINDOW_S, N_MELS, MEL_FRAMES, ENC_FRAMES, BASE,
BATCH, LR and SEED are IMPORTED from 10_train_exp1.py, which is not modified.
The architecture is therefore identical to Experiment 1 by construction, and the
only independent variable is the training corpus.

EPOCHS is the one training-schedule value that differs: Experiment 1's val CTC
bottomed at epoch 6 of 30 on 6.0 h. With ~5x the data an epoch is ~5x the steps,
so EPOCHS is reduced to 12 to cover roughly 2x Experiment 1's total optimiser
steps. Best-val checkpointing is unchanged, so this cannot flatter the result -
it only bounds wall-clock. Recorded here rather than applied silently.

$0: local RTX 4060. THROWAWAY SPIKE CODE. Not RATL Core.
"""

import importlib.util
import json
import pathlib
import sys
import time

import numpy as np
import torch
import torch.nn as nn

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("tr", HERE / "10_train_exp1.py")
tr = importlib.util.module_from_spec(spec)
sys.modules["tr"] = tr
spec.loader.exec_module(tr)

TAG = sys.argv[2] if len(sys.argv) > 2 else "exp1b"
CORPUS = HERE / "corpus" / (sys.argv[1] if len(sys.argv) > 1 else "exp1b-train.json")
FEATS = HERE / ".cache" / "feats"
CKPT = HERE / "models" / TAG
RESULTS = HERE / "results"
for p in (FEATS, CKPT, RESULTS):
    p.mkdir(parents=True, exist_ok=True)

EPOCHS = 12
VAL_FRAC = 0.05


def precompute(clips, fe):
    """Cache log-mel features (float16). Same transform as Experiment 1."""
    import librosa

    path = FEATS / f"{TAG}_{len(clips)}_{tr.MEL_FRAMES}.npy"
    meta = FEATS / f"{TAG}_{len(clips)}_{tr.MEL_FRAMES}.json"
    if path.exists() and meta.exists():
        print(f"[feats] reusing {path.name}")
        return np.load(path, mmap_mode="r"), json.loads(meta.read_text())
    print(f"[feats] computing {len(clips)} x ({tr.N_MELS},{tr.MEL_FRAMES}) ...")
    arr = np.lib.format.open_memmap(
        path, mode="w+", dtype=np.float16,
        shape=(len(clips), tr.N_MELS, tr.MEL_FRAMES))
    lens = []
    bad = []
    t0 = time.time()
    for i, c in enumerate(clips):
        # A full-size but internally damaged mp3 passes every filesystem check
        # and only fails at decode. Skip it and record it rather than aborting a
        # 13k-clip run; the index is excluded from training downstream.
        try:
            a, _ = librosa.load(str(tr.AUDIO / c["audio"]), sr=tr.SR, mono=True)
        except Exception as e:  # noqa: BLE001
            print(f"        [bad audio] {c['audio']}: {type(e).__name__}: {e}",
                  flush=True)
            bad.append(i)
            lens.append(1)
            continue
        n = min(len(a), int(tr.WINDOW_S * tr.SR))
        f = fe(a[:n], sampling_rate=tr.SR, return_tensors="np",
               padding="max_length", max_length=int(tr.WINDOW_S * tr.SR))
        m = f["input_features"][0][:, :tr.MEL_FRAMES]
        if m.shape[1] < tr.MEL_FRAMES:
            m = np.pad(m, ((0, 0), (0, tr.MEL_FRAMES - m.shape[1])),
                       constant_values=m.min())
        arr[i] = m.astype(np.float16)
        lens.append(int(min(n / tr.SR * 50, tr.ENC_FRAMES)))
        if (i + 1) % 500 == 0:
            print(f"        {i+1}/{len(clips)}  {time.time()-t0:.0f}s")
    arr.flush()
    meta.write_text(json.dumps({"lens": lens, "bad": bad}))
    if bad:
        print(f"[feats] {len(bad)} clip(s) unreadable, excluded from training",
              flush=True)
    return np.load(path, mmap_mode="r"), {"lens": lens, "bad": bad}


def main():
    torch.manual_seed(tr.SEED)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[dev] {dev}  {torch.cuda.get_device_name(0) if dev == 'cuda' else ''}")

    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(tr.WINDOW_S)
    fe.n_samples = int(tr.WINDOW_S * tr.SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    data = json.loads(CORPUS.read_text(encoding="utf-8"))
    clips = data["clips"]
    print(f"[data] {len(clips)} clips  {data['total_hours']} h  "
          f"{len(data['stats'])} reciters")

    itos, stoi = tr.build_vocab(clips)
    print(f"[vocab] {len(itos)}: {''.join(itos[1:])}")
    (CKPT / "vocab.json").write_text(json.dumps(itos, ensure_ascii=False),
                                     encoding="utf-8")

    # The vocab must match Experiment 1's or the G2 comparison shifts basis.
    exp1_vocab = json.loads(
        (HERE / "models" / "exp1" / "vocab.json").read_text(encoding="utf-8"))
    if itos != exp1_vocab:
        print(f"[vocab] NOTE: differs from Experiment 1 "
              f"({len(exp1_vocab)} -> {len(itos)}); "
              f"added={sorted(set(itos) - set(exp1_vocab))} "
              f"removed={sorted(set(exp1_vocab) - set(itos))}")

    feats, meta = precompute(clips, fe)
    lens = meta["lens"]
    targets = [tr.encode(c["words"], stoi) for c in clips]

    bad = set(meta.get("bad", []))
    idx = np.array([i for i in range(len(clips)) if i not in bad])
    if bad:
        dropped_h = sum(clips[i]["duration_s"] for i in bad) / 3600
        print(f"[data] excluding {len(bad)} unreadable clip(s) "
              f"({dropped_h:.3f} h); {len(idx)} usable")
    rng = np.random.default_rng(tr.SEED)
    rng.shuffle(idx)
    n_val = max(16, int(len(idx) * VAL_FRAC))
    val_idx, tr_idx = idx[:n_val], idx[n_val:]
    print(f"[split] train={len(tr_idx)} val={len(val_idx)}")

    model = tr.EncCTC(len(itos)).to(dev)
    n_par = sum(p.numel() for p in model.parameters())
    print(f"[model] {n_par/1e6:.2f}M params")

    opt = torch.optim.AdamW(model.parameters(), lr=tr.LR, weight_decay=0.01)
    steps = EPOCHS * (len(tr_idx) // tr.BATCH)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=tr.LR,
                                                total_steps=steps, pct_start=0.1)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    print(f"[sched] {steps} total steps ({EPOCHS} epochs x "
          f"{len(tr_idx)//tr.BATCH} steps)")

    def batches(ids, bs, shuffle=True):
        ids = ids.copy()
        if shuffle:
            rng.shuffle(ids)
        for i in range(0, len(ids) - bs + 1, bs):
            yield ids[i:i + bs]

    def run_batch(b, train):
        x = torch.from_numpy(np.asarray(feats[b], dtype=np.float32)).to(dev)
        tg = [targets[i] for i in b]
        tl = torch.tensor([len(t) for t in tg], dtype=torch.long)
        flat = torch.tensor([c for t in tg for c in t], dtype=torch.long)
        il = torch.tensor([lens[i] for i in b], dtype=torch.long)
        with torch.autocast(dev, dtype=torch.bfloat16, enabled=(dev == "cuda")):
            logits = model(x)
        lp = torch.log_softmax(logits.float(), dim=-1).transpose(0, 1)
        loss = ctc(lp, flat, il, tl)
        if train:
            loss.backward()
        return loss

    log, best, t0 = [], 1e9, time.time()
    for ep in range(1, EPOCHS + 1):
        model.train()
        tot = n = 0
        for b in batches(tr_idx, tr.BATCH):
            opt.zero_grad(set_to_none=True)
            loss = run_batch(b, True)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            if sched.last_epoch < steps - 1:
                sched.step()
            tot += loss.item()
            n += 1
        tr_loss = tot / max(1, n)

        model.eval()
        vt = vn = 0
        with torch.no_grad():
            for b in batches(val_idx, min(tr.BATCH, len(val_idx)), shuffle=False):
                vt += run_batch(b, False).item()
                vn += 1
        va_loss = vt / max(1, vn)
        log.append({"epoch": ep, "train_ctc": round(tr_loss, 4),
                    "val_ctc": round(va_loss, 4), "sec": round(time.time() - t0)})
        print(f"  ep {ep:>3}  train {tr_loss:.4f}  val {va_loss:.4f}  "
              f"({time.time()-t0:.0f}s)")
        if va_loss < best:
            best = va_loss
            torch.save({"model": model.state_dict(), "vocab": itos,
                        "enc_frames": tr.ENC_FRAMES, "mel_frames": tr.MEL_FRAMES,
                        "epoch": ep, "val_ctc": va_loss}, CKPT / "best.pt")

    (RESULTS / f"16_train_{TAG}.json").write_text(json.dumps(
        {"experiment": TAG, "base": tr.BASE, "params": n_par,
         "vocab_size": len(itos), "vocab": itos,
         "epochs": EPOCHS, "batch": tr.BATCH, "lr": tr.LR,
         "window_s": tr.WINDOW_S, "enc_frames": tr.ENC_FRAMES,
         "train_clips": len(tr_idx), "val_clips": len(val_idx),
         "unreadable_clips_excluded": len(bad),
         "hours": data["total_hours"], "reciters": len(data["stats"]),
         "best_val_ctc": round(best, 4),
         "exp1_best_val_ctc": 0.4006,
         "device": torch.cuda.get_device_name(0) if dev == "cuda" else "cpu",
         "log": log}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nbest val CTC {best:.4f} (exp1: 0.4006) -> {CKPT/'best.pt'}")


if __name__ == "__main__":
    main()
