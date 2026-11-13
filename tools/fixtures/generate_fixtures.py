#!/usr/bin/env python3
"""
Cross-language fixture generator for the Magpie Android usage dashboard.

Seals synthetic day packs exactly as yetone/magpie's backup.SealData does
(verified against internal/backup/data.go + backup.go @ 6acf5ee5):

    plain JSON -> gzip -> AES-256-GCM(PBKDF2-HMAC-SHA256(pass, salt, 600000, 32B))
    AAD = "magpie-data/1/pbkdf2-sha256/600000/" + salt.lower-hex

The expected aggregates below were computed by hand from the upstream
Totals rules, NOT by the Kotlin code under test, so the Android decoder and
aggregator are checked against an independent implementation.

Output: app/src/test/resources/fixtures/fixtures.json  (synthetic only)
"""
import gzip
import hashlib
import json
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PASSPHRASE = "fixture-passphrase-合成样本专用"
ITERATIONS = 600_000

OUT = Path(__file__).resolve().parents[2] / "app" / "src" / "test" / "resources" / "fixtures" / "fixtures.json"


def seal(data: bytes, passphrase: str, salt: bytes, nonce: bytes) -> bytes:
    key = hashlib.pbkdf2_hmac("sha256", passphrase.encode(), salt, ITERATIONS, dklen=32)
    aad = f"magpie-data/1/pbkdf2-sha256/{ITERATIONS}/{salt.hex()}".encode()
    sealed = AESGCM(key).encrypt(nonce, data, aad)
    envelope = {
        "format": "magpie-data",
        "version": 1,
        "kdf": "pbkdf2-sha256",
        "iterations": ITERATIONS,
        "salt": salt.hex(),  # deliberately hex to prove the parser reads Base64
        "nonce": nonce.hex(),
        "data": sealed.hex(),
    }
    # real envelopes are Base64; we hex here to make wrong-format failures loud
    # — no: envelopes must be Base64. Redo properly below.
    import base64
    envelope["salt"] = base64.b64encode(salt).decode()
    envelope["nonce"] = base64.b64encode(nonce).decode()
    envelope["data"] = base64.b64encode(sealed).decode()
    return json.dumps(envelope, separators=(",", ":")).encode()


COMPUTER = "0123456789abcdef0123456789abcdef"
COMPUTER2 = "fedcba9876543210fedcba9876543210"

CST = timezone(timedelta(hours=8))


def t(y, mo, d, h, mi=0, s=0):
    return datetime(y, mo, d, h, mi, s, tzinfo=CST)


def call(t_, **kw):
    base = {
        "t": t_.strftime("%Y-%m-%dT%H:%M:%SZ") if t_.tzinfo == timezone.utc else t_.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "in": 0, "out": 0, "cache_read": 0, "cache_write": 0, "status": 200, "ms": 1000,
    }
    base.update(kw)
    return base


# ---------------------------------------------------------------- fixture 1
# 2026-03-31 (CST): the acceptance sample.
#   plain row        : in=1000 out=200 cache_read=3000 cache_write=0 -> all=4200
#   cache_unknown row: in=5000 out=100 (excluded from hit-rate denominator)
#   handed-on row    : in=800, handed_on=true, status=500 (tokens yes, request no)
#   retried row      : tries=2, status=200 (answered after a failed try)
#   canceled row     : status=499 err_type=agent_canceled
#   failed row       : status=500 (gateway, provider set)
#   explicit cost 0  : cost=0.0 -> priced, not unpriced
#   priced row       : cost=0.012
#   session row      : provider=session-unknown source=log
FIXTURE1_CALLS = [
    call(t(2026, 3, 31, 10, 0), agent="demo", provider="openai", model="gpt-x",
         **{"in": 1000, "out": 200, "cache_read": 3000, "cache_write": 0}, cost=0.012),
    call(t(2026, 3, 31, 10, 5), agent="demo", provider="kiro", model="kiro-m",
         **{"in": 5000, "out": 100}, cache_unknown=True),
    call(t(2026, 3, 31, 10, 10), agent="demo", provider="anthropic", model="claude-x",
         **{"in": 800, "out": 0}, status=500, err="boom", handed_on=True),
    call(t(2026, 3, 31, 10, 15), agent="demo", provider="anthropic", model="claude-x",
         **{"in": 900, "out": 300}, tries=2),
    call(t(2026, 3, 31, 10, 20), agent="demo", provider="openai", model="gpt-x",
         **{"in": 400, "out": 50}, status=499, err_type="agent_canceled", err="the agent canceled the request"),
    call(t(2026, 3, 31, 10, 25), agent="demo", provider="gemini", model="gem-x",
         **{"in": 111, "out": 22}, status=502, err="bad gateway"),
    call(t(2026, 3, 31, 10, 30), agent="demo", provider="openai", model="gpt-x",
         **{"in": 0, "out": 0}, cost=0.0),
    call(t(2026, 3, 31, 10, 35), agent="codex", provider="session-unknown", model="gpt-5",
         **{"in": 2000, "out": 500}, source="log"),
    call(t(2026, 3, 31, 10, 40), agent="demo", provider="openai", model="gpt-x",
         **{"in": 0, "out": 0, "cache_read": 0, "cache_write": 0}, rejected=True, status=400, err="rejected locally"),
]

