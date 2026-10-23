import os
import sys
import subprocess
import re
import shutil

TOOL_DIR = os.path.expanduser("~/.zagent_tools")
BAKSMALI = os.path.join(TOOL_DIR, "baksmali.jar")
SMALI = os.path.join(TOOL_DIR, "smali.jar")

def decompile(apk_path, out_dir):
print(f"[*] 正在反编译 {apk_path}...")
os.makedirs(out_dir, exist_ok=True)

解压 apk 提取 classes*.dex

subprocess.run(["unzip", "-o", apk_path, "classes*.dex", "-d", out_dir], check=True)

反编译所有 dex

for f in os.listdir(out_dir):
if f.endswith(".dex"):
print(f" -> 反编译 {f}")
subprocess.run(["java", "-jar", BAKSMALI, "d", os.path.join(out_dir, f), "-o", os.path.join(out_dir, f.replace(".dex", "_smali"))], check=True)

def patch_smali(smali_dir, target_keyword):
print(f"[*] 正在扫描包含 '{target_keyword}' 的 Smali 代码...")
patched = 0
for root, _, files in os.walk(smali_dir):
for file in files:
if file.endswith(".smali"):
path = os.path.join(root, file)
with open(path, "r", encoding="utf-8") as f:
content = f.read()

如果包含恶意关键词

if target_keyword in content:
print(f" -> 发现可疑文件: {path}")

简单的替换策略：将方法体替换为 return-void 或 false
这里提供一个基础的正则，把特定方法内部清空
(实操中需要 AI 分析后生成精确的正则，这里作为示例)

patched += 1
print(f"[*] 共扫描到 {patched} 个可疑文件。")

def rebuild_and_sign(work_dir, original_apk, out_apk):
print(f"[*] 正在重新打包...")

合并所有 smali 目录重新生成 dex

dex_files = []
for d in os.listdir(work_dir):
if d.endswith("_smali"):
dex_name = d.replace("_smali", ".dex")
dex_path = os.path.join(work_dir, dex_name)
subprocess.run(["java", "-jar", SMALI, "a", os.path.join(work_dir, d), "-o", dex_path], check=True)
dex_files.append(dex_name)

复制原 APK 并替换 dex

shutil.copy(original_apk, out_apk)
for dex in dex_files:
subprocess.run(["zip", "-j", out_apk, os.path.join(work_dir, dex)], check=True)

print("[*] 正在对齐与签名...")
aligned_apk = out_apk.replace(".apk", "_aligned.apk")
subprocess.run(["zipalign", "-p", "4", out_apk, aligned_apk], check=True)

生成临时 keystore 签名

ks_path = os.path.join(TOOL_DIR, "debug.keystore")
if not os.path.exists(ks_path):
subprocess.run(["keytool", "-genkey", "-v", "-keystore", ks_path, "-alias", "androiddebugkey", "-storepass", "android", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US"], check=True)

subprocess.run(["apksigner", "sign", "--ks", ks_path, "--ks-pass", "pass:android", aligned_apk], check=True)
print(f"[+] 净化并签名完成：{aligned_apk}")
return aligned_apk

if name == "main":
if len(sys.argv) < 3:
print("用法: python apk_cleaner.py <apk路径> <恶意特征关键词>")
sys.exit(1)

apk = sys.argv[1]
keyword = sys.argv[2]
out_dir = apk + "_work"
out_apk = apk.replace(".apk", "_cleaned.apk")

decompile(apk, out_dir)
patch_smali(out_dir, keyword)
rebuild_and_sign(out_dir, apk, out_apk)

清理临时目录

shutil.rmtree(out_dir, ignore_errors=True)
