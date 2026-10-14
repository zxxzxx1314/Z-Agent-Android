import json, threading, http.server
from pathlib import Path

DEFAULT_PORT = 8765
PORT_FILE = Path("/sdcard/Download/rea/.mcp_port")
STATE = {"srv": None, "port": 0}

def _c():
    try:
        import rea_core
        return rea_core
    except ImportError:
        import rea
        return rea

def tools():
    def t(n, d, p=None, r=None):
        return {"name": n, "description": d,
                "inputSchema": {"type": "object", "properties": p or {}, "required": r or []}}
    return [
        t("rea_open", "identify target type", {"target": {"type": "string"}}, ["target"]),
        t("rea_run", "run full analysis", {"target": {"type": "string"}}, ["target"]),
        t("rea_sessions", "list all sessions"),
        t("rea_evidence", "list evidence in a session", {"sid": {"type": "string"}}),
        t("rea_show", "show one evidence", {"sid": {"type": "string"}, "eid": {"type": "string"}}, ["sid", "eid"]),
        t("rea_trace", "trace evidence to raw command", {"sid": {"type": "string"}, "eid": {"type": "string"}}, ["sid", "eid"]),
        t("rea_diff", "diff two sessions", {"sid_a": {"type": "string"}, "sid_b": {"type": "string"}}, ["sid_a", "sid_b"]),
        t("rea_report", "generate markdown report", {"sid": {"type": "string"}}),
        t("rea_graph", "evidence Mermaid graph", {"sid": {"type": "string"}}),
        t("rea_frida_status", "frida status + gate"),
        t("rea_frida_gate", "open/close frida gate", {"enable": {"type": "boolean"}}, ["enable"]),
        t("rea_frida_run", "run frida (requires gate open)", {"mode": {"type": "string"}, "target": {"type": "string"}, "script": {"type": "string"}, "timeout": {"type": "integer"}}, ["mode"]),
    ]

def resources():
    def r(uri, name, desc, mime="application/json"):
        return {"uri": uri, "name": name, "description": desc, "mimeType": mime}
    return [
        r("rea://backends", "Backends", "已注册后端的清单与可用性"),
        r("rea://sessions", "Sessions", "全部会话（meta 列表）"),
        r("rea://sessions/{sid}", "Session meta", "单会话元信息"),
        r("rea://sessions/{sid}/evidence", "Evidence list", "单会话证据 id 列表"),
        r("rea://sessions/{sid}/evidence/{eid}", "Evidence", "单条证据 JSON"),
        r("rea://sessions/{sid}/report", "Report", "该会话的 markdown 报告", mime="text/markdown"),
        r("rea://latest/report", "Latest report", "最近会话的 markdown 报告", mime="text/markdown"),
    ]

def read_resource(uri):
    c = _c()
    if uri == "rea://backends":
        try:
            import sys as _sys
            from pathlib import Path as _P
            p = _P("/sdcard/Download/rea")
            if str(p) not in _sys.path:
                _sys.path.insert(0, str(p))
            from backends import list_backends, get as _get
            out = []
            for n in list_backends():
                b = _get(n)
                out.append({"name": n, "available": bool(b.available()),
                    "hint": b.hint()})
            return json.dumps(out, ensure_ascii=False, indent=2), "application/json"
        except Exception as e:
            return json.dumps({"error": repr(e)}), "application/json"
    if uri == "rea://sessions":
        return json.dumps(c.list_sessions(), ensure_ascii=False, indent=2), "application/json"
    if uri == "rea://latest/report":
        sid = c.latest_session()
        if not sid:
            return "(无会话)", "text/markdown"
        return c.report(sid), "text/markdown"
    prefix = "rea://sessions/"
    if uri.startswith(prefix):
        rest = uri[len(prefix):]
        parts = rest.split("/")
        if len(parts) == 1 and parts[0]:
            sid = parts[0]
            d = c.SESS / sid / "meta.json"
            if d.exists():
                return json.dumps(c.jload(d), ensure_ascii=False, indent=2), "application/json"
            return json.dumps({"error": "no such session: " + sid}), "application/json"
        if len(parts) >= 2 and parts[0] and parts[1] == "evidence":
            sid = parts[0]
            if len(parts) == 2:
                ev = c.Evidence(sid)
                return json.dumps([f.name for f in ev.ls()], ensure_ascii=False, indent=2), "application/json"
            if len(parts) == 3 and parts[2]:
                eid = parts[2]
                try:
                    ev = c.Evidence(sid)
                    obj = ev.show(eid)
                    return json.dumps(obj, ensure_ascii=False, indent=2), "application/json"
                except Exception as e:
                    return json.dumps({"error": repr(e)}), "application/json"
        if len(parts) == 2 and parts[0] and parts[1] == "report":
            sid = parts[0]
            return c.report(sid), "text/markdown"
    raise ValueError("unknown resource: " + str(uri))

