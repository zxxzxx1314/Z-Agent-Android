# -*- coding: utf-8 -*-
"""android_api —— Android 原生能力的 Python 侧封装（替代 termux-api）

Chaquopy 允许 Python 直接 import Java 类。本模块把常用能力包装成
纯 Python 函数，供 Agent 脚本使用：

    import android_api
    info = android_api.battery_info()      # {"percent": 87, "charging": True}
    android_api.notify("任务完成", "报告已生成")   # 系统通知
    android_api.toast("已保存")                    # 屏幕轻提示

设计原则：
  - 顶层绝不 import Java（桌面/沙箱环境导入本模块不会炸）；
  - 每个能力独立 try/except，Java 侧不可用时返回明确的降级信息，
    绝不让一次能力查询崩溃宿主会话。"""
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
    """发一条系统通知（需 POST_NOTIFICATIONS 权限，Android 13+ 会请求）。"""
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