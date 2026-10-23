package cn.rain.zagent

// 截图窗口三态决策。
//
// CAPTURE: 可以截这个窗口
// EXCLUDE: 明确不截（本应用的无障碍浮层、用户显式排除的包）
// BLOCK_UNKNOWN: 有未解析的窗口混入，无法判定该不该截，宁可整体放弃
//
// 纯函数，不依赖 Android SDK；调用方负责把 AccessibilityWindowInfo 的字段喂进来。
object ScreenshotWindowPolicy {

    enum class Decision { CAPTURE, EXCLUDE, BLOCK_UNKNOWN }

    fun decide(
        isAccessibilityOverlay: Boolean,
        isApplicationWindow: Boolean,
        active: Boolean,
        focused: Boolean,
        resolvedPackage: String?,
        ownPackage: String,
        excludedPackages: Set<String>,
    ): Decision {
        // 本应用的无障碍浮层永远不截，否则截到的是自己。
        if (isAccessibilityOverlay && resolvedPackage == ownPackage) {
            return Decision.EXCLUDE
        }
        // 用户显式排除的包（如语音浮层、输入法候选条）不截。
        if (!resolvedPackage.isNullOrBlank() && excludedPackages.contains(resolvedPackage)) {
            return Decision.EXCLUDE
        }
        // 配置了排除列表，但某个可见窗口解析不出包名：无法判断它是不是被排除的那个，
        // 宁可整体放弃也不能截错。
        if (excludedPackages.isNotEmpty() && resolvedPackage.isNullOrBlank()
            && (isApplicationWindow || active || focused)) {
            return Decision.BLOCK_UNKNOWN
        }
        return Decision.CAPTURE
    }
}