def prompts():
    def p(n, d, args=None):
        return {"name": n, "description": d, "arguments": args or []}
    return [
        p("rea-analyze", "引导对目标做 REA 三步分析（open / run / report）",
                [{"name": "target", "description": "目标路径（apk/dex/js/py/elf/zip/dir）", "required": True}]),
        p("rea-triage-apk", "APK 快速分级：抽 URL / 类名 / 关键字符串",
                [{"name": "target", "description": "APK 路径", "required": True}]),
        p("rea-diff-explain", "解释两个会话的差异并指出可核实点",
                [{"name": "sid_a", "description": "会话 A", "required": True},
                 {"name": "sid_b", "description": "会话 B", "required": True}]),
    ]

def get_prompt(name, args):
    args = args or {}
    if name == "rea-analyze":
        target = args.get("target", "target")
        text = (
            "请使用 REA-Lite 对目标做一次完整分析：" + chr(10) +
            "1) 调用 rea_open 识别类型，确认是 " + str(target) + "；" + chr(10) +
            "2) 调用 rea_run 执行分析，记录返回的 sid 与证据 id；" + chr(10) +
            "3) 对关键证据用 rea_trace 反查原始命令与输出；" + chr(10) +
            "4) 用 rea_report 生成报告，并给出你的结论（每条结论必须引用 ev-id）。" + chr(10) +
            "禁忌：不要臆造未出现在证据中的信息。"
        )
        return {"description": "REA 三步分析", "messages": [
            {"role": "user", "content": {"type": "text", "text": text}}]}
    if name == "rea-triage-apk":
        target = args.get("target", "apk")
        text = (
            "对 APK 做快速分级：" + chr(10) +
            "1) rea_run " + str(target) + " 拿到全部证据；" + chr(10) +
            "2) 关注 dex-urls / dex-strings / archive-list；" + chr(10) +
            "3) 归纳：网络域名、疑似 SDK、敏感权限字符串、加固迹象；" + chr(10) +
            "4) 每条结论后紧跟引用 [ev-XXXX]。"
        )
        return {"description": "APK 快速分级", "messages": [
            {"role": "user", "content": {"type": "text", "text": text}}]}
    if name == "rea-diff-explain":
        a = args.get("sid_a", "A")
        b = args.get("sid_b", "B")
        text = (
            "调用 rea_diff 对比 " + str(a) + " 与 " + str(b) + "，然后：" + chr(10) +
            "1) 对 added/removed/changed 每项解释含义；" + chr(10) +
            "2) 指出哪些变化最可能对应真实代码/资源改动；" + chr(10) +
            "3) 给出下一步可核实的取证动作（具体命令）。"
        )
        return {"description": "差异解释", "messages": [
            {"role": "user", "content": {"type": "text", "text": text}}]}
    raise ValueError("unknown prompt: " + str(name))

def _frida():
    import sys as _s
    if "/sdcard/Download/rea" not in _s.path:
        _s.path.insert(0, "/sdcard/Download/rea")
    import backends.frida_backend as fb
    return fb

def call(name, args):
    args = args or {}
    c = _c()
    if name == "rea_open":
        t = args.get("target", "")
        return {"target": t, "type": c.detect_type(t)}
    if name == "rea_run":
        sid, ttype, ids = c.analyze(args.get("target", ""))
        return {"sid": sid, "type": ttype, "evidence": ids}
    if name == "rea_sessions":
        return c.list_sessions()
    if name == "rea_evidence":
        sid = args.get("sid") or c.latest_session()
        if not sid:
            return []
        return [f.name for f in c.Evidence(sid).ls()]
    if name == "rea_show":
        return c.Evidence(args["sid"]).show(args["eid"])
    if name == "rea_trace":
        return c.trace(args["sid"], args["eid"])
    if name == "rea_diff":
        return c.diff_sessions(args["sid_a"], args["sid_b"])
    if name == "rea_report":
        return c.report(args.get("sid") or c.latest_session())
    if name == "rea_graph":
        return c.graph(args.get("sid") or c.latest_session())
    if name == "rea_frida_status":
        return _frida().frida_status()
    if name == "rea_frida_gate":
        return {"ok": True, "result": _frida().gate_set(bool(args.get("enable", False)))}
    if name == "rea_frida_run":
        return _frida().frida_run(
            args.get("mode", "ps"),
            args.get("target", ""),
            args.get("script"),
            int(args.get("timeout", 60)))
    raise ValueError("unknown tool: " + str(name))

