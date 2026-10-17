import re

PATTERN = r"(ghp_|gho_|ghu_|ghs_|ghr_)([A-Za-z0-9]{20,})|(github_pat_)([A-Za-z0-9_]{12,})|(glpat-)([A-Za-z0-9_-]{20,})|(sk-ant-|sk-proj-|sk-svcacct-)([A-Za-z0-9_-]{20,})|(sk-)([A-Za-z0-9]{20,})|(hf_)([A-Za-z0-9]{20,})|(npm_)([A-Za-z0-9]{20,})|(xox[baprs]-)([A-Za-z0-9-]{12,})|(Bearer +)([A-Za-z0-9._-]{16,})|(AKIA)([0-9A-Z]{16})|(AIza)([0-9A-Za-z_-]{35})|(sk_live_|pk_live_|rk_live_)([A-Za-z0-9]{16,})|(eyJ)([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)"
RX = re.compile(PATTERN)


def maskOne(s):
	if len(s) <= 8:
		return s
	return s[:4] + "..." + s[-4:] + " [已脱敏]"


def scrub(text):
	if not text:
		return text
	def repl(m):
		groups = m.groups()
		for i in range(0, len(groups), 2):
			if groups[i]:
				return groups[i] + maskOne(groups[i + 1] or "")
		return m.group(0)
	try:
		return RX.sub(repl, str(text))
	except Exception:
		return text
