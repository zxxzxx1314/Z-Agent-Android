# -*- coding: utf-8 -*-
"""zagent_app —— APK 模式 Python 入口（Chaquopy 桥接适配器）

Java 侧（PythonBridge.kt）只认识本模块的这几个函数：
    bootstrap(home_path)   初始化（看门狗体检 → 惰性加载真身 → 会话恢复）
    run_agent(user_input)  一轮交互（命令或对话），返回本轮全部输出文本
    set_api_key(key)       写入 mind/config.json 并热更新
    set_base_url(url)      设置 LLM API 地址（空=恢复默认，自动补全端点）
    set_model(name)        设置模型名称（空=恢复默认，写入 config.json 并热更新）
    get_status()           JSON 状态（版本/世代/自动执行/Key/地址/模型名是否就绪…）
    set_auto(flag)         切换 AUTO_EXECUTE
    reset_session()        清空会话（等价 /clear）

「手术」设计（为什么不逐行改 zagent.py 的 print/input）：
    2100+ 行身经百战的核心零重写风险——用**模块级名字重绑**完成同等手术：
    zag.print = 捕获器（输出进本轮缓冲，由 Java 显示在界面）
    zu.print  = 捕获器（zcore 的执行结果回显一并捕获）
    zag.input = 禁止器（APK 无终端；AUTO_EXECUTE 默认开，确认路径不触发）
    适配器复刻 main() 的初始化语义（目录/Key/系统提示/会话/插件），
    每轮 run_agent 复刻 REPL 的一圈（note_task → agent_loop → save_session）。

命令特判：/exit 与 /restart 在 APK 内不执行（无 execv/退出语义），
改为提示用户重开应用；其余命令原样交给 handle_command。

失败语义：bootstrap 失败（基因损坏且无法救援）→ run_agent 返回
带 [FATAL] 前缀的说明，PythonBridge 显示并阻止后续调用。"""
import json
import os
import sys
import traceback

_state = {"ready": False, "err": "", "home": "", "boot_out": []}
_zag = None          # 惰性加载的 zagent 模块
_zu = None           # zcore.util（print 一并捕获）
_deploy_note = ""    # 最近一次出厂基因读取命中的机制（诊断横幅用）

APK_PROMPT_ADDON = """

【当前宿主：Android APK（v6.1 迁移版）】
- 你运行在图形界面 App 内，没有终端；print 由界面展示，无需 input()。
- 你的脚本在本进程内执行（无子进程隔离）：不要用 os.fork/多进程；
  不要 termux-* 命令（不可用）。
- 系统能力改用 Android 原生：import android_api 后可用
  android_api.battery_info() / notify(标题, 内容) / toast(消息)。
- /restart 与 /exit 无效；进化（[evolve:]）在本环境被影子闸门
  fail-closed 拒绝——基因手术请回 Termux 开发环境完成后再打包。
"""


# ---------------- print 捕获 / input 禁止 ----------------
def _make_printer(buf):
    def _print(*args, sep=" ", end="\n", file=None, flush=False):
        try:
            s = sep.join(str(a) for a in args) + end
            try:
             from zcore.mask import scrub as msk
             s = msk(s)
            except Exception:
             pass
            buf.append(s)
        except Exception:
            pass
    return _print


def _no_input(prompt=""):
    raise RuntimeError(
        "APK 模式没有终端输入。请在界面设置中开启自动执行，"
        "或把需要确认的操作改为计划模式（/plan）。")


def _load_key_noninteractive():
    """load_api_key 的非交互版：env → config.json → 空（绝不停下问人）。"""
    env_key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    if env_key:
        return env_key
    try:
        with open(_zag.CONFIG_PATH, "r", encoding="utf-8") as f:
            return str(json.load(f).get("api_key", "")).strip()
    except Exception:
        return ""


_DEFAULT_BASE_URL = ""      # bootstrap 首次加载 zagent 时记录其出厂默认值


