# REA MCP server: JSON-RPC 2.0 over HTTP, pure stdlib
import json, threading, http.server
from pathlib import Path

DEFAULT_PORT = 8765
PORT_FILE = Path("/sdcard/Download/rea/.mcp_port")
STATE = {"srv": None, "port": 0}

def _c():
	import rea_core
	return rea_core

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
	]

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
	raise ValueError("unknown tool: " + str(name))

def _rpc(obj):
	rid = obj.get("id")
	m = obj.get("method", "")
	if m == "initialize":
		return {"jsonrpc": "2.0", "id": rid, "result": {
			"protocolVersion": "2024-11-05",
			"capabilities": {"tools": {"listChanged": False}},
			"serverInfo": {"name": "rea-lite", "version": "1.0"}}}
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
			"tools": [t["name"] for t in tools()]}

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
