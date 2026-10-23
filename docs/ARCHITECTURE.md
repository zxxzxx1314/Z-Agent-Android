# 架构

text
复制
下载
MainActivity (Kotlin)            界面、流式渲染、会话/设置面板
   |
PythonBridge (Kotlin)            唯一桥接：JVM ⇄ Python，出厂基因部署
   |
zagent_app.py (Python)           应用侧门面：bootstrap / 状态 / 会话 / 插件 / 流
   |
zagent.py (Python)               核心：三角色协作、验收合同、自我进化、审计链
   |
zcore/  zguard/                  检查点、事件、闸门、掩码、引导守卫

Linux 命令环境由 LinuxShellBridge.kt 使用 proot + 内置 Termux bootstrap 提供，
Python 侧通过 android_api.shell_exec() 调用。

层次职责
层	职责	不应做什么
Kotlin UI	渲染、输入、动效、崩溃兜底	不直接执行业务逻辑
PythonBridge	生命周期、部署 assets、方法映射	不做业务决策
zagent_app	门面、会话状态、插件调度	不依赖 Android 类
zagent	核心编排、验收、审计	不做 UI
zcore	检查点、事件、闸门等基础组件	不感知业务
zguard	启动守卫、阴影对照	不感知业务
关键约束

assets/zagent_factory/ 必须与 python/ 字节一致（见 tools/sync_factory.sh）。

MainActivity.kt 主界面是本轮改动热点，动效委托 MotionTokens / SpringExt。

CrashApp / CrashActivity 提供崩溃兜底，不可移除。

SignatureCheck 用于防重打包，签名配在 local.properties。
