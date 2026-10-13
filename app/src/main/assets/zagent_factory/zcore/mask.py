import re

R = re.compile("(ghp|github_pat_|sk-)([A-Za-z0-9_-]{8,})|(Bearer +)([A-Za-z0-9_.-]{8,})")


def _one(s):
    if len(s) <= 8:
        return s
    return s[:4] + "..." + s[-4:] + " [已脱敏]"


def scrub(text):
    if not text:
        return text
    def _r(m):
        if m.group(1):
            return m.group(1) + _one(m.group(2))
        return m.group(3) + _one(m.group(4))
    try:
        return _R.sub(_r, str(text))
    except Exception:
        return text