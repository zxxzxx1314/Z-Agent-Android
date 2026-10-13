package cn.rain.zagent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// API Key 加密存储（EncryptedSharedPreferences / AES256）
object SecurePrefs {
private const val FILE_NAME = "zagent_secure"
private const val KEY_API = "api_key"

@Volatile private var cached: SharedPreferences? = null

private fun open(ctx: Context): SharedPreferences {
cached?.let { return it }
synchronized(this) {
cached?.let { return it }
val mk = MasterKey.Builder(ctx.applicationContext)
.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
.build()
val p = EncryptedSharedPreferences.create(
ctx.applicationContext,
FILE_NAME,
mk,
EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
cached = p
return p
}
}

fun getApiKey(ctx: Context): String? = try {
open(ctx).getString(KEY_API, null)
} catch (e: Exception) { null }

fun putApiKey(ctx: Context, key: String) {
try { open(ctx).edit().putString(KEY_API, key).apply() } catch (e: Exception) { }
}
}
