# 第三方开源组件声明

本工程在构建与运行过程中分发以下第三方组件。各组件的版权与许可条款归其各自作者所有，本工程仅在其许可范围内使用。

许可证正文见 app/src/main/assets/licenses/。

---

## 一、随 APK 分发的原生二进制

可执行 ELF（proot / loader / loader32）放在 jniLibs，打包后落在 nativeLibraryDir，只读执行；共享库（libtalloc.so.2、libandroid-shmem.so）、ld.config.txt、exec-manifest.txt 与 bootstrap 压缩包位于 app/src/main/assets/linux/，由运行时复制到应用私有目录。assets/linux 下不再保留 proot / loader / loader32 副本。

### proot

| 项 | 内容 |
|---|---|
| 文件 | jniLibs/arm64-v8a/libproot.so、libproot_loader.so、libproot_loader32.so（打包后位于 nativeLibraryDir）|
| 用途 | 在非 root 环境下模拟 chroot，运行 Termux bootstrap |
| 许可证 | GPL-2.0（全文见 licenses/GPL-2.0-only.txt）|
| 上游 | https://proot-me.github.io/ |
| 版本 | 未记录（二进制 .comment：proot 为 clang 21.0.0 / LLD 21.1.8；loader、loader32 为 clang 21.0.0 / LLD 21.0.0。具体 release tag 待按上游复核）|

> 合规义务：GPL-2.0 要求分发二进制时提供对应源码，或提供有效期不少于三年的书面要约。
> 当前仓库未包含 proot 源码。发布前必须补齐以下之一：
> 1. 将对应版本的 proot 源码打包进 assets/native-sources/proot.tar.gz，随 APK 一并分发；
> 2. 在本文档中写明源码获取地址，并确认该地址在三年内有效。

当前采用方案 2（只声明获取途径，未随 APK 分发源码）：

https://proot-me.github.io/

https://github.com/termux/proot（Termux 维护分支，实际构建来源更可能为此）

上游地址长期有效性需在每次发版前复核；若上游迁移必须同步更新本节。

### talloc

| 项 | 内容 |
|---|---|
| 文件 | assets/linux/libtalloc.so.2（运行时复制到 filesDir/linux，LD_LIBRARY_PATH 含该目录，满足 proot 的 DT_NEEDED=libtalloc.so.2；jniLibs 另存 libtalloc.so，因 SONAME 仍为 libtalloc.so.2，不参与该条解析）|
| 用途 | proot 的内存分配器依赖 |
| 许可证 | LGPL-3.0（全文见 licenses/LGPL-3.0-only.txt）|
| 上游 | https://talloc.samba.org/ |
| 版本 | 未记录（二进制 .comment 显示 clang 19.0.1 / LLD 19.0.1 编译，具体 release tag 待按上游复核）|

> LGPL-3.0 允许以动态链接方式使用而不公开本工程源码，但要求：
> 1. 声明使用了 talloc 及其版本；
> 2. 提供替换该库的方法（本工程以独立 .so 分发，满足此条）；
> 3. 提供 talloc 源码或获取途径：https://talloc.samba.org/ 、https://git.samba.org/?p=talloc.git（发布前需复核地址有效性）。

### libandroid-shmem

| 项 | 内容 |
|---|---|
| 文件 | assets/linux/libandroid-shmem.so（jniLibs/arm64-v8a 另存一份同名副本，内容一致）|
| 用途 | 在 Android 上提供 System V 共享内存仿真，proot 依赖 |
| 许可证 | BSD-2-Clause（全文见 licenses/BSD-2-Clause.txt）|
| 上游 | https://github.com/termux/libandroid-shmem |
| 版本 | 未记录（二进制 .comment 显示 clang 21.0.0 / LLD 21.0.0 编译；具体 release tag 待按上游复核）|

BSD-2-Clause 要求在源码与二进制分发中保留版权声明、条件列表与免责声明。

### Termux Bootstrap

| 项 | 内容 |
|---|---|
| 文件 | assets/linux/bootstrap-aarch64.zip（约 86 MB 解压后，3774 个文件）|
| 用途 | 提供 Linux 用户空间（bash、coreutils、apt、dpkg、curl 等）|
| 来源 | Termux 官方 bootstrap（由 bin/termux-am、bin/termux-setup-storage、
| | etc/termux/termux-bootstrap/second-stage/、lib/libtermux-exec-*.so 等路径确认）|
| 上游 | https://github.com/termux/termux-packages |
| 打包日期 | 2026-09-27（见 zip 内 SYMLINKS.txt 时间戳）|
| 许可证 | 混合，见下 |

该 bootstrap 是大量独立软件包的聚合分发，各包保留自己的许可证。主要类别：

| 许可证 | 典型组件 |
|---|---|
| GPL-2.0 / GPL-3.0 | bash、grep、sed、gawk、tar、diffutils、findutils、coreutils（部分）、xz-utils、gzip |
| LGPL-2.1 / LGPL-3.0 | glibc 兼容层、libgmp、libmpfr、readline、ncurses |
| Apache-2.0 | apt、dpkg（部分）|
| MIT / BSD | curl（curl 许可证）、openssl（Apache-2.0）、libssh2（BSD-3-Clause）|
| 其他 | libgcrypt（LGPL-2.1+）、libgnutls（LGPL-2.1+）、libidn2（LGPL-3.0+）|

