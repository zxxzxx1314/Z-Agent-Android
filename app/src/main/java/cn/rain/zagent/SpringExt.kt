package cn.rain.zagent

import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

// View 体系的 spring 动效工具（顶层函数，同包直接可见）
// 全部支持 Reduce Motion：系统开启时退化为 100ms tween
//
// 注意：SpringAnimation(view, prop, target) 三参构造后再 setSpring(force) 会
// 用无参 SpringForce（finalPosition=NaN/MAX）覆盖 internal force，
// start() 里 sanityCheck 会因 final position 越界抛 UnsupportedOperationException。
// 正确姿势：两参构造 → spring = force → animateToFinalPosition(target)。

const val REDUCE_MS = 100L

fun <T : View> springTo(
view: T,
property: FloatPropertyCompat<T>,
target: Float,
force: SpringForce,
startVelocity: Float = 0f
): SpringAnimation? {
if (MotionPrefs.reduceMotion(view)) return null
val anim = SpringAnimation(view, property)
anim.spring = force
if (startVelocity != 0f) anim.setStartVelocity(startVelocity)
anim.animateToFinalPosition(target)
return anim
}

fun View.springY(target: Float, force: SpringForce, velocity: Float = 0f) {
if (MotionPrefs.reduceMotion(this)) {
animate().translationY(target).setDuration(REDUCE_MS).start()
return
}
springTo(this, SpringAnimation.TRANSLATION_Y, target, force, velocity)
}

fun View.springX(target: Float, force: SpringForce, velocity: Float = 0f) {
if (MotionPrefs.reduceMotion(this)) {
animate().translationX(target).setDuration(REDUCE_MS).start()
return
}
springTo(this, SpringAnimation.TRANSLATION_X, target, force, velocity)
}

fun View.springScale(target: Float, force: SpringForce) {
if (MotionPrefs.reduceMotion(this)) {
scaleX = target; scaleY = target
return
}
val a = SpringAnimation(this, SpringAnimation.SCALE_X)
a.spring = force
a.addUpdateListener { _, v, _ -> this.scaleY = v }
a.animateToFinalPosition(target)
}

// 出现动画：位移 spring + 透明度 tween
fun View.appearIn(distanceDp: Int = 14) {
if (MotionPrefs.reduceMotion(this)) {
alpha = 1f
translationY = 0f
return
}
val d = distanceDp * resources.displayMetrics.density
translationY = d
alpha = 0f
springY(0f, MotionTokens.appear())
animate().alpha(1f).setDuration(MotionTokens.FADE_MS)
.setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f)).start()
}

// 按下缩放：reduce motion 时完全跳过
fun View.pressScale() {
if (getTag(R.id.tag_press_scale) == true) return
setTag(R.id.tag_press_scale, true)
setOnTouchListener { v, ev ->
if (MotionPrefs.reduceMotion(v)) return@setOnTouchListener false
when (ev.actionMasked) {
MotionEvent.ACTION_DOWN -> {
v.animate().cancel()
v.animate().scaleX(MotionTokens.PRESS_SCALE).scaleY(MotionTokens.PRESS_SCALE)
.setDuration(MotionTokens.PRESS_MS)
.setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f)).start()
}
MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
v.springScale(1f, MotionTokens.press())
}
}
false
}
}
// ─────────── 高层动画（第二批：错误/完成/列表/思考） ───────────

// 错误抖动：Keyframe 左右衰减（一次 420ms）
fun View.animateShake() {
if (MotionPrefs.reduceMotion(this)) return
val d = resources.displayMetrics.density
val amp = 10f * d
val kfs = arrayOf(
android.animation.Keyframe.ofFloat(0f, 0f),
android.animation.Keyframe.ofFloat(0.15f, amp),
android.animation.Keyframe.ofFloat(0.30f, -amp * 0.8f),
android.animation.Keyframe.ofFloat(0.45f, amp * 0.6f),
android.animation.Keyframe.ofFloat(0.60f, -amp * 0.4f),
android.animation.Keyframe.ofFloat(0.75f, amp * 0.25f),
android.animation.Keyframe.ofFloat(1f, 0f)
)
val pvh = android.animation.PropertyValuesHolder.ofKeyframe(
View.TRANSLATION_X, *kfs)
val a = android.animation.ObjectAnimator.ofPropertyValuesHolder(this, pvh)
a.duration = 420
a.interpolator = PathInterpolator(0.36f, 0f, 0.66f, -0.56f)
a.start()
}

// 错误卡片：红晕闪烁 + 抖动
fun View.animateErrorCard() {
if (MotionPrefs.reduceMotion(this)) return
animateShake()
val orig = background
val halo = android.graphics.drawable.ColorDrawable(0x40FF4444.toInt())
val sl = android.graphics.drawable.LayerDrawable(arrayOf(halo, orig))
background = sl
val a = android.animation.ObjectAnimator.ofInt(halo, "alpha", 0, 160, 0)
a.duration = 700
a.addListener(object : android.animation.AnimatorListenerAdapter() {
override fun onAnimationEnd(anim: android.animation.Animator) {
background = orig
}
})
a.start()
}

// 完成：中心放大+轻微弹跳（缩放 spring + 淡入）
fun View.animateFinish() {
if (MotionPrefs.reduceMotion(this)) {
alpha = 1f; scaleX = 1f; scaleY = 1f
return
}
alpha = 0f
scaleX = 0.5f; scaleY = 0.5f
animate().alpha(1f).scaleX(1.06f).scaleY(1.06f).setDuration(160)
.setInterpolator(PathInterpolator(0.34f, 1.2f, 0.64f, 1f)).start()
postDelayed({
springScale(1f, MotionTokens.appear())
}, 160)
}

// 列表错开入场：对子 View 逐个 appearIn（默认 35ms 步进、封顶 300ms）
fun View.animateStaggerIn(startDelayMs: Long = 0L) {
val vg = this as? android.view.ViewGroup ?: return
val n = vg.childCount
for (i in 0 until n) {
val c = vg.getChildAt(i) ?: continue
val step = minOf(i.toLong() * MotionTokens.STAGGER_MS, MotionTokens.STAGGER_CAP_MS)
val delay = startDelayMs + step
c.alpha = 0f
c.translationY = 14f * resources.displayMetrics.density
c.postDelayed({
c.springY(0f, MotionTokens.appear())
c.animate().alpha(1f).setDuration(MotionTokens.FADE_MS)
.setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f)).start()
}, delay)
}
}

// 思考中：一次呼吸（0.4 → 1.0）1.2s 一拍
fun View.thinkingPulseOnce() {
if (MotionPrefs.reduceMotion(this)) return
animate().cancel()
alpha = 1f
animate().alpha(0.45f).setDuration(420)
.setInterpolator(PathInterpolator(0.4f, 0f, 0.6f, 1f))
.withEndAction {
animate().alpha(1f).setDuration(420)
.setInterpolator(PathInterpolator(0.4f, 0f, 0.6f, 1f)).start()
}.start()
}
