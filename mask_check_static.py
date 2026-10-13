import io, sys
PAT = io.open("app/src/main/assets/mask_pattern.txt").read().strip()
M = io.open("app/src/main/python/zcore/mask.py").read()
K = io.open("app/src/main/java/cn/rain/zagent/MainActivity.kt").read()
if PAT not in M:
    print("MISMATCH: mask.py 不含 pattern")
    sys.exit(1)
if PAT not in K:
    print("MISMATCH: MainActivity.kt 不含 pattern")
    sys.exit(1)
print("PATTERN consistent across 3 sources")