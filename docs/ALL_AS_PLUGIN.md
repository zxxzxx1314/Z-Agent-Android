
Z-Agent · 一切皆插件重构方案 v2.0

目标：模型只输出一种调用协议，宿主只维护一张动作注册表。
新增能力 = 丢一个插件目录进去，核心代码零改动。

一、现状诊断
1.1 协议层：每种能力一套标签

当前 zagent.py 里存在 6 套平行协议，每套都要走「正则识别 → 剥离 → 确认 → 执行 → 回传」五段代码：

标签	解析函数	执行函数
[script:]...[/script]	_script_blocks / extract_scripts	run_python_code
[plugin:id arg]	PLUGIN_CALL_RE	run_plugin
[evolve:reason]...[/evolve]	_evolution_parts / extract_evolution	apply_evolution
[patch:file]...[/patch]	run_patch_tag	run_patch_tag
[todo]a|b[/todo]	update_todos	update_todos
[contract]...[/contract]	parse_accept_blocks	register_contract

代价：新增一种能力要动 5 处（正则表、提取、剥离、确认、执行），且任何一处漏改就会让「字面量里的标签被误执行」这类问题复现——_literal_spans 的存在本身就是补丁史的证据。

1.2 权限层：动作表与执行器脱节

zcore/gate.py 的 MIN_LEVEL 硬编码了 12 个动作字符串。而实际执行路径分散在 run_python_code / run_plugin / _run_process / run_patch_tag 里，各自判断。新增动作若忘记在 MIN_LEVEL 登记，required_level() 会返回默认值，等于绕过门禁。

1.3 界面层：动作清单在 Kotlin 里又写了一遍

MainActivity.kt 的 QuickBtn("save", "存插件", "saveplugin", ...) 是一份手工维护的动作表；PythonBridge.kt 里又是第三份（插件桥接）。Python 侧加一个动作，两处 Kotlin 都要跟着改。

1.4 插件层：只能被调用，不能自我描述

插件目前只有 @name / @desc 两个头部注记，无法声明：需要什么权限、参数 schema 是什么、要不要弹界面、超时多久、返回什么结构。宿主只能盲调。

二、目标与非目标
目标

单一协议：模型输出 [act:id {json}]，一切能力统一入口。

单一注册表：动作由 manifest 声明，扫描即注册，核心代码不感知具体动作。

权限前移：level / permission 写在 manifest 里，gate 从注册表读，不再维护第二份表。

内置即插件：script.run、file.read 这些原生能力也做成插件包，走同一条管线，享受同等的门禁与审计。

向后兼容：旧标签在过渡期内由适配器翻译成新动作，不破坏已有会话与用户肌肉记忆。

非目标

不重写 zcore/gate.py 的状态机（level / circuit / kill 逻辑保留）。

不动审计链 mind/ 的落盘格式。

不在本次改造中更换进程隔离方案。

三、核心抽象
3.1 插件包格式
text
复制
下载
plugins/<id>/
├── manifest.json      # 动作声明（必需）
├── main.py            # 实现，必须提供 run(args, ctx)
├── prompt.md          # 给模型的说明书（可选）
├── ui.json            # 生成式 UI schema（可选）
└── test.py            # 自检用例（可选）

单文件插件 plugins/<id>.py 保留兼容，按 id + 头部注记合成最小 manifest。

3.2 manifest.json
json
复制
下载
{
  "id": "file.read",
  "name": "读文件",
  "desc": "读取文本文件内容，返回字符串",
  "version": "1.0",
  "level": 0,
  "permission": ["fs.read"],
  "isolation": "inproc",
  "timeout": 15,
  "args": {
    "path":  {"type": "string", "required": true, "desc": "绝对路径"},
    "limit": {"type": "int", "default": 65536}
  },
  "returns": {"ok": "bool", "data": "string", "err": "string"},
  "replaces": ["file_read"],
  "tags": ["core", "fs"]
}

字段约定：

level：0 观察 / 1 建议 / 2 半自动 / 3 全自动，语义与 zcore/gate.py 一致。

isolation：inproc（进程内，快）或 process（子进程隔离，默认）。

replaces：该动作吸收的旧标签或旧插件 id，供兼容层反查。

ui.json 存在时，缺参数会自动向用户弹表单，而不是报错。

3.3 统一调用协议
text
复制
下载
[act:file.read {"path": "/sdcard/a.txt"}]
[act:shell.run {"cmd": "ls -la", "timeout": 30}]
[act:ui.form {"title": "选择目录", "fields": [...]}]

参数体是 JSON 对象；单行内闭合。

省略参数体等价于 {}，由 ui.json 或 args.default 补全。

一次回复可含多个 [act:]，按出现顺序串行执行，任一失败不中断后续（结果逐条回传）。

与旧标签共存期间，[script:] / [plugin:] 由适配器在解析阶段就地转成对应 [act:]，之后的管线完全一致。

3.4 执行管线

新增 zcore/actions.py，六段式：

text
复制
下载
parse    →  从回复文本抽出 [(id, args, span)]
resolve  →  查注册表，未知 id 直接返回 NOT_FOUND（不落到 fallback）
gate     →  level 检查 + circuit / kill 检查
approve  →  L2 以下走 _approval_issue / _approval_consume
execute  →  inproc 或子进程，超时强杀，输出截断
audit    →  写 mind/anchors，记录 {id, args_hash, ok, err, ms}

返回统一结构：

python
复制
下载
{"ok": True, "data": ..., "err": None, "meta": {"id": ..., "ms": ..., "level": ...}}

