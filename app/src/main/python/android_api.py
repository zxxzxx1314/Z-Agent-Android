# -- coding: utf-8 --
"""android_api —— Android 原生能力的 Python 侧封装（替代 termux-api）

Chaquopy 允许 Python 直接 import Java 类。本模块把常用能力包装成
纯 Python 函数，供 Agent 脚本使用：

 import android_api
 info = android_api.battery_info() # {"percent": 87, "charging": True}
 android_api.notify("任务完成", "报告已生成") # 系统通知
 android_api.toast("已保存") # 屏幕轻提示

设计原则：
 - 顶层绝不 import Java（桌面/沙箱环境导入本模块不会炸）；
 - 每个能力独立 try/except，Java 侧不可用时返回明确的降级信息，
 绝不让一次能力查询崩溃宿主会话。
"""
import json


def _java():
    """惰性取 AndroidAPI Java 类；非 Android 环境返回 None。"""
    try:
        from cn.rain.zagent import AndroidAPI
        return AndroidAPI
    except Exception:
        return None


def battery_info():
    """电量与充电状态（dict）。不可用时返回 {"error": ...}。"""
    api = _java()
    if api is None:
        return {"error": "AndroidAPI 不可用（非 APK 环境）"}
    try:
        return json.loads(api.getBatteryStatus())
    except Exception as e:
        return {"error": repr(e)}


def notify(title, content):
    """发一条系统通知（需 POST_NOTIFICATIONS 权限）。"""
    api = _java()
    if api is None:
        return False
    try:
        api.notify(str(title), str(content))
        return True
    except Exception:
        return False


def toast(message):
    """屏幕轻提示。"""
    api = _java()
    if api is None:
        return False
    try:
        api.toast(str(message))
        return True
    except Exception:
        return False


def device_info():
    """机型/系统版本等基本信息。"""
    api = _java()
    if api is None:
        return {"error": "AndroidAPI 不可用"}
    try:
        return json.loads(api.getDeviceInfo())
    except Exception as e:
        return {"error": repr(e)}


#
# v2 扩展（Java 侧 AndroidAPI.kt 补的方法）
#


def vibrate(ms=500):
    """振动 ms 毫秒（0-60000）。"""
    api = _java()
    if api is None:
        return {"ok": False, "error": "AndroidAPI 不可用"}
    try:
        api.vibrate(int(ms))
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": repr(e)}


def clipboard_get():
    """读剪贴板文本。"""
    api = _java()
    if api is None:
        return {"ok": False, "error": "AndroidAPI 不可用"}
    try:
        return {"ok": True, "text": api.clipboardGet()}
    except Exception as e:
        return {"ok": False, "error": repr(e)}


def clipboard_set(text):
    """写剪贴板。"""
    api = _java()
    if api is None:
        return {"ok": False, "error": "AndroidAPI 不可用"}
    try:
        api.clipboardSet(str(text))
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": repr(e)}


def volume():
    """读媒体音量 {music, max}。"""
    api = _java()
    if api is None:
        return {"ok": False, "error": "AndroidAPI 不可用"}
    try:
        return {"ok": True, "data": json.loads(api.getVolume())}
    except Exception as e:
        return {"ok": False, "error": repr(e)}


def set_volume(level):
    """设媒体音量（0-15）。"""
    api = _java()
    if api is None:
        return {"ok": False, "error": "AndroidAPI 不可用"}
    try:
        api.setVolume(int(level))
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": repr(e)}


def open_url(url):
    """系统浏览器打开 URL。"""
    api = _java()
    if api is None:
        return {"ok": False, "error": "AndroidAPI 不可用"}
    try:
        api.openUrl(str(url))
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": repr(e)}


# Linux shell（proot + Termux bootstrap）
#
# 走 Java 侧 AndroidAPI.execShell / linuxStatus / linuxBootstrap。
# 底层是 proot 挂载 Termux rootfs，能在 APK 里跑 ls / cat / python / apt 等。
# 首次调用会触发 bootstrap（解压约 90MB，耗时约 30 秒），之后常驻。


def shell_exec(cmd, timeout=60):
    """在 proot Linux 环境执行命令，返回 ok/code/stdout/stderr/timeout。"""
    api = _java()
    if api is None:
        return {"ok": False, "error": "AndroidAPI 不可用"}
    try:
        return json.loads(api.execShell(str(cmd), int(timeout)))
    except Exception as e:
        return {"ok": False, "error": repr(e)}


def shell_status():
    """Linux 环境状态：ready/booting/err/rootfs_exists/bash_exists。"""
    api = _java()
    if api is None:
        return {"ok": False, "error": "AndroidAPI 不可用"}
    try:
        return json.loads(api.linuxStatus())
    except Exception as e:
        return {"ok": False, "error": repr(e)}


