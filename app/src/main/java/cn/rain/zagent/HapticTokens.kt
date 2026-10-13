package cn.rain.zagent

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

// 触觉三档：轻点 / 成功 / 警告
object HapticTokens {

    // 轻点：发送、切 Plan/Build
    fun tap(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    // 成功：配置保存（轻双击手感）
    fun success(v: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    // 警告：开启自动执行 / 清空会话
    fun warn(v: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            v.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
}

// Reduce Motion：系统减弱动画开关
object MotionPrefs {
    fun reduceMotion(v: View): Boolean {
        return try {
            android.provider.Settings.Global.getFloat(
                v.context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (e: Exception) {
            false
        }
    }
}