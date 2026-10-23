# Z-Agent for Android

Android 上的自主 AI Agent。Kotlin 壳 + Chaquopy 内嵌 Python + proot Linux 环境。

架构
text
复制
下载
MainActivity (Kotlin)          界面、流式渲染、会话/设置面板
   |
PythonBridge (Kotlin)          唯一桥接：JVM <-> Python，出厂基因部署
   |
zagent_app.py (Python)         应用侧门面：bootstrap / 状态 / 会话 / 插件 / 流
   |
zagent.py (Python)             核心：三角色协作、验收合同、自我进化、审计链
   |
zcore/  zguard/                检查点、事件、闸门、掩码、引导守卫

另有 LinuxShellBridge (Kotlin) 用 proot + 内置 Termux bootstrap 提供完整
Linux 命令环境，Python 侧通过 android_api.shell_exec() 调用。

详见 docs/ARCHITECTURE.md。

目录
路径	说明
app/src/main/java/cn/rain/zagent/	Kotlin 源码
app/src/main/python/	Python 源码（开发与调试的主位置）
app/src/main/assets/zagent_factory/	出厂基因：python/ 的副本，运行时由 Kotlin 部署到私有目录
app/src/main/assets/linux/	proot + Termux bootstrap（约 33MB）
app/src/main/res/	布局、样式、字符串、尺寸
tools/	构建与一致性校验脚本（canary / mask / sync / preflight）
docs/	架构、构建、贡献、安全文档
backups/	本地备份（布局历史版本 / 旧工程快照），不入版本库
.github/workflows/android-build.yml	CI：Debug + Release 构建与产物上传
构建

CI 走 GitHub Actions，推 main 自动触发。本地构建需要 JDK 17 + Android SDK +
Chaquopy 许可，APK 产物在 app/build/outputs/apk/。

详见 docs/BUILDING.md。

常用命令
bash
复制
下载
make sync     # 同步出厂基因（python/ -> assets/zagent_factory/）
make check    # 提交前自检（canary / mask / 敏感信息）
make debug    # 构建 Debug APK
make release  # 构建 Release APK
出厂基因同步

app/src/main/python/ 改动后必须同步到 app/src/main/assets/zagent_factory/，
否则 APK 运行时部署的仍是旧代码。用 make sync 或 tools/sync_factory.sh 一键同步。

贡献

见 docs/CONTRIBUTING.md。

许可

见 LICENSE。第三方组件见 THIRD_PARTY_NOTICES.md。
