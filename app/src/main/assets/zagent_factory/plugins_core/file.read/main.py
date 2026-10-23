# -- coding: utf-8 --
"""file.read - 按显式字节预算读取文本文件。

默认单次 16 KiB；offset 只落在完整 UTF-8 字符边界；
读取结果带 next_offset / remaining，方便调用方分页。
"""
import os

DEFAULT_LIMIT = 16384
MAX_LIMIT = 262144


def _align(raw, target):
    """把 target 回退到 raw 中最近的 UTF-8 起始字节。raw 从头开始。"""
    if target <= 0:
        return 0
    if target > len(raw):
        target = len(raw)
    i = 0
    while i < target:
        b = raw[i]
        if (b & 0x80) == 0:
            step = 1
        elif (b & 0xE0) == 0xC0:
            step = 2
        elif (b & 0xF0) == 0xE0:
            step = 3
        elif (b & 0xF8) == 0xF0:
            step = 4
        else:
            step = 1
        if i + step > target:
            return i
        i += step
    return target


def run(args, ctx):
    path = os.path.expanduser(str(args.get("path") or "").strip())
    try:
        limit = int(args.get("limit") or DEFAULT_LIMIT)
    except Exception:
        limit = DEFAULT_LIMIT
    limit = max(1, min(MAX_LIMIT, limit))
    try:
        offset = int(args.get("offset") or 0)
    except Exception:
        offset = 0
    offset = max(0, offset)
    if not path:
        return {"ok": False, "outcome": "not_submitted", "err": "path is required"}
    if not os.path.exists(path):
        return {"ok": False, "outcome": "not_submitted", "err": "not found: " + path}
    if os.path.isdir(path):
        return {"ok": False, "outcome": "not_submitted", "err": "is a directory: " + path}
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            if offset > 0:
                f.seek(0)
                prefix = f.read(offset + 4)
                aligned = _align(prefix, offset)
                f.seek(aligned)
                offset = aligned
            raw = f.read(limit + 4)
    except Exception as e:
        return {"ok": False, "outcome": "unknown", "err": repr(e)}
    truncated = len(raw) > limit
    body_end = _align(raw, limit)
    body = raw[:body_end]
    text = body.decode("utf-8", "replace")
    next_offset = (offset + len(body)) if truncated else None
    remaining = max(0, size - offset - len(body))
    return {"ok": True, "outcome": "ok", "data": text,
        "meta": {"size": size, "offset": offset, "limit": limit,
            "bytes_read": len(body), "truncated": truncated,
            "next_offset": next_offset, "remaining": remaining}}
