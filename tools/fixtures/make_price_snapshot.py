#!/usr/bin/env python3
"""
Trims models.dev's api.json into the phone's bundled price snapshot:
{ "<providerId>": { "<modelId>": {"input":x,"output":y,"cache_read":z,
"cache_write":w,"cache_write_1h":h?,"tiers":[...]?} } }, only models with a
cost, gzipped into the APK assets. Run against a fresh api.json:

    python make_price_snapshot.py <path-to-api.json>
"""
import gzip
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("Usage: python make_price_snapshot.py <models-dev-api.json>")
SRC = sys.argv[1]
OUT = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "prices" / "models-dev.json.gz"


def keep_cost(c):
    if not isinstance(c, dict):
        return None
    out = {}
    for k in ("input", "output", "cache_read", "cache_write"):
        v = c.get(k)
        if isinstance(v, (int, float)):
            out[k] = float(v)
    h = c.get("cache_write_1h")
    if isinstance(h, (int, float)) and h > 0:
        out["cache_write_1h"] = float(h)
    tiers = c.get("tiers")
    if isinstance(tiers, list):
        keep = []
        for t in tiers:
            if not isinstance(t, dict):
                continue
            tier = t.get("tier")
            above = t.get("above")
            if isinstance(tier, dict):
                if tier.get("type", "context") != "context":
                    continue
                above = tier.get("size")
            if not isinstance(above, (int, float)) or above <= 0:
                continue
            tt = {"above": int(above)}
            for k in ("input", "output", "cache_read", "cache_write", "cache_write_1h"):
                v = t.get(k)
                if isinstance(v, (int, float)):
                    tt[k] = float(v)
            keep.append(tt)
        if keep:
            keep.sort(key=lambda x: x["above"])
            out["tiers"] = keep
    if not out:
        return None
    return out


def main():
    raw = json.loads(Path(SRC).read_text(encoding="utf-8"))
    providers = {}
    canon = {}
    models_total = 0
    for pid, pdata in raw.items():
        if not isinstance(pdata, dict):
            continue
        models = pdata.get("models")
        if not isinstance(models, dict):
            continue
        kept = {}
        per_provider_canon = {}
        for mid, m in models.items():
            if not isinstance(m, dict):
                continue
            cost = keep_cost(m.get("cost"))
            if cost is not None:
                kept[mid] = cost
            # canonical_model_id is how the desktop's MakerPriced and
            # PricedBy know what an entry is (catalog.go madeByOf,
            # canonicallyPriced): DeepSeek sells V4.1 Flash as deepseek-flash
            # whose canonical is deepseek/deepseek-v4.1-flash, the id the
            # agents serve it under. Kept per provider, as the desktop keys it.
            c = m.get("canonical_model_id")
            if isinstance(c, str) and c and cost is not None:
                per_provider_canon[mid] = c
        if kept:
            providers[pid] = kept
            models_total += len(kept)
        if per_provider_canon:
            canon[pid] = per_provider_canon
    snapshot = {
        "fetchedAt": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "source": "models.dev/api.json",
        "providers": providers,
        # provider id -> model id -> canonical "<maker>/<id>"
        "canonical": canon,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    blob = gzip.compress(json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")).encode())
    OUT.write_bytes(blob)
    print(f"providers={len(providers)} models={models_total} canonical={sum(len(v) for v in canon.values())} -> {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