def shell_bootstrap():
    """主动触发 bootstrap；成功返回 ready 字符串，失败返回 fail: 前缀。"""
    api = _java()
    if api is None:
        return "AndroidAPI 不可用"
    try:
        return str(api.linuxBootstrap())
    except Exception as e:
        return "fail: " + repr(e)

def eval_js(url, js):
    api = _java()
    if api is None:
        return '{"ok": false, "error": "AndroidAPI not available"}'
    try:
        return api.evalJs(str(url), str(js))
    except Exception as e:
        return '{"ok": false, "error": "' + str(e) + '"}'

def get_window_tree():
    """获取当前活动窗口的 UI 树结构（JSON 字符串）。"""
    api = _java()
    if api is None:
        return "{\"error\": \"AndroidAPI 不可用\"}"
    try:
        return api.getWindowTree()
    except Exception as e:
        return "{\"error\": \"%s\"}" % repr(e)

def gesture_tap(x, y, duration_ms=50):
    """在屏幕坐标发一个真实触摸。返回 dict：ok/outcome/x/y/err。

    outcome 取 COMPLETED / CANCELLED / NOT_DISPATCHED / UNKNOWN；
    只有 NOT_DISPATCHED 可以安全换其它路径重试。
    """
    api = _java()
    if api is None:
        return {"ok": False, "outcome": "NOT_DISPATCHED", "err": "AndroidAPI not available"}
    try:
        raw = api.gestureTap(float(x), float(y), int(duration_ms))
        return json.loads(raw) if raw else {"ok": False, "outcome": "UNKNOWN"}
    except Exception as e:
        return {"ok": False, "outcome": "UNKNOWN", "err": repr(e)}


def click_node_by_text(text):
    """按文本找可点击祖先并发真实触摸。返回 dict：ok/outcome/x/y/err。"""
    api = _java()
    if api is None:
        return {"ok": False, "outcome": "NOT_DISPATCHED", "err": "AndroidAPI not available"}
    try:
        raw = api.clickNodeByText(str(text))
        return json.loads(raw) if raw else {"ok": False, "outcome": "UNKNOWN"}
    except Exception as e:
        return {"ok": False, "outcome": "UNKNOWN", "err": repr(e)}


def screenshot(excluded_packages=None):
    """截取当前可截的窗口，返回 dict：ok/outcome/png_base64/window_id/bytes/err。

    excluded_packages: 要排除的包名列表（如语音浮层）。
    API < 30 直接 NOT_DISPATCHED；主线程调用直接 NOT_DISPATCHED。
    """
    api = _java()
    if api is None:
        return {"ok": False, "outcome": "NOT_DISPATCHED", "err": "AndroidAPI not available"}
    try:
        raw = api.screenshot(json.dumps(list(excluded_packages or [])))
        return json.loads(raw) if raw else {"ok": False, "outcome": "UNKNOWN"}
    except Exception as e:
        return {"ok": False, "outcome": "UNKNOWN", "err": repr(e)}


def ocr_image(image_path):
    """对图片进行 OCR 识别，返回提取出的文本。"""
    api = _java()
    if api is None:
        return "Error: AndroidAPI 不可用"
    try:
        return api.ocrImage(str(image_path))
    except Exception as e:
        return "Error: " + repr(e)
def show_dynamic_ui(json_str):
    """渲染动态 UI JSON。"""
    api = _java()
    if api is None:
        return "Error: AndroidAPI 不可用"
    try:
        return api.showDynamicUi(str(json_str))
    except Exception as e:
        return "Error: " + repr(e)

def accessibility_status():
    """无障碍服务三态健康检查：DISCONNECTED / STALE / READY。"""
    api = _java()
    if api is None:
        return {"ready": False, "health": "DISCONNECTED", "msg": "AndroidAPI not available"}
    try:
        raw = api.accessibilityStatus()
        return json.loads(raw) if raw else {"ready": False, "health": "STALE"}
    except Exception as e:
        return {"ready": False, "health": "UNKNOWN", "msg": repr(e)}


def accessibility_ready():
    return bool(accessibility_status().get("ready"))


def capability_snapshot():
    # 当前设备能力快照（dict）。不可用时返回空 dict，调用方按无条件处理。
    api = _java()
    if api is None:
        return {}
    try:
        raw = api.capabilitySnapshot()
        return json.loads(raw) if raw else {}
    except Exception:
        return {}

# task 21: user scope + POSIX shell quote
def app_user_id():
    api = _java()
    if api is None:
        return 0
    try:
        return int(api.appUserId())
    except Exception:
        return 0


def sh_quote(s):
    return chr(39) + str(s).replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39)) + chr(39)