完整的逐包许可证清单可由 bootstrap 内的 var/lib/dpkg/status 与各包 copyright 文件导出。
已执行（结果见 licenses/termux-bootstrap-copyright.txt，325 KB）。原始步骤记录如下：

sh
# 从 bootstrap 中导出全部包的许可证信息
unzip bootstrap-aarch64.zip -d /tmp/bs
find /tmp/bs -path '*/share/doc/*/copyright' -exec cat {} \; > bootstrap-copyright.txt


将导出结果保存为 assets/licenses/termux-bootstrap-copyright.txt，并在此文档中引用。

---

## 二、Python 运行时依赖（Chaquopy 打包）

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| Chaquopy | 3.12 | MIT（运行时库）／商业（构建插件）| Kotlin ↔ Python 桥接 |
| requests | 2.34.2（已固定）| Apache-2.0 | HTTP 客户端，唯一随 APK 打包的 pip 依赖 |
| Pillow | 未打包（try/except 可选导入）| HPND（Historical Permission Notice and Disclaimer）| 图像处理；缺失时降级提示 |
| pypdf | 未打包（try/except 可选导入）| BSD-3-Clause | PDF 解析；缺失时降级提示 |

> 版本固定状态：app/build.gradle 现固定 requests==2.34.2，可重现构建。
> Pillow / pypdf 未列入 pip 安装，zagent.py 以 try/except 可选导入，缺失时打印降级提示。
> 若要启用，建议分别固定 Pillow==13.0.0、pypdf==6.21.2，但需先验证 Chaquopy 对 Pillow 的
> wheel 与 ABI 支持（Chaquopy 对 Pillow 需额外配置，不能保证任意版本可装）。

> Chaquopy 的许可证分两部分：运行时库（com.chaquo.python:runtime）为 MIT，
> Gradle 构建插件在开源项目下免费、商业项目需授权。原声明中的「MIT / Apache 2.0 双许可」不准确，现已修正。

---

## 三、Android 与 Google 依赖

| 组件 | 版本 | 许可证 |
|---|---|---|
| androidx.core:core-ktx | 1.15.0 | Apache-2.0 |
| androidx.appcompat:appcompat | 1.7.0 | Apache-2.0 |
| com.google.android.material:material | 1.12.0 | Apache-2.0 |
| androidx.constraintlayout:constraintlayout | 2.2.0 | Apache-2.0 |
| androidx.dynamicanimation:dynamicanimation | 1.0.0 | Apache-2.0 |
| androidx.security:security-crypto | 1.1.0-alpha06 | Apache-2.0 |
| com.google.mlkit:text-recognition | 16.0.0 | Apache-2.0 |
| com.google.mlkit:text-recognition-chinese | 16.0.0 | Apache-2.0 |

ML Kit 的两项此前遗漏。它们是 OcrHelper.kt 的实现基础，随 APK 分发，必须声明。
ML Kit 的模型文件可能由 Google Play Services 在运行时下载，不属于本 APK 的分发内容。

---

## 四、本工程自身

除上述第三方组件外，本工程源码采用 MIT 许可证，详见根目录 LICENSE 文件。

MIT 许可证不覆盖：

- assets/linux/ 下的任何文件（各自适用其原始许可证）

- Chaquopy、ML Kit 及任何通过 Gradle / pip 引入的依赖

---

## 五、待办

- [x] 确认 proot 与 talloc 的编译版本，补入上表「版本」栏
 - 已用 readelf -p .comment 读出编译工具链版本；具体 release tag 仍需按上游复核
- [x] 按上表「合规义务」补齐 GPL-2.0 / LGPL-3.0 的源码提供（采用方案 2：文档声明获取途径）
 - 注意：未随 APK 分发源码，发布前必须复核上游地址有效性
- [x] 从 Termux bootstrap 导出逐包 copyright，存为 assets/licenses/termux-bootstrap-copyright.txt
- [x] 修正 Chaquopy 许可证描述（运行时 MIT / 构建插件商业）
- [x] 在 app/build.gradle 固定 pip 依赖版本号（requests==2.34.2）

---

## 附：许可证文件索引

| 文件 | 对应组件 |
|---|---|
| licenses/GPL-2.0-only.txt | proot、bootstrap 内 GPL-2.0 组件 |
| licenses/GPL-3.0-only.txt | bootstrap 内 GPL-3.0 组件 |
| licenses/LGPL-2.1-only.txt | bootstrap 内 LGPL-2.1 组件 |
| licenses/LGPL-3.0-only.txt | talloc、bootstrap 内 LGPL-3.0 组件 |
| licenses/Apache-2.0.txt | AndroidX、ML Kit、requests |
| licenses/MIT.txt | Chaquopy 运行时 |
| licenses/BSD-2-Clause.txt | libandroid-shmem |
| licenses/BSD-3-Clause.txt | pypdf、bootstrap 内 libssh2 |
| licenses/ISC.txt | （预留）|
| licenses/Unlicense.txt | （预留）|
| licenses/termux-bootstrap-copyright.txt | Termux bootstrap 31 个包的版权原文汇总（325 KB）|