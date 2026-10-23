package cn.rain.zagent

import androidx.dynamicanimation.animation.SpringForce

/** 动效 token 统一出口：参数与 res/values/motion.xml 保持一致。 */
object MotionTokens {

    // spring 阻尼比 / 刚度
    const val DAMP_APPEAR = 0.85f
    const val STIFF_APPEAR = 400f
    const val DAMP_SHEET = 1.0f
    const val STIFF_SHEET = 250f
    const val DAMP_SEG = 0.8f
    const val STIFF_SEG = 500f
    const val DAMP_PRESS = 0.6f
    const val STIFF_PRESS = 800f

    // 时长
    const val FADE_MS = 150L
    const val PRESS_MS = 80L
    const val OPEN_MS = 250L
    const val CLOSE_MS = 170L
    const val STAGGER_MS = 35L
    const val STAGGER_CAP_MS = 300L
    const val SCRIM_LEAD_MS = 30L

    // 缩放
    const val PRESS_SCALE = 0.92f

    fun appear(): SpringForce = SpringForce().apply {
        dampingRatio = DAMP_APPEAR
        stiffness = STIFF_APPEAR
    }

    fun sheet(): SpringForce = SpringForce().apply {
        dampingRatio = DAMP_SHEET
        stiffness = STIFF_SHEET
    }

    fun seg(): SpringForce = SpringForce().apply {
        dampingRatio = DAMP_SEG
        stiffness = STIFF_SEG
    }

    fun press(): SpringForce = SpringForce().apply {
        dampingRatio = DAMP_PRESS
        stiffness = STIFF_PRESS
    }
}