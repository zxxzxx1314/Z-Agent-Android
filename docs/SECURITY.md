# 安全策略

报告漏洞

不要用公开 issue 报安全问题。发邮件给维护者并在标题加 [SECURITY]。

密钥与凭据

local.properties、tk.txt、*.keystore、*.jks、keystore.properties 均在 .gitignore。

gradle.properties 不含任何密钥。

CI 用 GitHub Secrets 注入 ZAGENT_API_KEY 与 ZAGENT_EXPECTED_SIGNATURE。

签名校验

SignatureCheck.kt 在运行时校验 APK 签名，防止重打包与二次分发。
正式分发包请在 local.properties 配 ZAGENT_EXPECTED_SIGNATURE。

依赖

Chaquopy（Python 运行时）随 APK 打包，不联网下载。

Python 侧 pip 依赖固定版本（见 app/build.gradle 的 chaquopy.pip.install）。

Kotlin/AndroidX 依赖由 Gradle 从 google() / mavenCentral() 拉取。
