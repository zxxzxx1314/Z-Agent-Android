package com.example.zagent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**

APK 签名校验：比对当前安装包的签名 SHA-256 与出厂预期值。

预期值来源：local.properties 的 ZAGENT_EXPECTED_SIGNATURE

→ build.gradle buildConfigField → BuildConfig.ZAGENT_EXPECTED_SIGNATURE

若未配置（空字符串），视为开发模式，跳过校验。

用途：防止二次打包 / 篡改重签。
*/
object SignatureCheck {

/** 读取当前 APK 签名 SHA-256（小写十六进制，无分隔）。失败返回 null。 */
fun currentSha256(context: Context): String? = try {
val pm = context.packageManager
val pkg = context.packageName
val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
.signingInfo?.apkContentsSigners
} else {
@Suppress("DEPRECATION")
pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
}
val sig = sigs?.firstOrNull() ?: return null
val md = MessageDigest.getInstance("SHA-256")
md.update(sig.toByteArray())
md.digest().joinToString("") { b -> "%02x".format(b) }
} catch (e: Exception) {
null
}

/** 校验签名。expected 为空 → 跳过（返回 true）。 */
fun verify(context: Context, expected: String): Boolean {
if (expected.isBlank()) return true
val actual = currentSha256(context) ?: return false
return actual.equals(expected.trim(), ignoreCase = true)
}
}
