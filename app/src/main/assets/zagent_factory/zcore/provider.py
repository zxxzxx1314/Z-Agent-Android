"""zcore.provider - Provider protocol adapter (task 22).

Entry points: detect_provider / merge_system_chat /
to_anthropic_request / to_responses_request / extract_text.

Design boundary:
 1. This agent uses plain-text protocol (not OpenAI tools).
 So this module only reshapes request/response bodies.
 2. Opaque output items replay: reasoning blocks in Anthropic /
 Responses theoretically need to be replayed next round to
 continue reasoning state. This agent uses non-streaming plain
 text chat, no cross-round opaque replay. from_responses /
 from_anthropic only extract text and reasoning for UI display,
 never written back to messages. If tool calling is added later,
 replay logic goes into _pending_opaque (in-memory dict, cleared
 after run), never persisted into transcript.
"""

PROVIDER_CHAT = "chat"
PROVIDER_ANTHROPIC = "anthropic"
PROVIDER_RESPONSES = "responses"


def detect_provider(base_url):
    b = (base_url or "").lower()
    if not b:
        return PROVIDER_CHAT
    if "anthropic" in b or "/v1/messages" in b:
        return PROVIDER_ANTHROPIC
    if "/v1/responses" in b or b.rstrip("/").endswith("/responses"):
        return PROVIDER_RESPONSES
    return PROVIDER_CHAT


def merge_system_chat(msgs):
    """Merge multiple system messages into one head entry.
    
    chat template accepts only one system; some servers 400 on
    multiple. Merge order follows original messages, empty skipped.
    If no system at all, return unchanged.
    """
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
    """Build Anthropic Messages request: (url, headers, body).
    
    system is lifted to top-level; Anthropic does not accept system
    role inside messages.
    """
    url = base_url or "https://api.anthropic.com/v1/messages"
    if "/messages" not in url.lower():
        if url.rstrip("/").endswith("/chat/completions"):
            url = url[: -len("/chat/completions")] + "/v1/messages"
    sys_parts = []
    conv = []
    for m in msgs:
        if not isinstance(m, dict):
            continue
        r = m.get("role")
        c = m.get("content") or ""
        if not c:
            continue
        if r == "system":
            sys_parts.append(c)
        elif r in ("user", "assistant"):
            conv.append({"role": r, "content": c})
    body = {
        "model": model,
        "max_tokens": max(1, int(max_tokens or 4096)),
        "messages": conv,
    }
    if sys_parts:
        body["system"] = (chr(10) + chr(10)).join(sys_parts)
    if temperature is not None:
        body["temperature"] = float(temperature)
    headers = {
        "x-api-key": _chat_key(),
        "anthropic-version": "2023-06-01",
        "Content-Type": "application/json",
    }
    return url, headers, body


def _chat_key():
    try:
        import zagent
        return str(getattr(zagent, "API_KEY", "") or "").strip()
    except Exception:
        return ""


def to_responses_request(base_url, msgs, model, temperature=0.3):
    """Build OpenAI Responses request: (url, headers, body).
    
    system goes to instructions; history goes to input items.
    """
    url = base_url or "https://api.openai.com/v1/responses"
    if "/responses" not in url.lower():
        if url.rstrip("/").endswith("/chat/completions"):
            url = url[: -len("/chat/completions")] + "/v1/responses"
    sys_parts = []
    items = []
    for m in msgs:
        if not isinstance(m, dict):
            continue
        r = m.get("role")
        c = m.get("content") or ""
        if not c:
            continue
        if r == "system":
            sys_parts.append(c)
        elif r == "user":
            items.append({"role": "user", "content": [{"type": "input_text", "text": c}]})
        elif r == "assistant":
            items.append({"role": "assistant", "content": [{"type": "output_text", "text": c}]})
    body = {"model": model, "input": items}
    if sys_parts:
        body["instructions"] = (chr(10) + chr(10)).join(sys_parts)
    if temperature is not None:
        body["temperature"] = float(temperature)
    headers = {
        "Authorization": "Bearer " + _chat_key(),
        "Content-Type": "application/json",
    }
    return url, headers, body


def from_anthropic(obj):
    """Anthropic response -> (content, reasoning)."""
    content = []
    reasoning = []
    if not isinstance(obj, dict):
        return "", ""
    for blk in (obj.get("content") or []):
        if not isinstance(blk, dict):
            continue
        t = blk.get("type")
        if t == "text":
            content.append(blk.get("text") or "")
        elif t == "thinking":
            reasoning.append(blk.get("thinking") or "")
    return "".join(content), "".join(reasoning)


def from_responses(obj):
    """Responses response -> (content, reasoning)."""
    if not isinstance(obj, dict):
        return "", ""
    ot = obj.get("output_text")
    if isinstance(ot, str) and ot:
        return ot, ""
    content = []
    reasoning = []
    for item in (obj.get("output") or []):
        if not isinstance(item, dict):
            continue
        t = item.get("type")
        if t == "message":
            for c in (item.get("content") or []):
                if isinstance(c, dict) and c.get("type") == "output_text":
                    content.append(c.get("text") or "")
        elif t == "reasoning":
            for s in (item.get("summary") or []):
                if isinstance(s, dict) and s.get("type") == "summary_text":
                    reasoning.append(s.get("text") or "")
    return "".join(content), "".join(reasoning)


def from_chat(obj):
    if not isinstance(obj, dict):
        return "", ""
    try:
        msg = obj["choices"][0]["message"]
    except Exception:
        return "", ""
    return (msg.get("content") or ""), (msg.get("reasoning_content") or "")


def extract_text(provider, obj):
    if provider == PROVIDER_ANTHROPIC:
        return from_anthropic(obj)
    if provider == PROVIDER_RESPONSES:
        return from_responses(obj)
    return from_chat(obj)