def _rpc(obj):
    rid = obj.get("id")
    m = obj.get("method", "")
    if m == "initialize":
        return {"jsonrpc": "2.0", "id": rid, "result": {
            "protocolVersion": "2024-11-05",
            "capabilities": {
                "tools": {"listChanged": False},
                "resources": {"subscribe": False, "listChanged": False},
                "prompts": {"listChanged": False}},
            "serverInfo": {"name": "rea-lite", "version": "1.1"}}}
    if m == "tools/list":
        return {"jsonrpc": "2.0", "id": rid, "result": {"tools": tools()}}
    if m == "tools/call":
        p = obj.get("params") or {}
        try:
            r = call(p.get("name", ""), p.get("arguments"))
            return {"jsonrpc": "2.0", "id": rid, "result": {
                "content": [{"type": "text", "text": json.dumps(r, ensure_ascii=False, indent=2)}],
                "isError": False}}
        except Exception as e:
            return {"jsonrpc": "2.0", "id": rid, "result": {
                "content": [{"type": "text", "text": "[REA MCP error] " + repr(e)}],
                "isError": True}}
    if m == "resources/list":
        return {"jsonrpc": "2.0", "id": rid, "result": {"resources": resources()}}
    if m == "resources/read":
        p = obj.get("params") or {}
        uri = p.get("uri", "")
        try:
            text, mime = read_resource(uri)
            return {"jsonrpc": "2.0", "id": rid, "result": {"contents": [
                {"uri": uri, "mimeType": mime, "text": text}]}}
        except Exception as e:
            return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": repr(e)}}
    if m == "prompts/list":
        return {"jsonrpc": "2.0", "id": rid, "result": {"prompts": prompts()}}
    if m == "prompts/get":
        p = obj.get("params") or {}
        try:
            r = get_prompt(p.get("name", ""), p.get("arguments"))
            return {"jsonrpc": "2.0", "id": rid, "result": r}
        except Exception as e:
            return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": repr(e)}}
    if m == "notifications/initialized":
        return None
    return {"jsonrpc": "2.0", "id": rid,
            "error": {"code": -32601, "message": "method not found: " + str(m)}}

class _Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, a=None, b=None, c=None, d=None):
        return
    def _send(self, code, body):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "")
        self.end_headers()
        self.wfile.write(data)
    def do_GET(self):
        if self.path.startswith("/health"):
            self._send(200, json.dumps({"ok": True, "port": STATE["port"]}))
        elif self.path.startswith("/tools"):
            self._send(200, json.dumps({"tools": tools()}, ensure_ascii=False))
        elif self.path.startswith("/resources"):
            self._send(200, json.dumps({"resources": resources()}, ensure_ascii=False))
        elif self.path.startswith("/prompts"):
            self._send(200, json.dumps({"prompts": prompts()}, ensure_ascii=False))
        else:
            self._send(200, json.dumps({"ok": True, "server": "rea-lite"}))
    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(n).decode("utf-8") if n > 0 else ""
            obj = json.loads(raw) if raw else {}
        except Exception as e:
            self._send(400, json.dumps({"error": "bad request: " + repr(e)}))
            return
        if isinstance(obj, list):
            out = []
            for one in obj:
                r = _rpc(one)
                if r is not None:
                    out.append(r)
            self._send(200, json.dumps(out, ensure_ascii=False))
            return
        r = _rpc(obj)
        if r is None:
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            self._send(200, json.dumps(r, ensure_ascii=False))

def serve(port=None):
    port = int(port or DEFAULT_PORT)
    if STATE["srv"] is not None:
        return STATE["port"]
    try:
        srv = http.server.ThreadingHTTPServer(("127.0.0.1", port), _Handler)
    except Exception:
        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    actual = srv.server_address[1]
    STATE["srv"] = srv
    STATE["port"] = actual
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        PORT_FILE.write_text(str(actual), encoding="utf-8")
    except Exception:
        pass
    return actual

def stop():
    srv = STATE["srv"]
    if srv is None:
        return False
    try:
        srv.shutdown()
        srv.server_close()
    except Exception:
        pass
    STATE["srv"] = None
    STATE["port"] = 0
    return True

def status():
    srv = STATE["srv"]
    if srv is None:
        last = None
        try:
            last = int(PORT_FILE.read_text(encoding="utf-8").strip())
        except Exception:
            last = None
        return {"running": False, "last_port": last}
    return {"running": True, "port": STATE["port"],
            "tools": [t["name"] for t in tools()],
            "resources": [r["uri"] for r in resources()],
            "prompts": [p["name"] for p in prompts()]}

def cli_mcp(argv):
    p = None
    if "--port" in argv:
        try:
            i = argv.index("--port")
            p = int(argv[i + 1])
        except Exception:
            p = None
    port = serve(p)
    return "REA MCP 已启动, 端口 " + str(port) + ", 工具 " + str(len(tools())) + " 个"

def cli_stop():
    return "MCP 已停止" if stop() else "MCP 未运行"

def cli_status():
    return json.dumps(status(), ensure_ascii=False, indent=2)