def _normalize_base_url(url):
    """把用户输入规整成完整 chat 端点。
    兼容三种粘贴习惯：裸域名 / 带 /v1 / 完整 /chat/completions。
    空输入原样返回（语义=恢复默认，由调用方处理）。"""
    u = (url or "").strip().rstrip("/")
    if not u:
        return ""
    if not (u.startswith("http://") or u.startswith("https://")):
        u = "https://" + u          # 裸域名自动补协议
    if u.endswith("/chat/completions") or u.endswith("/completions"):
        return u
    return u + "/chat/completions"


def _load_base_url_noninteractive():
    """load_base_url 的非交互版：env → config.json → 空（空=沿用出厂默认）。"""
    env_url = os.environ.get("RAIN_BASE_URL", "").strip()
    if env_url:
        return _normalize_base_url(env_url)
    try:
        with open(_zag.CONFIG_PATH, "r", encoding="utf-8") as f:
            raw = str(json.load(f).get("base_url", "")).strip()
    except Exception:
        raw = ""
    return _normalize_base_url(raw)


def _load_model_noninteractive():
    """zagent.load_model 的非交互版：env(RAIN_MODEL) → config.json(model) → 空。
    空串 = 沿用 zagent.py 出厂默认（不覆盖，交给 load_model 决定）。"""
    env_model = os.environ.get("RAIN_MODEL", "").strip()
    if env_model:
        return env_model
    try:
        with open(_zag.CONFIG_PATH, "r", encoding="utf-8") as f:
            raw = str(json.load(f).get("model", "")).strip()
    except Exception:
        raw = ""
    return raw


# 出厂基因静态清单（机制2 的枚举兜底）。新增 zcore/zguard 源文件时
# 同步更新；reader 枚举成功时会自动补全，无需手工维护精确。
_FACTORY_MANIFEST = {
    "zcore": ("__init__.py", "util.py"),
    "zguard": ("__init__.py", "boot_guard.py", "boot_guard_selftest.py",
               "shadow_child.py", "shadow_test.py"),
}


def _factory_sources_via_files(me):
    """机制1：__file__ 同目录的真实文件（Termux/开发机）。"""
    if not os.path.isfile(os.path.join(me, "zagent.py")):
        return None
    out = {"zagent.py": open(os.path.join(me, "zagent.py"), "rb").read()}
    for pkg in ("zcore", "zguard"):
        d = os.path.join(me, pkg)
        if os.path.isdir(d):
            for fn in sorted(os.listdir(d)):
                if fn.endswith(".py"):
                    out["%s/%s" % (pkg, fn)] = open(os.path.join(d, fn), "rb").read()
    return out


def _factory_sources_via_importlib():
    """机制2：标准导入协议读取（Chaquopy 16 起 .py 默认从 APK 直接
    加载、不落盘，os.path 意义上「源码目录」并不存在）。

    依据：Chaquopy 的 SourceAssetLoader 继承
    importlib.machinery.SourceFileLoader，get_source /
    get_resource_reader 均可用；find_spec 只定位不执行模块，
    「看门狗体检前真身不进内存」的次序保持不变。"""
    import importlib.util
    spec = importlib.util.find_spec("zagent")
    if spec is None or spec.loader is None:
        raise RuntimeError("find_spec('zagent') 未命中")
    src = spec.loader.get_source("zagent")
    if src is None:
        raise RuntimeError("loader=%s 未提供源码（可能仅打包了 .pyc）"
                           % type(spec.loader).__name__)
    out = {"zagent.py": src.encode("utf-8")}
    for pkg in ("zcore", "zguard"):
        pspec = importlib.util.find_spec(pkg)
        if pspec is None or pspec.loader is None:
            raise RuntimeError("find_spec(%r) 未命中" % pkg)
        names = set(_FACTORY_MANIFEST.get(pkg, ()))
        root = None
        try:
            reader = pspec.loader.get_resource_reader(pkg)
            if reader is not None:
                root = reader.files()
                names |= {e.name for e in root.iterdir()
                          if e.name.endswith(".py")}
        except Exception:
            root = None
        for fn in sorted(names):
            data = None
            if root is not None:                 # 优先 reader（零执行）
                try:                             # 空文件返回 b""，也是成功
                    data = root.joinpath(fn).read_bytes()
                except Exception:
                    data = None
            if data is None:                     # 回退子模块 spec（仅触发
                modname = pkg if fn == "__init__.py" else pkg + "." + fn[:-3]
                try:                             # 工厂 __init__，无副作用）
                    mspec = importlib.util.find_spec(modname)
                    if mspec is not None and mspec.loader is not None:
                        s = mspec.loader.get_source(modname)
                        if s is not None:
                            data = s.encode("utf-8")
                except Exception:
                    data = None
            if data is None:
                raise RuntimeError("读取 %s/%s 失败（loader=%s）"
                                   % (pkg, fn, type(pspec.loader).__name__))
            out["%s/%s" % (pkg, fn)] = data
    return out


