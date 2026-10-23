# 构建指南

环境

JDK 17

Android SDK Platform 37

Gradle 8.13（wrapper 已内置）

Chaquopy 16.1.0（Gradle 插件，无需本地安装）

Python 3.12（Chaquopy 内嵌，无需本地 Python）

本地开发与自检还需要：

bash、rsync（make sync 用）

python3、node（make check 用）

Termux 下：pkg install rsync python nodejs

本地构建
bash
复制
下载
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

或：

bash
复制
下载
make debug
make release

产物：app/build/outputs/apk/debug/app-debug.apk / .../release/app-release.apk

签名

Release 签名依赖根目录 local.properties 或环境变量：

RELEASE_STORE_FILE

RELEASE_STORE_PASSWORD

RELEASE_KEY_ALIAS

RELEASE_KEY_PASSWORD

若缺省则回退到 debug 签名（仅供 CI 冒烟测试，正式分发必须配齐）。

出厂基因同步

Python 源码在 app/src/main/python/ 为主目录；app/src/main/assets/zagent_factory/
是运行时从 assets 部署到私有目录的拷贝。改动 python/ 后必须执行：

bash
复制
下载
make sync
# 或 ./tools/sync_factory.sh

否则 APK 实际运行的是旧代码。

CI

推 main 触发 .github/workflows/android-build.yml，产出三份 artifacts：

zagent-debug-apk

zagent-release-apk

r8-mapping

打 tag 且 v 前缀（如 v0.3.3）时，build 成功后自动创建 GitHub Release 并附带 APK。

提交前自检
bash
复制
下载
make check
# 或 ./tools/preflight.sh

会依次执行：canary、mask 静态一致性、mask 运行时 fixture、敏感信息扫描。

canary 与签名指纹

app/src/main/assets/canary.txt 是出厂指纹，SHA-256 必须与
tools/canary_check.py 里的 EXPECTED 常量一致。

文件缺失 → 脚本打印 skip 并放行（例如开源镜像不带指纹）。

文件存在但 SHA 不匹配 → 脚本 FAIL，CI 中止。

发布正式包前，务必确认文件已放入且 EXPECTED 已更新。
