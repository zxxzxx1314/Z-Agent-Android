# -- coding: utf-8 --
"""plugin.call - invoke a plugin under home/plugins.

Thin shell: subprocess isolation, timeout and termux-* short-circuit
all live in host run_plugin.
"""


def run(args, ctx):
    pid = str(args.get("id") or "").strip()
    if not pid:
        return {"ok": False, "err": "id is required"}
    host = (ctx or {}).get("host") or {}
    fn = host.get("run_plugin")
    if fn is None:
        return {"ok": False, "err": "host.run_plugin unavailable"}
    text = str(fn(pid, str(args.get("arg") or "")) or "")
    bad = text.startswith(("PLUGIN_", "插件 "))
    return {"ok": not bad, "data": text, "err": (text if bad else None)}
