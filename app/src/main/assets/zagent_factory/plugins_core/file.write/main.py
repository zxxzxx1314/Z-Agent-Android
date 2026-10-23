# -- coding: utf-8 --
"""file.write - 覆盖写入或追加文本文件。

单次写入上限 512 KiB（超出直接拒绝，不做截断，避免悄悄丢数据）。
覆盖模式走临时文件 + os.replace，保证原子性。
"""
import os
import tempfile

MAX_BYTES = 512 * 1024


def run(args, ctx):
    path = os.path.expanduser(str(args.get("path") or "").strip())
    content = args.get("content")
    append = bool(args.get("append"))
    if not path:
        return {"ok": False, "outcome": "not_submitted", "err": "path is required"}
    if content is None:
        return {"ok": False, "outcome": "not_submitted", "err": "content is required"}
    content = str(content)
    raw = content.encode("utf-8")
    if len(raw) > MAX_BYTES:
        return {"ok": False, "outcome": "not_submitted",
            "err": "content too large: %d > %d bytes" % (len(raw), MAX_BYTES)}
    try:
        parent = os.path.dirname(os.path.abspath(path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        if append:
            with open(path, "a", encoding="utf-8", newline=chr(10)) as f:
                f.write(content)
        else:
            fd, t = tempfile.mkstemp(prefix=".act-", dir=parent or ".")
            try:
                with os.fdopen(fd, "w", encoding="utf-8", newline=chr(10)) as f:
                    f.write(content)
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(t, path)
            finally:
                if os.path.exists(t):
                    os.unlink(t)
    except Exception as e:
        return {"ok": False, "outcome": "unknown", "err": repr(e)}
    return {"ok": True, "outcome": "ok", "data": "wrote %d bytes -> %s" % (len(raw), path)}
