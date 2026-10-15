# test_gate.py —— 最小自测（无外部依赖）
import os, sys, tempfile, shutil, json

# 隔离 HOME 以免污染真实 ~/mind
TMP = tempfile.mkdtemp(prefix="gatetest_")
os.environ["HOME"] = TMP
# 清掉可能继承的坑
for k in ("RAIN_BASE_URL", "RAIN_MODEL"):
    os.environ.pop(k, None)

# 强制 reload gate 模块（HOME 变了）
sys.path.insert(0, "/sdcard/Download/Z-Agent-App/app/src/main/python")
import importlib
import zcore.gate as g
importlib.reload(g)

fails = []
def check(cond, msg):
    if not cond:
        fails.append(msg)
        print("FAIL:", msg)
    else:
        print("ok :", msg)

# 1. 默认 L2
st = g.status()
check(st["level"] == g.L2_SEMI, "默认 level=L2")

# 2. L2 可 script.run
ok, why = g.check("script.run")
check(ok, "L2 script.run 通过")

# 3. L2 不可 evolve
ok, why = g.check("evolve")
check(not ok, "L2 evolve 被拒: " + why)

# 4. 授权 L3 后可 evolve
g.grant(3, ttl=60, actor="test")
ok, why = g.check("evolve")
check(ok, "L3 evolve 通过")

# 5. actions 白名单
g.grant(3, actions=["evolve"], ttl=60, actor="test")
ok, _ = g.check("evolve")
check(ok, "白名单含 evolve 通过")
ok, why = g.check("restart")
check(not ok, "白名单不含 restart 被拒")

# 6. 熔断
g.circuit_trip("test trip")
ok, why = g.check("script.run")
check(not ok, "熔断后 script.run 被拒")
check(g.status()["level"] == g.L0_OBSERVE, "熔断后 level=L0")

# 7. 人类授权解除熔断
g.grant(2, ttl=60, actor="human:test")
ok, _ = g.check("script.run")
check(ok, "人类授权解除熔断")

# 8. kill 文件触发 SystemExit
open(g.KILL_PATH, "w").write("1")
try:
    g.check("read")
    check(False, "kill 未触发")
except SystemExit:
    check(True, "kill 触发 SystemExit")
os.remove(g.KILL_PATH)

# 9. 过期自动降级
g.grant(3, ttl=1, actor="test")
import time as _t
_t.sleep(2)
ok, why = g.check("evolve")
check(not ok, "过期后 evolve 被拒: " + why)

shutil.rmtree(TMP, ignore_errors=True)
print("TOTAL FAILS:", len(fails))
sys.exit(1 if fails else 0)