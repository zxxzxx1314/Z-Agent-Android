"""zcore.provider - Provider protocol adapter (task 22)."""
import json

def detect_provider(url):
	if not url:
		return "openai"
	u = url.lower()
	if "anthropic" in u:
		return "anthropic"
	if "/responses" in u:
		return "responses"
	return "openai"

def merge_system_chat(msgs):
	if not msgs:
		return msgs
	systems = []
	rest = []
	for m in msgs:
		if not isinstance(m, dict):
			continue
		if m.get("role") == "system":
			c = m.get("content") or ""
			if c:
				systems.append(c)
		else:
			rest.append(m)
	if not systems:
		return rest if rest else msgs
	merged = {"role": "system", "content": (chr(10) + chr(10)).join(systems)}
	return [merged] + rest

def to_anthropic_request(base_url, msgs, model, temperature=0.3, max_tokens=4096):
	msgs = merge_system_chat(msgs)
	url = base_url or "https://api.anthropic.com/v1/messages"
	if "/messages" not in url.lower():
		if url.rstrip("/").endswith("/chat/completions"):
			url = url[: -len("/chat/completions")] + "/v1/messages"
	sys_msg = None
	conv = []
	for m in msgs:
		if not isinstance(m, dict):
			continue
		r = m.get("role")
		c = m.get("content") or ""
		if not c:
			continue
		if r == "system":
			sys_msg = c
		elif r in ("user", "assistant"):
			conv.append({"role": r, "content": c})
	body = {
		"model": model,
		"max_tokens": max(1, int(max_tokens or 4096)),
		"messages": conv,
	}
	if sys_msg:
		body["system"] = sys_msg
	if temperature is not None:
		body["temperature"] = float(temperature)
	headers = {
		"x-api-key": _chat_key(),
		"anthropic-version": "2023-06-01",
		"Content-Type": "application/json",
	}
	return url, headers, body

def to_responses_request(base_url, msgs, model, temperature=0.3):
	msgs = merge_system_chat(msgs)
	url = base_url or "https://api.openai.com/v1/responses"
	if "/responses" not in url.lower():
		if url.rstrip("/").endswith("/chat/completions"):
			url = url[: -len("/chat/completions")] + "/v1/responses"
	sys_msg = None
	items = []
	for m in msgs:
		if not isinstance(m, dict):
			continue
		r = m.get("role")
		c = m.get("content") or ""
		if not c:
			continue
		if r == "system":
			sys_msg = c
		elif r == "user":
			items.append({"role": "user", "content": [{"type": "input_text", "text": c}]})
		elif r == "assistant":
			items.append({"role": "assistant", "content": [{"type": "output_text", "text": c}]})
	body = {"model": model, "input": items}
	if sys_msg:
		body["instructions"] = sys_msg
	if temperature is not None:
		body["temperature"] = float(temperature)
	headers = {
		"Authorization": "Bearer " + _chat_key(),
		"Content-Type": "application/json",
	}
	return url, headers, body
