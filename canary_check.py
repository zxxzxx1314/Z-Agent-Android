import hashlib, zipfile, os, sys

SRC = "app/src/main/assets/canary.txt"
APK = "app/build/outputs/apk/release/app-release.apk"

if not os.path.isfile(SRC):
    print("canary source missing, skip"); sys.exit(0)
exp = hashlib.sha256(open(SRC, "rb").read()).hexdigest()
print("source canary sha=" + exp)

if not os.path.isfile(APK):
    print("apk not built yet, skip apk check"); sys.exit(0)
z = zipfile.ZipFile(APK)
try:
    data = z.read("assets/canary.txt")
except KeyError:
    print("canary not packed in apk, skip"); sys.exit(0)
got = hashlib.sha256(data).hexdigest()
print("apk canary sha=" + got)

if exp != got:
    print("FAIL: canary mismatch")
    sys.exit(1)
print("OK: canary bytes preserved")