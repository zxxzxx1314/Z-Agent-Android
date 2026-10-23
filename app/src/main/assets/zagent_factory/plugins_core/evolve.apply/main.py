# -- coding: utf-8 --
"""evolve.apply - validate and replace the running genome.

level=3 means the pipeline never prompts for us, so this action must ask
the user itself before touching the genome.
"""


def run(args, ctx):
    reason = str(args.get("reason") or "").strip()
    source = args.get("source") or ""
    if not reason or not str(source).strip():
        return {"ok": False, "err": "reason and source are required"}
    host = (ctx or {}).get("host") or {}
    ask = host.get("confirm_evolve")
    if ask is not None and not ask(reason):
        return {"ok": False, "err": "DENIED: user refused evolution"}
    fn = host.get("apply_evolution")
    if fn is None:
        return {"ok": False, "err": "host.apply_evolution unavailable"}
    ok, msg = fn(str(source), reason)
    return {"ok": bool(ok), "data": str(msg), "err": (None if ok else str(msg))}