def _factory_sources_via_factory_dir(factory):
    """机制0：直接读 Kotlin 预复制的出厂源码目录 home/factory
    （PythonBridge.deployFactoryGenome 把 APK assets/zagent_factory
    落盘到这里）。纯磁盘文件读取，【不经过 importlib / Chaquopy
    导入器】——不受任何打包与加载机制影响。

    真机实证（2026-10-12）：Chaquopy 默认把 .py 编译为 .pyc 且不把
    源码打进 APK，机制2 的 find_spec 命中 SourcelessAssetLoader
    （该 loader 无 get_source）→ 出厂基因部署失败（FATAL(4)）。APK
    形态下机制0 是主通道；Termux/开发机无 factory 时自然回落机制1。"""
    if not factory or not os.path.isfile(os.path.join(factory, "zagent.py")):
        return None
    out = {}
    for root, dirs, files in os.walk(factory):
        dirs[:] = [d for d in dirs if d != "__pycache__"]
        for fn in sorted(files):
            if not fn.endswith(".py"):
                continue
            p = os.path.join(root, fn)
            rel = os.path.relpath(p, factory).replace(os.sep, "/")
            out[rel] = open(p, "rb").read()
    return out or None


def _deploy_factory_genome(home):
    """出厂基因（APK 内打包源码，只读）→ 可写基因层 home/genome/。

    Chaquopy 打包的 Python 源在 APK 内运行时只读——进化/回滚/快照
    都不可能写回 APK。因此部署一份可写副本作为运行基因：看门狗
    快照、（若环境允许的）进化都发生在副本上。出厂基因更新
    （指纹变化）时自动重刷副本；心智/会话/技能在 home 根下不受影响。
    返回 genome 目录。

    源码获取（三栖，按序尝试）：
      机制0  home/factory 磁盘直读（Kotlin 预复制，APK 主通道）；
      机制1  __file__ 同目录的真实文件（Termux/开发机）；
      机制2  标准导入协议 find_spec().loader.get_source()。"""
    global _deploy_note
    me = os.path.dirname(os.path.abspath(__file__))
    genome = os.path.join(home, "genome")
    marker = os.path.join(genome, ".factory_sha")

    sources, errors = None, []
    # ---- 机制0：Kotlin 预复制目录（纯磁盘读，与导入系统无关）----
    try:
        sources = _factory_sources_via_factory_dir(os.path.join(home, "factory"))
        if sources:
            _deploy_note = "机制0 factory 直读"
    except Exception as e:                      # 机制0 异常也要带进诊断
        errors.append("factory: %r" % (e,))
    if not sources:
        try:
            sources = _factory_sources_via_files(me)
            if sources:
                _deploy_note = "机制1 __file__ 同目录"
        except Exception as e:                  # 机制1 异常也要带进诊断
            errors.append("files: %r" % (e,))
    if not sources:
        try:
            sources = _factory_sources_via_importlib()
            if sources:
                _deploy_note = "机制2 importlib"
        except Exception as e:
            errors.append("importlib: %r" % (e,))
    if not sources or not sources.get("zagent.py"):
        raise RuntimeError(
            "出厂基因读取失败：%s（Termux 请检查源码目录完整性；APK "
            "用户请重装；仍复现请把本行反馈给开发者）" % "; ".join(errors))

    import hashlib
    h = hashlib.sha256()
    for rel in sorted(sources):
        h.update(sources[rel])
    sha = h.hexdigest()
    fresh = (os.path.exists(os.path.join(genome, "zagent.py"))
             and os.path.exists(marker)
             and open(marker, encoding="utf-8").read().strip() == sha)
    if not fresh:
        import shutil
        if os.path.isdir(genome):
            shutil.rmtree(genome, ignore_errors=True)
        os.makedirs(genome, exist_ok=True)
        for rel in sorted(sources):
            dst = os.path.join(genome, *rel.split("/"))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as f:
                f.write(sources[rel])
        with open(marker, "w", encoding="utf-8") as f:
            f.write(sha)
        _deploy_note += "（已部署 %d 个源文件→genome，指纹 %s…）" % (
            len(sources), sha[:8])
    else:
        _deploy_note += "（指纹一致 %s…，沿用现有 genome）" % sha[:8]
    return genome


