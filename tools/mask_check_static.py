import io, sys
PAT = io.open('app/src/main/assets/mask_pattern.txt').read().strip()
M = io.open('app/src/main/python/zcore/mask.py').read()
K = io.open('app/src/main/java/cn/rain/zagent/MainActivity.kt').read()
B = chr(92)
K2 = K.replace(B + B, B)
if PAT not in M:
	print('MISMATCH: mask.py does not contain pattern')
	sys.exit(1)
if PAT not in K2:
	print('MISMATCH: MainActivity.kt does not contain pattern (after unescape)')
	sys.exit(1)
print('PATTERN consistent across 3 sources')