# Expected, computed from upstream rules by hand:
#   calls (final requests) = 9 rows - 1 handed_on - 1 rejected = 7
#     but note: rejected rows do not enter sums; count only skips handed_on.
#     Totals.count runs for every non-rejected row that reaches add(): rows
#     reaching add are the 8 non-rejected ones (9 - 1 rejected). One of those
#     is handed_on -> calls = 7.  Wait: 9 rows, rejected excluded from add
#     entirely (counts.add returns before), so 8 rows reach Totals.count;
#     handed_on returns before Calls++ -> calls = 7.
#   errors: failed rows among the 7 = the 502 row -> 1
#     (the handed-on 500 row is not a request outcome; the canceled row is
#      neither failed nor answered)
#   canceled = 1; retried = 1 (tries=2 answered)
#   tokens (all) = in+out+cr+cw over the 8 non-rejected rows:
#     row1: 1000+200+3000+0 = 4200
#     row2: 5000+100        = 5100
#     row3: 800+0           = 800
#     row4: 900+300         = 1200
#     row5: 400+50          = 450
#     row6: 111+22          = 133
#     row7: 0
#     row8: 2000+500        = 2500
#     total = 4200+5100+800+1200+450+133+0+2500 = 14383
#   hit rate: excluded row2 (cache_unknown) prompt = in+cr+cw = 5000 (out aside);
#     denominator = (sum in + cr + cw of all non-rejected rows) - cacheUnknown
#     — upstream subtracts CacheUnknown from the whole denominator, where
#     CacheUnknown is the SUM of cacheUnknownIn over flagged rows = 5000.
#     sum of in+cr+cw over all 8 rows = 4000+800+900+400+111+0+5000+2000 = 13211
#     denominator = 13211 - 5000 = 8211 ; numerator = cacheRead total = 3000
#     rate = 3000/8211 ≈ 0.36537
#   cost = 0.012 (row1) + 0.0 (row7, explicit zero counts as priced)
#     unpriced = rows with in+out>0 and cost absent among non-rejected:
#     rows 2,3,4,5,6,8 -> 6
EXPECTED1 = {
    "calls": 7, "errors": 1, "retried": 1, "canceled": 1,
    "allTokens": 14383,
    "hitRateNumerator": 3000, "hitRateDenominator": 8211,
    "costUsd": 0.012, "unpriced": 6,
    "input": 10211, "output": 1172,
    "cacheRead": 3000, "cacheWrite": 0,
    "cacheUnknownCalls": 1, "cacheUnknownTokens": 5000,
}

# ---------------------------------------------------------------- fixture 2
# A second computer, different day, minimal: proves multi-device + ordering.
FIXTURE2_CALLS = [
    call(t(2026, 4, 1, 8, 0), agent="other", provider="deepseek", model="ds-v4",
         **{"in": 100, "out": 20}, cost=0.001),
]
EXPECTED2 = {
    "calls": 1, "errors": 0, "retried": 0, "canceled": 0,
    "allTokens": 120, "hitRateNumerator": 0, "hitRateDenominator": 100,
    "costUsd": 0.001, "unpriced": 0, "input": 100, "output": 20,
    "cacheRead": 0, "cacheWrite": 0, "cacheUnknownCalls": 0, "cacheUnknownTokens": 0,
}

# ---------------------------------------------------------------- fixture 3
# Wrong passphrase / corrupt: sealed with a different passphrase.
FIXTURE3_CALLS = [call(t(2026, 4, 1, 9, 0), provider="x", **{"in": 1})]


def day_pack(computer, name, day, calls):
    return {"computer": computer, "name": name, "day": day, "calls": calls}


def main():
    import base64
    salt1 = bytes(range(16))                  # 16 bytes, deterministic
    nonce1 = bytes(range(12))
    salt2 = bytes(range(16, 32))
    nonce2 = bytes(range(12, 24))
    salt3 = bytes(range(32, 48))
    nonce3 = bytes(range(24, 36))

    pack1 = json.dumps(day_pack(COMPUTER, "Demo-PC", "2026-03-31", FIXTURE1_CALLS), separators=(",", ":")).encode()
    pack2 = json.dumps(day_pack(COMPUTER2, "Demo-Laptop", "2026-04-01", FIXTURE2_CALLS), separators=(",", ":")).encode()
    pack3 = json.dumps(day_pack(COMPUTER, "Demo-PC", "2026-04-02", FIXTURE3_CALLS), separators=(",", ":")).encode()

    sealed1 = base64.b64encode(seal(gzip.compress(pack1), PASSPHRASE, salt1, nonce1)).decode()
    sealed2 = base64.b64encode(seal(gzip.compress(pack2), PASSPHRASE, salt2, nonce2)).decode()
    sealed3 = base64.b64encode(seal(gzip.compress(pack3), "another-passphrase", salt3, nonce3)).decode()

    fixtures = {
        "passphrase": PASSPHRASE,
        "iterations": ITERATIONS,
        "files": [
            {
                "name": f"{COMPUTER}-2026-03-31.magpie-usage",
                "sealedBase64": sealed1,
                "expected": EXPECTED1,
            },
            {
                "name": f"{COMPUTER2}-2026-04-01.magpie-usage",
                "sealedBase64": sealed2,
                "expected": EXPECTED2,
            },
            {
                "name": f"{COMPUTER}-2026-04-02.magpie-usage",
                "sealedBase64": sealed3,
                "expected": {"wrongPassphrase": True},
            },
        ],
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(fixtures, indent=1), encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")

    # Self-check: open our own seal the same way upstream would.
    key = hashlib.pbkdf2_hmac("sha256", PASSPHRASE.encode(), salt1, ITERATIONS, dklen=32)
    aad = f"magpie-data/1/pbkdf2-sha256/{ITERATIONS}/{salt1.hex()}".encode()
    import base64
    env = json.loads(base64.b64decode(sealed1))
    plain = AESGCM(key).decrypt(nonce1, base64.b64decode(env["data"]), aad)
    assert json.loads(gzip.decompress(plain))["day"] == "2026-03-31"
    print("self-check: seal roundtrip OK")


if __name__ == "__main__":
    sys.exit(main())
