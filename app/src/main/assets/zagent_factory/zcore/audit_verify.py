# zcore/audit_verify.py —— 独立审计 CLI（纯 stdlib，与主程序零依赖）
# 用途：脱离宿主进程，独立校验 audit-chain.jsonl 的完整性；
# 若提供 anchors 目录（App 层每 append 落一份），一并交叉核对。
#
# 用法：
# python3 audit_verify.py [chain.jsonl] # 默认 ~/mind/audit-chain.jsonl
# python3 audit_verify.py <chain> --anchors <dir> # 交叉核对
# python3 audit_verify.py <chain> --json # 机器可读输出
# python3 audit_verify.py --selftest # 内置自测
import argparse, hashlib, json, os, sys

def chain_hash(prev_hash, seq, ts, kind, payload, actor):
    base = "|".join([
        str(prev_hash), str(seq), str(ts), str(kind),
        json.dumps(payload or {}, ensure_ascii=False, sort_keys=True),
        str(actor)])
    return hashlib.sha256(base.encode("utf-8")).hexdigest()

def verify_chain(logp):
    """返回 (ok, errors, records, chain_head)"""
    errs = []
    recs = []
    prev = ""
    seq_expected = 1
    if not os.path.exists(logp):
        return True, [], [], ""
    try:
        with open(logp, "r", encoding="utf-8") as f:
            for lineno, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    o = json.loads(line)
                except Exception as e:
                    errs.append("line %d: JSON err %r" % (lineno, e))
                    continue
                seq = o.get("seq")
                if seq != seq_expected:
                    errs.append("line %d: seq expect %d got %s" % (lineno, seq_expected, seq))
                ph = o.get("prev_hash", "")
                if ph != prev:
                    errs.append("line %d: prev mismatch" % lineno)
                th_calc = chain_hash(ph, seq, o.get("ts"), o.get("kind"),
                    o.get("payload"), o.get("actor"))
                th_actual = o.get("this_hash", "")
                if th_calc != th_actual:
                    errs.append("line %d: this hash mismatch" % lineno)
                prev = th_actual
                seq_expected = seq + 1
                recs.append(o)
    except Exception as e:
        errs.append("read err: %r" % e)
    return len(errs) == 0, errs, recs, prev

def verify_anchors(recs, anchor_dir):
    """anchors 目录下每个文件为一行一个 <seq>:<hash>。交叉核对。
    返回 (ok, errors, checked)."""
    errs = []
    checked = 0
    if not anchor_dir or not os.path.isdir(anchor_dir):
        return True, [], 0
    by_seq = {}
    for r in recs:
        try:
            by_seq[int(r.get("seq"))] = r.get("this_hash", "")
        except Exception:
            pass
    for fn in sorted(os.listdir(anchor_dir)):
        p = os.path.join(anchor_dir, fn)
        if not os.path.isfile(p):
            continue
        try:
            with open(p, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line or ":" not in line:
                        continue
                    seq_s, hash_s = line.split(":", 1)
                    try:
                        seq = int(seq_s)
                    except Exception:
                        continue
                    checked += 1
                    if seq not in by_seq:
                        errs.append("%s: anchor seq=%d 未出现在 chain" % (fn, seq))
                    elif by_seq[seq] != hash_s.strip():
                        errs.append("%s: anchor seq=%d hash 不一致" % (fn, seq))
        except Exception as e:
            errs.append("%s: %r" % (fn, e))
    return len(errs) == 0, errs, checked

def selftest():
    import tempfile
    d = tempfile.mkdtemp(prefix="audit_verify_")
    p = os.path.join(d, "chain.jsonl")
    prev = ""
    lines = []
    for i in (1, 2, 3):
        ts = 1000 + i
        kind = "test%d" % i
        payload = {"i": i}
        actor = "selftest"
        h = chain_hash(prev, i, ts, kind, payload, actor)
        lines.append(json.dumps({"seq": i, "ts": ts, "kind": kind,
            "payload": payload, "actor": actor,
            "prev_hash": prev, "this_hash": h}, sort_keys=True))
        prev = h
    with open(p, "w", encoding="utf-8") as f:
        f.write(chr(10).join(lines) + chr(10))
    ok, errs, recs, head = verify_chain(p)
    print("selftest good chain:", ok, "records:", len(recs))
    assert ok and len(recs) == 3
    # 篡改一条
    lines[1] = lines[1].replace('"i": 2', '"i": 99')
    with open(p, "w", encoding="utf-8") as f:
        f.write(chr(10).join(lines) + chr(10))
    ok2, errs2, _, _ = verify_chain(p)
    print("selftest tampered chain:", ok2, "errors:", len(errs2))
    assert not ok2 and errs2
    print("SELFTEST OK")

def main():
    ap = argparse.ArgumentParser(
        description="Z-Agent 独立审计 CLI（纯 stdlib）")
    ap.add_argument("chain", nargs="?", default=os.path.join(
        os.path.expanduser("~"), "mind", "audit-chain.jsonl"))
    ap.add_argument("--anchors", default="", help="锚点目录（可选）")
    ap.add_argument("--json", action="store_true", help="JSON 输出")
    ap.add_argument("--selftest", action="store_true", help="内置自测")
    args = ap.parse_args()
    if args.selftest:
        selftest()
        return 0
    ok, errs, recs, head = verify_chain(args.chain)
    aok, aerrs, achecked = (True, [], 0)
    if args.anchors:
        aok, aerrs, achecked = verify_anchors(recs, args.anchors)
    result = {
        "chain_path": args.chain,
        "records": len(recs),
        "chain_ok": ok,
        "chain_errors": errs,
        "chain_head": head,
        "anchors_dir": args.anchors or None,
        "anchors_checked": achecked,
        "anchors_ok": aok,
        "anchors_errors": aerrs,
        "overall_ok": ok and aok,
    }
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=1))
    else:
        print("chain: %s" % args.chain)
        print("records: %d" % len(recs))
        print("head: %s" % head)
        print("chain_ok: %s" % ("OK" if ok else "TAMPERED"))
        for e in errs:
            print(" E " + e)
        if args.anchors:
            print("anchors: %s (%d 条核对)" % (args.anchors, achecked))
            print("anchors_ok: %s" % ("OK" if aok else "MISMATCH"))
            for e in aerrs:
                print(" E " + e)
    return 0 if result["overall_ok"] else 1

if __name__ == "__main__":
    sys.exit(main())