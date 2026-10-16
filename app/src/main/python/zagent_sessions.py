# coding: utf-8
# zagent_sessions -- multi-session manager
import json
import os
import time

def _d(home):
    d = os.path.join(home, "sessions")
    os.makedirs(d, exist_ok=True)
    return d

def _ip(home):
    return os.path.join(_d(home), "index.json")

def _sp(home, sid):
    return os.path.join(_d(home), sid + ".json")

def _rd(p):
    fh = open(p, encoding="utf-8")
    try:
        return json.load(fh)
    finally:
        fh.close()

def _wr(p, o):
    t = p + ".tmp"
    fh = open(t, "w", encoding="utf-8")
    try:
        json.dump(o, fh, ensure_ascii=False, indent=1)
    finally:
        fh.close()
    os.replace(t, p)

def load_index(home):
    p = _ip(home)
    if os.path.exists(p):
        try:
            d = _rd(p)
            if isinstance(d, dict) and isinstance(d.get("items"), list):
                return d
        except Exception:
            pass
    return {"current": None, "items": []}

def save_index(home, idx):
    _wr(_ip(home), idx)

def load_msgs(home, sid):
    p = _sp(home, sid)
    if not os.path.exists(p):
        return []
    try:
        d = _rd(p)
        if isinstance(d, list):
            return [m for m in d if isinstance(m, dict) and m.get("role") in ("user", "assistant")]
    except Exception:
        pass
    return []

def save_msgs(home, sid, msgs):
    c = [{"role": m.get("role"), "content": m.get("content", "")} for m in msgs if isinstance(m, dict) and m.get("role") in ("user", "assistant")]
    _wr(_sp(home, sid), c)

def _tt(msgs):
    for m in msgs:
        if m.get("role") == "user":
            t = (m.get("content") or "").strip().replace(chr(10), " ")
            if t:
                return t[:24]
    return "(empty)"

def list_sessions(home):
    idx = load_index(home)
    out = []
    for it in idx.get("items", []):
        sid = it.get("sid")
        if not sid:
            continue
        msgs = load_msgs(home, sid)
        out.append({"sid": sid, "title": it.get("title") or _tt(msgs), "mtime": it.get("mtime") or 0, "count": len(msgs), "current": sid == idx.get("current")})
    out.sort(key=lambda x: -(x.get("mtime") or 0))
    return out

def create(home, msgs=None):
    idx = load_index(home)
    sid = "s" + str(int(time.time() * 1000))
    msgs = msgs or []
    idx["items"].insert(0, {"sid": sid, "title": _tt(msgs), "mtime": int(time.time())})
    idx["current"] = sid
    save_index(home, idx)
    save_msgs(home, sid, msgs)
    return sid

def ensure(home, msgs=None):
    idx = load_index(home)
    if not idx.get("current"):
        return create(home, msgs)
    return idx["current"]

def touch(home, sid, msgs):
    idx = load_index(home)
    save_msgs(home, sid, msgs)
    hit = False
    for it in idx.get("items", []):
        if it.get("sid") == sid:
            it["mtime"] = int(time.time())
            if not it.get("title"):
                it["title"] = _tt(msgs)
            hit = True
            break
    if not hit:
        idx.setdefault("items", []).insert(0, {"sid": sid, "title": _tt(msgs), "mtime": int(time.time())})
    save_index(home, idx)

def remove(home, sid):
    idx = load_index(home)
    idx["items"] = [x for x in idx.get("items", []) if x.get("sid") != sid]
    if idx.get("current") == sid:
        idx["current"] = idx["items"][0]["sid"] if idx["items"] else None
    save_index(home, idx)
    p = _sp(home, sid)
    if os.path.exists(p):
        os.remove(p)
    return idx.get("current")

def export_md(home, sid, out_dir):
    msgs = load_msgs(home, sid)
    idx = load_index(home)
    title = "(untitled)"
    for it in idx.get("items", []):
        if it.get("sid") == sid:
            title = it.get("title") or title
            break
    lines = ["# " + title, ""]
    for m in msgs:
        who = "user" if m.get("role") == "user" else "AI"
        lines.append("## " + who)
        lines.append("")
        lines.append(m.get("content") or "")
        lines.append("")
    os.makedirs(out_dir, exist_ok=True)
    fn = os.path.join(out_dir, "chat-" + sid + ".md")
    fh = open(fn, "w", encoding="utf-8")
    try:
        fh.write(chr(10).join(lines))
    finally:
        fh.close()
    return fn