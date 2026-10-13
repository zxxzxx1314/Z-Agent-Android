import json, sys, io, importlib.util

FIX = "app/src/main/assets/mask_fixture.json"
MPY = "app/src/main/python/zcore/mask.py"

spec = importlib.util.spec_from_file_location("zmask", MPY)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

data = json.load(io.open(FIX, encoding="utf-8"))
failed = 0
for case in data["cases"]:
    got = mod.scrub(case["input"])
    if got != case["expect"]:
        print("FAIL " + case["name"])
        print(" expect: " + repr(case["expect"]))
        print(" got : " + repr(got))
        failed += 1
    else:
        print("OK " + case["name"])
if failed:
    sys.exit(1)
print("py ALL PASS " + str(len(data["cases"])) + " cases")