# ---------------- bootstrap ----------------
def bootstrap(home_path):
    """Java 传入口私有目录；必须在任何 zagent 导入之前调用（路径常量
    在导入期定型）。返回 JSON 状态字符串。"""

    if _state["ready"]:
        return get_status()
    _state["home"] = os.path.abspath(home_path)
    os.environ["HOME"] = _state["home"]
    os.environ["RAIN_AUTO_NOTIFY"] = "0"          # termux 通知在 APK 无意义
    os.environ["ZAGENT_NO_SUBPROCESS"] = "1"      # Chaquopy：进程内执行回退
    os.makedirs(_state["home"], exist_ok=True)

    boot_buf = _state["boot_out"]
    boot_buf.append("[bootstrap] HOME=%s\n" % _state["home"])

    # ---- 可写基因层：出厂基因部署到 home/genome（APK 内只读，详见函数文档）----
    try:
        genome = _deploy_factory_genome(_state["home"])
    except Exception as e:                       # 部署失败按 FATAL(4) 收敛
        _state["err"] = "FATAL(4) 出厂基因部署失败：%s" % e
        boot_buf.append("[FATAL] %s\n" % _state["err"])
        traceback.print_exc()
        return get_status()
    os.environ["ZAGENT_HOME"] = genome            # 看门狗/影子以可写基因为根
    sys.path.insert(0, genome)                    # zagent/zcore/zguard 优先从副本加载
    if _deploy_note:                              # 用户可见：确认新代码已进 APK
        boot_buf.append("[bootstrap] 出厂基因 %s\n" % _deploy_note)

    # ---- 看门狗体检（进程内版；坏基因自动回滚快照）----
    from zguard import boot_guard
    ok, msg = boot_guard.bootstrap_check()
    boot_buf.append("[boot_guard] 体检 %s：%s\n" % ("通过" if ok else "失败", msg))
    if not ok:
        _state["err"] = msg
        boot_buf.append("[FATAL] 基因组损坏且无法自动救援，已阻止加载。\n"
                        "请重装应用或回 Termux 环境修复基因。\n")
        return get_status()

    # ---- 惰性加载真身（此前绝不让 zagent 进内存）----
    import zagent as zag
    import zcore.util as zu
    global _zag, _zu
    _zag, _zu = zag, zu
    zag.print = _make_printer(boot_buf)
    zu.print = zag.print

    # ---- 复刻 main() 初始化（交互步骤全部替换为非交互等价物）----
    for d in (zag.SKILLS_DIR, zag.PLUGINS_DIR, zag.MIND_DIR,
              zag.EVOLUTION_DIR, zag.CONTRACTS_DIR):
        os.makedirs(d, exist_ok=True)
    zag.API_KEY = _load_key_noninteractive()
    # Base URL：先记录出厂默认（清空时回退用），再用 env/config 覆盖
    global _DEFAULT_BASE_URL
    _DEFAULT_BASE_URL = str(zag.BASE_URL)
    _bu = _load_base_url_noninteractive()
    if _bu:
        zag.BASE_URL = _bu
    # Model：优先 env/config.json 里用户设置的模型名（settings 界面写入），
    # 否则沿用 zagent.py 出厂默认。加载顺序与 load_base_url 对称。
    _mn = _load_model_noninteractive()
    if _mn:
        zag.MODEL = _mn
    zag.BASE_PROMPT = zag.BASE_PROMPT + APK_PROMPT_ADDON
    zag.AUTO_EXECUTE = True                     # APK 默认自动（无终端确认）
    zag.input = _no_input
    zag.refresh_system()
    _restored = zag.load_session()
    if _restored:
        zag.messages.extend(_restored)
    try:
        zag.install_bundled_plugins()
        zag.selfcheck_plugins()
    except Exception as e:
        boot_buf.append("[插件] 初始化告警：%r\n" % e)
    boot_buf.append("Z-Agent APK 就绪：v%s / Gen-%d / 会话 %d 条%s\n"
                    % (zag.VERSION, zag.get_gen(), len(zag.messages),
                       "" if zag.API_KEY else "（API Key 未配置，请在设置中填写）"))
    _state["ready"] = True
    return get_status()


