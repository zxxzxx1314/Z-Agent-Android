"""zcore.provider_bridge -- dispatch to alt providers."""
import zagent
from zcore import provider as pv


class _Skip(object):
    def __repr__(self):
        return 'SKIP'


SKIP = _Skip()


_CACHE = [None, None]


def provider_kind():
    cur = zagent.BASE_URL
    if _CACHE[0] != cur or _CACHE[1] is None:
        _CACHE[0] = cur
        _CACHE[1] = pv.detect_provider(cur)
    return _CACHE[1]


def _post_alt(url, headers, body):
    try:
        resp = zagent._HTTP_SESSION.post(
            url, headers=headers, json=body,
            timeout=zagent._POST_TIMEOUT)
    except Exception as e:
        print("[provider] net error: %r" % (e,))
        return None
    if resp.status_code != 200:
        print("[provider] http %d: %s" % (resp.status_code, resp.text[:400]))
        return None
    try:
        return resp.json()
    except Exception:
        print("[provider] json parse failed")
        return None


def try_alt_protocol(msgs, temperature):
    kind = provider_kind()
    if kind == pv.PROVIDER_CHAT:
        return SKIP
    if kind == pv.PROVIDER_ANTHROPIC:
        url, headers, body = pv.to_anthropic_request(
            zagent.BASE_URL, msgs, zagent.MODEL, temperature, max_tokens=4096)
    else:
        url, headers, body = pv.to_responses_request(
            zagent.BASE_URL, msgs, zagent.MODEL, temperature)
    obj = _post_alt(url, headers, body)
    if obj is None:
        try:
            zagent._STREAM_DONE[0] = True
        except Exception:
            pass
        return None
    content, reasoning = pv.extract_text(kind, obj)
    try:
        zagent._REASONING_BUF = reasoning or ""
    except Exception:
        pass
    if reasoning:
        try:
            zagent._STREAM_BUF.append(("reason", reasoning))
        except Exception:
            pass
    if content:
        try:
            zagent._STREAM_BUF.append(("content", content))
        except Exception:
            pass
    try:
        zagent._STREAM_DONE[0] = True
    except Exception:
        pass
    try:
        return zagent._extract_btn_tags(content)
    except Exception:
        return content
