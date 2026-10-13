import re

PATTERN = "(ghp_|github_pat_|sk-)([A-Za-z0-9_-]{8,})|(Bearer +)([A-Za-z0-9._-]{8,})"
RX = re.compile(PATTERN)


def maskOne(s):
    if len(s) <= 8:
        return s
    return s[:4] + "..." + s[-4:] + " [已脱敏]"


def scrub(text):
    if not text:
        return text
    def repl(m):
        if m.group(1):
            return m.group(1) + maskOne(m.group(2))
        return m.group(3) + maskOne(m.group(4))
    try:
        return RX.sub(repl, str(text))
    except Exception:
        return text