# ---------------- run_agent ----------------
def run_agent(user_input):
    """一轮交互：命令 → handle_command；对话 → agent_loop。
    返回本轮捕获的全部输出（含 bootstrap 首轮横幅）。"""
    _u0 = (user_input or "").strip()
    if _u0.startswith("/rea"):
        try:
            import rea_core as _rea
            return _rea.handle_command(_u0)
        except Exception as _e:
            return "[REA 异常] " + repr(_e) + chr(10)
    if not _state["ready"]:
        return ("[FATAL] 未就绪：%s\n请重装应用或回 Termux 环境修复。"
                % (_state["err"] or "bootstrap 未完成"))
    zag, zu = _zag, _zu
    turn_buf = []
    if _state["boot_out"]:
        turn_buf.extend(_state["boot_out"])
        _state["boot_out"] = []
    zag.print = _make_printer(turn_buf)
    zu.print = zag.print
    u = (user_input or "").strip()
    try:
        if not u:
            return "".join(turn_buf)
        low = u.lower()
        if low.startswith("/exit"):
            turn_buf.append("会话已保存。APK 模式不退出 Python 运行时；"
                            "如需彻底重启请完全关闭应用后重新打开。\n")
        elif low == "/restart":
            zag.save_session(zag.messages)
            turn_buf.append("会话已持久化。APK 模式的「重启」= 完全关闭"
                            "应用后重新打开（Chaquopy 运行时随 App 生灭）。\n")
        elif low.startswith("/zrun "):
            import base64 as _b64
            _p = u[len("/zrun "):]
            _code = _b64.b64decode(_p.encode()).decode("utf-8")
            turn_buf.append("[运行] 开始执行…" + chr(10))
            try:
                rc, out, err = zag.run_python_code(_code)
                turn_buf.append("[退出码 " + str(rc) + "]" + chr(10))
                turn_buf.append((out or "") + (err or "") + chr(10))
            except Exception as e:
                turn_buf.append("[运行异常] " + repr(e) + chr(10))
        elif low.startswith("/zsave "):
            import base64 as _b64
            _parts = u[len("/zsave "):].split(" ", 1)
            if len(_parts) < 2:
                turn_buf.append("[存插件] 参数不足" + chr(10))
            else:
                try:
                    _name = _b64.b64decode(_parts[0].encode()).decode("utf-8")
                    _code = _b64.b64decode(_parts[1].encode()).decode("utf-8")
                    zag.LAST_SCRIPT = _code
                    zag.LAST_DESC = "从代码块三键保存"
                    zag.save_plugin(_name)
                    turn_buf.append("[存插件] 已保存" + chr(10))
                except Exception as e:
                    turn_buf.append("[存插件异常] " + repr(e) + chr(10))
        elif u.startswith("/"):
            zag.handle_command(u)
        else:
            zag.note_task(u)
            try:
                enhanced = zag.preprocess_attachments(u)
            except Exception as _e:
                enhanced = u
                turn_buf.append("[附件] 预处理异常: %r" % _e)
            zag.messages.append({"role": "user", "content": enhanced})
            verdict = zag.agent_loop()
            if verdict == "api_error":
                turn_buf.append("\n[网络/API 异常] 本轮未完成。"
                                "请检查 API Key 与网络后重试。\n")
        zag.save_session(zag.messages)
    except SystemExit:
        turn_buf.append("\n[提示] 命令请求退出运行时，已拦截（APK 模式"
                        "请直接关闭应用）。\n")
        try:
            zag.save_session(zag.messages)
        except Exception:
            pass
    except Exception:
        turn_buf.append("\n[宿主异常] 本轮中断，会话已尽量保存：\n"
                        + traceback.format_exc()[-1200:] + "\n")
        try:
            zag.save_session(zag.messages)
        except Exception:
            pass
    return "".join(turn_buf)


