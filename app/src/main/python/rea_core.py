# REA-Lite Core: pure-stdlib analysis orchestrator (embedded)
import os, sys, json, time, hashlib, zipfile, ast as _ast, re as _re
from pathlib import Path

ROOT = Path("/sdcard/Download/rea")
SESS = ROOT / "sessions"

def now_ms():
    return int(time.time() * 1000)

def jdump(p, obj):
    Path(p).parent.mkdir(parents=True, exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)

def jload(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)

def sha1b(data):
    if isinstance(data, str):
        data = data.encode("utf-8", "ignore")
    return hashlib.sha1(data).hexdigest()

def detect_type(path):
    p = Path(path)
    if not p.exists():
        return "missing"
    if p.is_dir():
        return "dir"
    ext = p.suffix.lower()
    if ext == ".apk":
        return "apk"
    if ext == ".dex":
        return "dex"
    if ext in (".js", ".mjs", ".cjs", ".ts"):
        return "js"
    if ext == ".py":
        return "py"
    if ext in (".zip", ".jar"):
        return "zip"
    try:
        head = p.open("rb").read(8)
        if head[:4] == b"\x7fELF":
            return "elf"
        if head[:2] == b"MZ":
            return "pe"
        if head[:4] in (b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe"):
            return "macho"
    except Exception:
        pass
    return "unknown"

def read_strings(data, minlen=6):
    try:
        text = data.decode("latin-1") if isinstance(data, bytes) else str(data)
    except Exception:
        text = str(data)
    out = []
    seen = set()
    rx = _re.compile("[" + chr(92) + "x20-" + chr(92) + "x7e]{" + str(minlen) + ",}")
    for m in rx.finditer(text):
        s = m.group(0)
        if s in seen:
            continue
        seen.add(s)
        out.append(s)
        if len(out) >= 3000:
            break
    return out

class Evidence:
    def __init__(self, sid):
        self.sid = sid
        self.dir = SESS / sid / "evidence"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.cf = SESS / sid / "counter.txt"
        n = 0
        if self.cf.exists():
            try:
                n = int(self.cf.read_text().strip())
            except Exception:
                n = 0
        self.n = n

    def next_id(self):
        self.n += 1
        self.cf.write_text(str(self.n))
        return "ev-%04d" % self.n

    def add(self, etype, backend, target, content, confidence="static",
            limitations=None, cmd="", source="", refs=None):
        eid = self.next_id()
        obj = {
            "id": eid,
            "type": etype,
            "backend": backend,
            "target": str(target),
            "source": source,
            "content": content[:200000],
            "confidence": confidence,
            "limitations": limitations or [],
            "cmd": cmd,
            "refs": refs or [],
            "ts": now_ms(),
        }
        jdump(self.dir / (eid + ".json"), obj)
        tl = SESS / self.sid / "timeline.md"
        with open(tl, "a", encoding="utf-8") as f:
            f.write("- [%s] %s | %s | conf=%s" % (eid, etype, backend, confidence) + chr(10))
        return eid

    def ls(self):
        return sorted([f for f in self.dir.iterdir()
            if f.name.startswith("ev-") and f.name.endswith(".json")])

    def show(self, eid):
        return jload(self.dir / (eid + ".json"))

def be_basic(target, ev):
    p = Path(target)
    st = p.stat()
    data = p.open("rb").read()
    head = data[:64]
    info = {
        "name": p.name,
        "size": st.st_size,
        "sha1": sha1b(data),
        "head_hex": head.hex(),
    }
    return ev.add("file-meta", "stdlib", target,
        json.dumps(info, ensure_ascii=False, indent=2),
        confidence="static",
        limitations=["仅文件头与哈希，无语义"])

def be_zip_list(target, ev):
    try:
        z = zipfile.ZipFile(target, "r")
        names = z.namelist()
        rows = []
        for n in names[:500]:
            try:
                info = z.getinfo(n)
                rows.append("%10d %s" % (info.file_size, n))
            except Exception:
                rows.append(" ? " + n)
        body = "条目数 %d" % len(names) + chr(10) + chr(10).join(rows)
        return ev.add("archive-list", "zipfile", target, body,
            confidence="static",
            limitations=["仅目录清单，无内容分析"])
    except Exception as e:
        return ev.add("archive-list", "zipfile", target,
            "zip 解析失败: %r" % e,
            confidence="static", limitations=["解析失败"])

def be_dex_strings(target, ev):
    p = Path(target)
    buf = p.open("rb").read()
    ss = read_strings(buf, 6)
    keep = []
    for s in ss:
        if len(s) > 4 and s[0] == "L" and s[-1] == ";":
            keep.append(s)
    body = "扫描 %d 条，类名形式 %d 条" % (len(ss), len(keep))
    body = body + chr(10) + chr(10).join(keep[:400])
    return ev.add("dex-strings", "stdlib", target, body,
        confidence="static",
        limitations=["从 DEX 原始字节扫描，可能有误命中"])

def be_apk(ev, target):
    ids = []
    ids.append(be_zip_list(target, ev))
    try:
        with zipfile.ZipFile(target, "r") as z:
            names = z.namelist()
            for cand in ("classes.dex", "classes2.dex", "classes3.dex"):
                if cand in names:
                    data = z.read(cand)
                    ss = read_strings(data, 8)
                    urls = [s for s in ss if "http" in s]
                    eid = ev.add("dex-urls", "stdlib", cand,
                        chr(10).join(urls[:200]),
                        confidence="static",
                        limitations=["从 DEX 原始字节扫描"],
                        source=str(target) + "!" + cand)
                    ids.append(eid)
    except Exception as e:
        ids.append(ev.add("apk-error", "stdlib", target, repr(e),
            confidence="static", limitations=["解析失败"]))
    return ids

def be_py(target, ev):
    p = Path(target)
    if p.is_dir():
        files = [f for f in p.rglob(chr(42) + ".py")][:50]
    else:
        files = [p]
    results = []
    for f in files:
        try:
            src = f.read_text(encoding="utf-8")
            tree = _ast.parse(src)
            fns = [n.name for n in _ast.walk(tree) if isinstance(n, _ast.FunctionDef)]
            cls = [n.name for n in _ast.walk(tree) if isinstance(n, _ast.ClassDef)]
            imp = []
            for n in _ast.walk(tree):
                if isinstance(n, _ast.Import):
                    imp += [a.name for a in n.names]
                elif isinstance(n, _ast.ImportFrom):
                    imp.append(n.module or "")
            results.append({"file": str(f), "functions": fns[:100],
                    "classes": cls[:100], "imports": sorted(set(imp))[:100]})
        except Exception as e:
            results.append({"file": str(f), "error": str(e)})
    return ev.add("py-ast", "stdlib", target,
        json.dumps(results, ensure_ascii=False, indent=2),
        confidence="static", limitations=["仅语法层"])

def be_js(target, ev):
    p = Path(target)
    if p.is_dir():
        files = [f for f in p.rglob(chr(42) + ".js")][:30]
    else:
        files = [p]
    rx_fn = re.compile("function" + chr(92) + "s+([A-Za-z0-9" + chr(92) + "]+)")
    rx_arr = re.compile("(const|let|var)" + chr(92) + "s+([A-Za-z0-9" + chr(92) + "]+)")
    rx_imp = _re.compile("from" + chr(92) + "s+['" + chr(34) + "]([^'" + chr(34) + "]+)")
    results = []
    for f in files:
        try:
            src = f.read_text(encoding="utf-8", errors="ignore")
            fns = sorted(set(rx_fn.findall(src)) | set(x[1] for x in rx_arr.findall(src)))[:200]
            im = sorted(set(rx_imp.findall(src)))[:100]
            results.append({"file": str(f), "functions": fns, "imports": im})
        except Exception as e:
            results.append({"file": str(f), "error": str(e)})
    return ev.add("js-ast", "regex", target,
        json.dumps(results, ensure_ascii=False, indent=2),
        confidence="inferred",
        limitations=["正则近似抽取，非真 AST"])

def be_strings_any(target, ev):
    p = Path(target)
    buf = p.open("rb").read()
    ss = read_strings(buf, 6)
    return ev.add("strings", "stdlib", target,
        chr(10).join(ss),
        confidence="static",
        limitations=["仅可见字符串，可能误命中"])

DISPATCH = {
    "apk": ["basic", "apk", "strings"],
    "dex": ["basic", "dex", "strings"],
    "zip": ["basic", "zip", "strings"],
    "py": ["basic", "py"],
    "js": ["basic", "js"],
    "elf": ["basic", "strings"],
    "pe": ["basic", "strings"],
    "macho": ["basic", "strings"],
    "dir": ["py", "js"],
    "unknown": ["basic", "strings"],
    "missing": [],
}

def new_session(target):
    sid = time.strftime("%Y%m%d-%H%M%S") + "-" + sha1b(str(target))[:6]
    d = SESS / sid
    d.mkdir(parents=True, exist_ok=True)
    meta = {"sid": sid, "target": str(target),
        "type": detect_type(target), "created": now_ms()}
    jdump(d / "meta.json", meta)
    return sid, meta

def list_sessions():
    out = []
    if not SESS.exists():
        return out
    for d in sorted(SESS.iterdir()):
        if not d.is_dir():
            continue
        mf = d / "meta.json"
        if mf.exists():
            try:
                out.append(jload(mf))
            except Exception:
                pass
    return out

def analyze(target):
    sid, meta = new_session(target)
    ev = Evidence(sid)
    ttype = meta["type"]
    ids = []
    ids.append(ev.add("session-open", "core", target, "type=%s" % ttype,
        confidence="static", limitations=[]))
    for s in DISPATCH.get(ttype, DISPATCH["unknown"]):
        try:
            if s == "basic":
                ids.append(be_basic(target, ev))
            elif s == "zip":
                ids.append(be_zip_list(target, ev))
            elif s == "dex":
                ids.append(be_dex_strings(target, ev))
            elif s == "apk":
                ids += be_apk(ev, target)
            elif s == "py":
                ids.append(be_py(target, ev))
            elif s == "js":
                ids.append(be_js(target, ev))
            elif s == "strings":
                ids.append(be_strings_any(target, ev))
        except Exception as e:
            ids.append(ev.add("backend-error", s, target, repr(e),
                confidence="static", limitations=["后端异常"]))
    return sid, ttype, ids

def diff_sessions(sid_a, sid_b):
    a = Evidence(sid_a)
    b = Evidence(sid_b)
    def sig(ev_dir):
        out = {}
        for f in sorted(ev_dir.iterdir()):
            if not (f.name.startswith("ev-") and f.name.endswith(".json")):
                continue
            try:
                o = jload(f)
                key = o.get("type", "") + "|" + o.get("backend", "")
                h = hashlib.md5(o.get("content", "").encode("utf-8", "ignore")).hexdigest()[:10]
                out.setdefault(key, []).append(h)
            except Exception:
                pass
        return out
    sa = sig(a.dir)
    sb = sig(b.dir)
    added, removed, changed = [], [], []
    for k in sb:
        if k not in sa:
            added.append(k)
        elif sorted(sa[k]) != sorted(sb[k]):
            changed.append(k)
    for k in sa:
        if k not in sb:
            removed.append(k)
    return {"a": sid_a, "b": sid_b, "added": added,
        "removed": removed, "changed": changed}

def report(sid):
    d = SESS / sid
    meta = jload(d / "meta.json") if (d / "meta.json").exists() else {}
    items = []
    ev_dir = d / "evidence"
    if ev_dir.exists():
        for f in sorted(ev_dir.iterdir()):
            if not (f.name.startswith("ev-") and f.name.endswith(".json")):
                continue
            try:
                items.append(jload(f))
            except Exception:
                pass
    fence = chr(96) + chr(96) + chr(96)
    lines = []
    lines.append("# REA-Lite 分析报告")
    lines.append("")
    lines.append("- 会话: " + sid)
    lines.append("- 目标: " + str(meta.get("target", "?")))
    lines.append("- 类型: " + str(meta.get("type", "?")))
    lines.append("- 证据数: " + str(len(items)))
    lines.append("")
    lines.append("## 证据清单")
    lines.append("")
    for it in items:
        lines.append("### [" + str(it.get("id")) + "] " + str(it.get("type")) + " (" + str(it.get("backend")) + ")")
        if it.get("source"):
            lines.append("- 来源: " + str(it.get("source")))
        lines.append("- 可信度: " + str(it.get("confidence")))
        if it.get("limitations"):
            lines.append("- 局限: " + "; ".join(it.get("limitations")))
        if it.get("refs"):
            lines.append("- 引用: " + ", ".join(it.get("refs")))
        c = it.get("content", "")
        if len(c) > 1200:
            c = c[:1200] + chr(10) + "...(截断 " + str(len(it.get("content", "")) - 1200) + " 字)"
        lines.append("")
        lines.append(fence)
        lines.append(c)
        lines.append(fence)
        lines.append("")
    return chr(10).join(lines)

def trace(sid, eid):
    ev = Evidence(sid)
    o = ev.show(eid)
    return json.dumps({
        "id": eid,
        "backend": o.get("backend"),
        "confidence": o.get("confidence"),
        "limitations": o.get("limitations"),
        "refs": o.get("refs"),
        "content_len": len(o.get("content", "")),
        "content": o.get("content", "")[:4000],
    }, ensure_ascii=False, indent=2)

def graph(sid):
    ev = Evidence(sid)
    edges = []
    nodes = {}
    for f in ev.ls():
        try:
            o = jload(f)
            nid = o["id"]
            nodes[nid] = str(o.get("type")) + "/" + str(o.get("backend"))
            for r in o.get("refs", []):
                edges.append((r, nid))
        except Exception:
                pass
    lines = ["graph TD"]
    for nid, label in nodes.items():
        lines.append(" " + nid + "[" + nid + " " + label + "]")
    for a, b in edges:
        lines.append(" " + a + " --> " + b)
    return chr(10).join(lines)

def latest_session():
    ss = list_sessions()
    return ss[-1]["sid"] if ss else None

HELP = (
    "REA-Lite 内置分析器" + chr(10) +
    "用法:" + chr(10) +
    " /rea open <路径> 识别目标类型" + chr(10) +
    " /rea run <路径> 跑分析，返回会话 id" + chr(10) +
    " /rea report [sid] 生成人读报告" + chr(10) +
    " /rea evidence [sid] 列证据" + chr(10) +
    " /rea show <sid> <ev-id> 查单条证据" + chr(10) +
    " /rea trace <sid> <ev-id> 反查" + chr(10) +
    " /rea diff <sidA> <sidB> 两会话对比" + chr(10) +
    " /rea graph <sid> 证据关系图 Mermaid" + chr(10) +
    " /rea sessions 列会话" + chr(10) +
    " /rea mcp [--port N] 起 MCP 服务" + chr(10) +
    " /rea help 本帮助")

def cli(argv):
    if not argv:
        return HELP
    c = argv[0].lower()
    if c == "mcp":
        import rea_mcp
        return rea_mcp.cli_mcp(argv)
    if c == "mcp-stop":
        import rea_mcp
        return rea_mcp.cli_stop()
    if c == "mcp-status":
        import rea_mcp
        return rea_mcp.cli_status()
    if c == "help":
        return HELP
    if c == "open":
        if len(argv) < 2:
            return "用法: /rea open <路径>"
        return json.dumps({"target": argv[1], "type": detect_type(argv[1])},
            ensure_ascii=False, indent=2)
    if c == "run":
        if len(argv) < 2:
            return "用法: /rea run <路径>"
        sid, ttype, ids = analyze(argv[1])
        return "会话: " + sid + chr(10) + "类型: " + ttype + chr(10) + "证据: " + ", ".join(ids)
    if c == "report":
        sid = argv[1] if len(argv) > 1 else latest_session()
        if not sid:
            return "无会话"
        return report(sid)
    if c == "evidence":
        sid = argv[1] if len(argv) > 1 else latest_session()
        if not sid:
            return "无会话"
        ev = Evidence(sid)
        return chr(10).join([f.name for f in ev.ls()])
    if c == "show":
        if len(argv) < 3:
            return "用法: /rea show <sid> <ev-id>"
        ev = Evidence(argv[1])
        return json.dumps(ev.show(argv[2]), ensure_ascii=False, indent=2)
    if c == "trace":
        if len(argv) < 3:
            return "用法: /rea trace <sid> <ev-id>"
        return trace(argv[1], argv[2])
    if c == "diff":
        if len(argv) < 3:
            return "用法: /rea diff <sidA> <sidB>"
        return json.dumps(diff_sessions(argv[1], argv[2]), ensure_ascii=False, indent=2)
    if c == "graph":
        sid = argv[1] if len(argv) > 1 else latest_session()
        if not sid:
            return "无会话"
        return graph(sid)
    if c == "sessions":
        return json.dumps(list_sessions(), ensure_ascii=False, indent=2)
    return "未知子命令: " + c + chr(10) + HELP

def handle_command(u):
    parts = u.split()
    argv = parts[1:] if parts and parts[0].lower() == "/rea" else parts
    try:
        return cli(argv)
    except Exception as e:
        return "[REA 异常] " + repr(e)