zagent.py 里现有的 run_python_code / run_plugin / run_patch_tag 全部改成薄壳，内部只剩一句 return actions.execute(id, args)。

3.5 注册表
python
复制
下载
# zcore/actions.py
class Registry:
    def scan(self, plugins_dir, builtin_dir): ...   # 读 manifest，校验，缓存
    def get(self, aid): ...                          # 未知返回 None
    def prompt_block(self): ...                      # 生成【可用动作】段落
    def level_of(self, aid): ...                     # 供 gate 调用

zcore/gate.py 的 MIN_LEVEL 保留为兜底常量，但 required_level() 优先查注册表；注册表里没有的动作一律视为 99（禁止），而不是默认放行。

四、内置动作清单（Phase 1 迁移范围）
id	取代	level	isolation
script.run	[script:]	2	process
script.patch	[patch:]	2	process
plugin.call	[plugin:]	2	process
evolve.apply	[evolve:]	3	process
evolve.rollback	/rollback	3	inproc
file.read	—	0	inproc
file.write	—	2	inproc
file.list	—	0	inproc
file.find	—	0	inproc
file.replace	—	2	inproc
shell.run	_run_process	2	process
http.get	fetch_page	1	process
search.web	_parse_search	1	process
todo.set	[todo]	0	inproc
contract.register	[contract]	1	inproc
contract.verify	verify_contract_once	1	process
session.new/switch/export	zagent_app.py	1	inproc
ui.form	生成式 UI	0	inproc
android.battery/notify/toast/vibrate/openUrl/clipboard.*	android_api.py	1	inproc
apk.build	Kotlin apk.build	3	process
web.deploy	server.start	1	inproc

ui.form 是关键：它让插件可以「缺参数就弹表单」，而不用在 main.py 里自己拼 JSON。

五、迁移路线

每步可独立上线，任何一步出问题都能停在原地。

Step 0 · 冻结与基线

make check 跑通，记录当前 preflight.sh / canary / mask 检查结果作为基线。

给 zagent_factory 与 python/ 做一次字节一致性校验，确保出厂基因未被污染。

Step 1 · 引入注册表与管线（双轨）

新增 zcore/actions.py、zcore/action_manifest.py。

新增 assets/zagent_factory/plugins_core/，把上表前半段做成内置插件包，首次启动释放到 ~/plugins/。

build_system_prompt() 追加【可用动作】段，同时保留旧协议说明。

旧标签仍走原路径，新 [act:] 走新路径，互不干扰。

Step 2 · 执行函数薄壳化

run_python_code / run_plugin / run_patch_tag 改成转发到 actions.execute。

删除各自内部的 gate / approval 重复判断，统一由管线负责。

此时旧标签已经事实上走新管线，只是入口不同。

Step 3 · UI 与动作联动

ui.json 存在且缺必填参数时，管线返回 NEED_ARGS，宿主渲染表单，用户提交后用同一 id 重放。

zagent_app.py 的 show_plugin_ui 改为从注册表生成，不再手写组件列表。

Step 4 · Kotlin 侧收敛

PythonBridge.kt 新增 actions_list() 返回注册表 JSON。

MainActivity.kt 的 QuickBtn 表改为启动时拉取注册表动态生成，tags 含 quick 的动作才出现在快捷栏。

PluginActivity 直接展示 manifest 全字段（level / 权限 / 参数）。

Step 5 · 关闭旧协议

适配器保留「读」能力（识别旧标签并转换），但不再提供原生执行分支。

build_system_prompt() 移除旧协议说明，只保留 [act:]。

system prompt 里给一条迁移提示：「旧标签仍可用，但推荐 [act:]」。

六、风险与取舍
风险	影响	对策
子进程启动开销 ~80ms	高频小动作变慢	标 isolation: inproc 的动作走进程内，仅高风险动作隔离
模型旧习惯	继续输出 [script:]	适配器长期保留，转换零成本
manifest 写错导致动作不可用	能力缺失	扫描时校验并降级：非法 manifest 记入 bad_list，不阻断其他动作
注册表与 gate 不一致	门禁失效	注册表未命中一律 level 99，宁可拒绝不可放行
插件目录被外部篡改	提权风险	加载前校验 manifest.id 与目录名一致；~/plugins/ 之外的动作需显式授权

取舍说明：inproc 动作放弃进程隔离，换来延迟。判断标准是「该动作是否可能执行任意用户代码」——script.run、plugin.call、shell.run 必须 process；文件读写、状态查询可以 inproc。

七、验收标准

新增一个动作只改插件目录，zagent.py 与 zcore/ 零改动。

旧标签 100% 有对应 [act:] 映射，转换后行为一致（用现有会话样本回归）。

每个注册动作都有非空 level；未注册 id 返回 NOT_FOUND，不触发任何执行。

单动作执行 p95 < 300ms（inproc）/ < 1.5s（process）。

make check 全绿，canary / mask / preflight 三项无回归。

审计链每条例行记录 {id, args_hash, ok, ms, level}。

八、本次落地文件
text
复制
下载
app/src/main/python/zcore/actions.py          # 注册表 + 六段式管线
app/src/main/python/zcore/action_manifest.py  # manifest 解析与校验
app/src/main/assets/zagent_factory/plugins_core/  # 内置动作插件包
app/src/main/assets/zagent_factory/actions_prompt.md  # 【可用动作】段落模板
tools/check_actions.py                        # 注册表一致性自检
docs/ALL_AS_PLUGIN.md                         # 本文档
九、下一步

方案已定。实际动工从 Step 1 开始：先落 zcore/actions.py 与三个内置动作（file.read / file.write / shell.run），跑通管线，再批量迁移其余动作。
