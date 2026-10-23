#!/data/data/com.termux/files/usr/bin/bash

安装 Java 环境及 APK 处理工具

pkg install openjdk-17 wget unzip -y

WORK_DIR="
𝐻
𝑂
𝑀
𝐸
/
.
𝑧
𝑎
𝑔
𝑒
𝑛
𝑡
𝑡
𝑜
𝑜
𝑙
𝑠
"
𝑚
𝑘
𝑑
𝑖
𝑟
−
𝑝
"
HOME/.zagent
t
	​

ools"mkdir−p"WORK_DIR"
cd "$WORK_DIR"

腾讯 Maven 镜像

MAVEN="https://mirrors.cloud.tencent.com/nexus/repository/maven-public"

if [ ! -f "baksmali.jar" ]; then
echo "Downloading baksmali..."
wget -q "$MAVEN/com/android/tools/smali/baksmali/2.5.2/baksmali-2.5.2.jar" -O baksmali.jar
fi

if [ ! -f "smali.jar" ]; then
echo "Downloading smali..."
wget -q "$MAVEN/com/android/tools/smali/smali/2.5.2/smali-2.5.2.jar" -O smali.jar
fi

获取 apksigner 和 zipalign (通常包含在 build-tools 中，这里用备用的 Maven 仓库源，或者直接使用 Termux 的 apksigner)

pkg install apksigner zipalign -y

echo "工具链配置完成！"
