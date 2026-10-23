package cn.rain.zagent

import kotlin.jvm.functions.Function0

/**
 * 日志分级封装（task 20）。
 *
 * 设计约束：
 * 1. d() / i() 只在 debug 构建里生效；Release 构建里 R8 会按
 * proguard-rules.pro 里的 -assumenosideeffects 规则把整个调用删除，
 * 连 lambda 都不会执行。因此 d() / i() 的 lambda 里禁止写有副作用的代码。
 * 2. w() / e() 任何构建都保留——错误必须上报，不能静默。
 */
object Log {
    private const val TAG = "ZAgent"

    @JvmStatic
    fun d(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, msg)
    }

    @JvmStatic
    fun d(block: Function0<Any?>) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, block()?.toString() ?: "null")
    }

    @JvmStatic
    fun i(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.i(TAG, msg)
    }

    @JvmStatic
    fun i(block: Function0<Any?>) {
        if (BuildConfig.DEBUG) android.util.Log.i(TAG, block()?.toString() ?: "null")
    }

    @JvmStatic
    fun w(msg: String) {
        android.util.Log.w(TAG, msg)
    }

    @JvmStatic
    fun w(block: Function0<Any?>) {
        android.util.Log.w(TAG, block()?.toString() ?: "null")
    }

    @JvmStatic
    fun e(msg: String) {
        android.util.Log.e(TAG, msg)
    }

    @JvmStatic
    fun e(msg: String, tr: Throwable?) {
        android.util.Log.e(TAG, msg, tr)
    }

    @JvmStatic
    fun e(block: Function0<Any?>) {
        android.util.Log.e(TAG, block()?.toString() ?: "null")
    }
}
