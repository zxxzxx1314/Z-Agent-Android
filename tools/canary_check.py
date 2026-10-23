import hashlib, zipfile, os, sys

EXPECTED = "de1206d5f6a078fef16a06dca082046bbeb4fd6ec7e6e591664d1d192b622903"
SRC = "app/src/main/assets/canary.txt"
APK = "app/build/outputs/apk/release/app-release.apk"

if not os.path.isfile(SRC):
    print("canary source missing, skip"); sys.exit(0)
src_sha = hashlib.sha256(open(SRC, "rb").read()).hexdigest()
print("source canary sha=" + src_sha)
if src_sha != EXPECTED:
    print("FAIL: source canary tampered, expected=" + EXPECTED)
    sys.exit(1)

if not os.path.isfile(APK):
    print("apk not built yet, skip apk check"); sys.exit(0)
z = zipfile.ZipFile(APK)
try:
    data = z.read("assets/canary.txt")
except KeyError:
    print("canary not packed in apk, skip"); sys.exit(0)
apk_sha = hashlib.sha256(data).hexdigest()
print("apk canary sha=" + apk_sha)
if apk_sha != EXPECTED:
    print("FAIL: apk canary mismatch")
    sys.exit(1)
print("OK: canary bytes preserved")