# ---------------- 设置 / 状态 ----------------
def set_api_key(key):
    key = (key or "").strip()
    cfg = {}
    try:
        with open(_zag.CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception:
        pass
    cfg["api_key"] = key
    os.makedirs(os.path.dirname(_zag.CONFIG_PATH), exist_ok=True)
    with open(_zag.CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=1)
    if _zag:
        _zag.API_KEY = key
    return "ok" if key else "cleared"


def set_base_url(url):
    """设置 LLM API 地址。空串 = 恢复出厂默认（DeepSeek 官方端点）。
    输入兼容裸域名 / 带 /v1 / 完整端点；自动补 /chat/completions。"""
    bu = _normalize_base_url(url)
    cfg = {}
    try:
        with open(_zag.CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception:
        pass
    cfg["base_url"] = bu
    os.makedirs(os.path.dirname(_zag.CONFIG_PATH), exist_ok=True)
    with open(_zag.CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=1)
    if _zag:
        _zag.BASE_URL = bu or _DEFAULT_BASE_URL
    return ("已保存 → %s" % bu) if bu else ("已清空 → 默认 %s" % _DEFAULT_BASE_URL)


def set_model(name):
    """设置模型名称。空串 = 恢复出厂默认（zagent.py 的 DEFAULT_MODEL）。
    写入 mind/config.json 的 model 字段并热更新 zag.MODEL，
    下一轮 run_agent 的 chat 请求立即生效（payload.model）。"""
    mn = (name or "").strip()
    cfg = {}
    try:
        with open(_zag.CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception:
        pass
    cfg["model"] = mn
    os.makedirs(os.path.dirname(_zag.CONFIG_PATH), exist_ok=True)
    with open(_zag.CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=1)
    if _zag:
        if mn:
            _zag.MODEL = mn
        else:
            # 清空 → 回落 env / 出厂默认（此时 config 里 model 已为空）
            fallback = ""
            if hasattr(_zag, "load_model"):
                try:
                    fallback = _zag.load_model()
                except Exception:
                    fallback = ""
            _zag.MODEL = fallback or getattr(_zag, "MODEL", "") or "deepseek-flash"
    return ("已保存 → %s" % mn) if mn else "已清空 → 恢复出厂默认模型"


def set_auto(flag):
    if _zag:
        _zag.AUTO_EXECUTE = bool(flag)
    return "AUTO_EXECUTE=%s" % (bool(flag),)


def set_think(flag):
    """切换 THINK_ENABLED：True 时 _post 与 _post_stream 会按厂商
    往 payload 注入思考参数（thinking/enable_thinking/reasoning_effort）。
    真流式思考需要模型支持 reasoning_content 字段。"""
    if _zag:
        _zag.THINK_ENABLED = bool(flag)
    return "THINK_ENABLED=%s" % (bool(flag),)


def reset_session():
    zag = _zag
    if not zag:
        return "未就绪"
    zag.begin_task_slot(None)
    zag.messages.clear()
    zag.clear_session()
    zag.TODOS = []
    zag.refresh_system()
    return "会话与待办已清空（未验收合同已挂起）"


def get_status():
    st = {"ready": _state["ready"], "err": _state["err"],
          "home": _state["home"], "api_key_set": False,
          "base_url": None, "model": None,
          "version": None, "gen": None, "auto": None, "turns": 0,
          "mode": "APK/Chaquopy"}
    if _zag is not None:
        st.update({"api_key_set": bool(_zag.API_KEY),
                   "base_url": _zag.BASE_URL,
                   "model": _zag.MODEL,
                   "version": _zag.VERSION, "gen": _zag.get_gen(),
                   "auto": bool(_zag.AUTO_EXECUTE),
                   "turns": len(_zag.messages)})
    return json.dumps(st, ensure_ascii=False)


# ---------------- 插件管理（ui1：PluginActivity 专用桥接） ----------------
# 设计边界：
#  - 插件 = home/plugins/*.py（@name/@desc 头部注记）——在 home 根下，
#    【不在 genome/ 内】，看门狗只守基因核心，删除/新增不触发体检；
#  - 命名白名单 + 只碰 xxx.py 一个文件，杜绝路径穿越；
#  - zagent 的 scan_plugins 在下次系统提示刷新时自然感知增删，无需重启。

import re as _re
import time as _time

_PLUGIN_NAME_RE = _re.compile(r"^[\w\-]{1,64}$")


def _plugins_dir():
    """home/plugins（未 bootstrap 或目录不存在 → None）。"""
    if not _state.get("home"):
        return None
    d = os.path.join(_state["home"], "plugins")
    return d if os.path.isdir(d) else None


def plugins_list():
    """已装插件清单 -> JSON (file/name/desc/size/mtime/enabled)"""
    out = []
    d = _plugins_dir()
    _st = {}
    try:
        _sp = os.path.join(d, ".state.json") if d else None
        if _sp and os.path.isfile(_sp):
            with open(_sp, encoding="utf-8") as _f:
                _st = json.load(_f)
    except Exception:
        _st = {}
    if d:
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".py"):
                continue
            p = os.path.join(d, fn)
            try:
                st = os.stat(p)
            except OSError:
                continue
            name, desc = fn[:-3], ""
            try:
                with open(p, encoding="utf-8", errors="replace") as f:
                    head = f.read(4000)
                m = _re.search(r"#\s@name:\s(.+)", head)
                if m:
                    name = m.group(1).strip()
                m = _re.search(r"#\s@desc:\s(.+)", head)
                if m:
                    desc = m.group(1).strip()
            except OSError:
                pass
            out.append({
                "file": fn[:-3],
                "name": name,
                "desc": desc,
                "size": st.st_size,
                "mtime": _time.strftime(
                    "%Y-%m-%d %H:%M", _time.localtime(st.st_mtime)),
                "enabled": _st.get(fn[:-3], True),
            })
    return json.dumps({"ok": True, "plugins": out}, ensure_ascii=False)


def plugin_read(pid):
    """读插件源码 → JSON（内容截 40000 字符防超大文件撑爆内存）。"""
    if not _PLUGIN_NAME_RE.match(pid or ""):
        return json.dumps({"ok": False, "err": "插件名不合法"},
                          ensure_ascii=False)
    d = _plugins_dir()
    if not d:
        return json.dumps({"ok": False, "err": "插件目录不存在（先完成初始化）"},
                          ensure_ascii=False)
    p = os.path.join(d, pid + ".py")
    if not os.path.isfile(p):
        return json.dumps({"ok": False, "err": "插件不存在"}, ensure_ascii=False)
    try:
        with open(p, encoding="utf-8", errors="replace") as f:
            content = f.read(40000)
    except OSError as e:
        return json.dumps({"ok": False, "err": str(e)}, ensure_ascii=False)
    return json.dumps({"ok": True, "file": pid, "content": content},
                      ensure_ascii=False)


def plugin_delete(pid):
    """删插件 → JSON（只允许删 home/plugins/<白名单名>.py）。"""
    if not _PLUGIN_NAME_RE.match(pid or ""):
        return json.dumps({"ok": False, "err": "插件名不合法"},
                          ensure_ascii=False)
    d = _plugins_dir()
    if not d:
        return json.dumps({"ok": False, "err": "插件目录不存在"}, ensure_ascii=False)
    p = os.path.join(d, pid + ".py")
    if not os.path.isfile(p):
        return json.dumps({"ok": False, "err": "插件不存在"}, ensure_ascii=False)
    try:
        os.remove(p)
    except OSError as e:
        return json.dumps({"ok": False, "err": str(e)}, ensure_ascii=False)
    return json.dumps({"ok": True, "file": pid}, ensure_ascii=False)


def plugin_toggle(pid, enabled):
    """启用/禁用插件 -> JSON"""
    if not _PLUGIN_NAME_RE.match(pid or ""):
        return json.dumps({"ok": False, "err": "插件名不合法"}, ensure_ascii=False)
    d = _plugins_dir()
    if not d:
        return json.dumps({"ok": False, "err": "插件目录不存在"}, ensure_ascii=False)
    p = os.path.join(d, pid + ".py")
    if not os.path.isfile(p):
        return json.dumps({"ok": False, "err": "插件不存在"}, ensure_ascii=False)
    sp = os.path.join(d, ".state.json")
    st = {}
    try:
        if os.path.isfile(sp):
            with open(sp, encoding="utf-8") as f:
                st = json.load(f)
    except Exception:
        st = {}
    st[pid] = bool(enabled)
    try:
        with open(sp, "w", encoding="utf-8") as f:
            json.dump(st, f, ensure_ascii=False)
    except OSError as e:
        return json.dumps({"ok": False, "err": str(e)}, ensure_ascii=False)
    return json.dumps({"ok": True, "file": pid, "enabled": bool(enabled)}, ensure_ascii=False)

def poll_stream():
    try:
        buf = _zag._STREAM_BUF
        chunks = [{'t': t, 'v': v} for (t, v) in buf]
        buf.clear()
        done = _zag._STREAM_DONE[0]
        return json.dumps({'ok': True, 'chunks': chunks, 'done': done},
            ensure_ascii=False)
    except Exception as e:
        return json.dumps({'ok': False, 'err': str(e)}, ensure_ascii=False)


def stop_stream():
    try:
        _zag._STREAM_STOP[0] = True
        return json.dumps({'ok': True}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({'ok': False, 'err': str(e)}, ensure_ascii=False)



# ---------------- analyze action ----------------
def analyze_action(action, arg):
	'''Kotlin 直接调用的结构化接口。'''
	import json as _j
	try:
		import rea_core as _rea
	except Exception as e:
		return _j.dumps({'ok': False, 'err': 'import rea_core 失败: ' + repr(e)}, ensure_ascii=False)
	try:
		if action == 'open':
			return _j.dumps({'ok': True, 'target': arg, 'type': _rea.detect_type(arg)}, ensure_ascii=False)
		if action == 'run':
			sid, tt, ids = _rea.analyze(arg)
			ev = _rea.Evidence(sid)
			items = []
			for eid in ids:
				o = ev.show(eid)
				items.append({'id': o['id'], 'type': o['type'],
					'backend': o['backend'], 'confidence': o['confidence'],
					'limitations': o.get('limitations', []),
					'content_len': len(o.get('content', ''))})
			return _j.dumps({'ok': True, 'sid': sid, 'type': tt,
				'ids': ids, 'items': items}, ensure_ascii=False)
		if action == 'sessions':
			return _j.dumps({'ok': True, 'sessions': _rea.list_sessions()}, ensure_ascii=False)
		if action == 'latest':
			return _j.dumps({'ok': True, 'sid': _rea.latest_session()}, ensure_ascii=False)
		if action == 'evidence':
			ev = _rea.Evidence(arg)
			names = [f.name for f in ev.ls()]
			items = []
			for n in names:
				eid = n.replace('.json', '')
				o = ev.show(eid)
				items.append({'id': o['id'], 'type': o['type'],
					'backend': o['backend'], 'confidence': o['confidence'],
					'content_len': len(o.get('content', ''))})
			return _j.dumps({'ok': True, 'sid': arg, 'items': items}, ensure_ascii=False)
		if action == 'show':
			p = _j.loads(arg)
			o = _rea.Evidence(p['sid']).show(p['eid'])
			if len(o.get('content', '')) > 20000:
				o['content'] = o['content'][:20000] + '...(截断)'
			return _j.dumps({'ok': True, 'item': o}, ensure_ascii=False)
		if action == 'report':
			return _j.dumps({'ok': True, 'md': _rea.report(arg)}, ensure_ascii=False)
		if action == 'diff':
			p = _j.loads(arg)
			return _j.dumps({'ok': True, 'diff': _rea.diff_sessions(p['a'], p['b'])}, ensure_ascii=False)
		return _j.dumps({'ok': False, 'err': '未知 action: ' + str(action)}, ensure_ascii=False)
	except Exception as e:
		return _j.dumps({'ok': False, 'err': repr(e)}, ensure_ascii=False)