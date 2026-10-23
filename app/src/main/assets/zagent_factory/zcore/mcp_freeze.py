"""MCP tool freeze: snapshot enabled tools at run start."""
import copy


_FROZEN = {"tools": None, "token": None, "tag": None}


def freeze(tools, token=None, tag=None):
    if tools is None:
        tools = []
    _FROZEN["tools"] = copy.deepcopy(list(tools))
    _FROZEN["token"] = token
    _FROZEN["tag"] = tag
    return len(_FROZEN["tools"])


def frozen():
    return _FROZEN["tools"]


def token():
    return _FROZEN["token"]


def clear():
    _FROZEN["tools"] = None
    _FROZEN["token"] = None
    _FROZEN["tag"] = None


def lookup(name):
    snap = _FROZEN["tools"]
    if snap is None:
        return None
    for t in snap:
        if t.get("name") == name:
            return t
    return None

