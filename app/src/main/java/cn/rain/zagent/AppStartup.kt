package cn.rain.zagent

import android.os.SystemClock

// 冷启动埋点：Application.onCreate 起点 -> MainActivity.onResume 终点
object AppStartup {
    @Volatile var t0: Long = 0L
    @Volatile var reported: Boolean = false
    fun mark() { t0 = SystemClock.uptimeMillis() }
    fun elapsed(): Long = if (t0 == 0L) -1L else SystemClock.uptimeMillis() - t0
}