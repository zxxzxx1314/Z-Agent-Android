package cn.rain.zagent

import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

// View 体系的 spring 动效工具（顶层函数，同包直接可见）

// 通用 spring 动效。可打断——连续调用会从当前速度继续。
fun <T : View> springTo(
    view: T,
    property: FloatPropertyCompat<T>,
    target: Float,
    force: SpringForce,
    startVelocity: Float = 0f
): SpringAnimation {
    val anim = SpringAnimation(view, property, target)
    anim.spring = force
    if (startVelocity != 0f) anim.setStartVelocity(startVelocity)
    anim.start()
    return anim
}

fun View.springY(target: Float, force: SpringForce, velocity: Float = 0f) =
    springTo(this, SpringAnimation.TRANSLATION_Y, target, force, velocity)

fun View.springX(target: Float, force: SpringForce, velocity: Float = 0f) =
    springTo(this, SpringAnimation.TRANSLATION_X, target, force, velocity)

fun View.springScaleX(target: Float, force: SpringForce, velocity: Float = 0f) =
    springTo(this, SpringAnimation.SCALE_X, target, force, velocity)

fun View.springScaleY(target: Float, force: SpringForce, velocity: Float = 0f) =
    springTo(this, SpringAnimation.SCALE_Y, target, force, velocity)

// 等比缩放（X 驱动，Y 跟随）
fun View.springScale(target: Float, force: SpringForce) {
    val a = SpringAnimation(this, SpringAnimation.SCALE_X)
    a.spring = force
    a.addUpdateListener { _, v, _ -> this.scaleY = v }
    a.animateToFinalPosition(target)
}

// 出现动画：位移 spring + 透明度 tween
fun View.appearIn(distanceDp: Int = 14) {
    val d = distanceDp * resources.displayMetrics.density
    translationY = d
    alpha = 0f
    springY(0f, MotionTokens.appear())
    animate()
        .alpha(1f)
        .setDuration(MotionTokens.FADE_MS)
        .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
        .start()
}

// 按下缩放：按下 tween(80ms 到 0.92)，松手 spring 回 1
fun View.pressScale() {
    if (getTag(R.id.tag_press_scale) == true) return
    setTag(R.id.tag_press_scale, true)
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                v.animate().scaleX(MotionTokens.PRESS_SCALE).scaleY(MotionTokens.PRESS_SCALE)
                    .setDuration(MotionTokens.PRESS_MS)
                    .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.springScale(1f, MotionTokens.press())
            }
        }
        false
    }
}