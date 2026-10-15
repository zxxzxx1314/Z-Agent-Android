import sys
REPO = "/data/data/com.agent.agoni/files/home/zagent_push/repo/app/src/main/python"
sys.path.insert(0, REPO)
from zcore import proc

fails = 0
def chk(name, cond):
    global fails
    print(("PASS " if cond else "FAIL ") + name)
    if not cond:
        fails = fails + 1

r = proc.run(["true"])
chk("true ok", r["rc"] == 0 and r["kind"] == "ok")
r = proc.run(["false"])
chk("false exit", r["rc"] == 1 and r["kind"] == "exit")
r = proc.run(["/no/such/bin"])
chk("missing not_found", r["kind"] == "not_found")
r = proc.run("echo hi")
chk("shell echo", r["kind"] == "ok" and "hi" in r["out"])
r = proc.run(["sh", "-c", "sleep 3"], timeout=1)
chk("timeout", r["kind"] == "timeout" and r["rc"] == 124)
r = proc.run(["sh", "-c", "exit 7"])
chk("exit7", r["rc"] == 7 and r["kind"] == "exit")

print("PROC" + ("OK" if fails == 0 else "FAIL"))
sys.exit(0 if fails == 0 else 1)