// Copyright (c) 2026 Agoni
// This file is part of Z-Agent and is released under the MIT License.
// See the LICENSE file in the project root for full license information.
package cn.rain.zagent

import android.content.res.Resources

/**
 * Central design tokens. All values are dp (or sp for fonts).
 * Named fields resolve to px based on the current display density.
 */
object Dimens {
 @Volatile private var density = 1f
 @Volatile private var scaled = 1f

 @JvmStatic
 fun init(res: Resources) {
 density = res.displayMetrics.density
 scaled = res.displayMetrics.scaledDensity
 }

 @JvmStatic
 fun px(v: Int): Int = (v * density + 0.5f).toInt()

 @JvmStatic
 fun sp(v: Int): Int = (v * scaled + 0.5f).toInt()

 // ---- spacing ----
 val space2 get() = px(2)
 val space4 get() = px(4)
 val space5 get() = px(5)
 val space6 get() = px(6)
 val space8 get() = px(8)
 val space10 get() = px(10)
 val space12 get() = px(12)
 val space14 get() = px(14)
 val space16 get() = px(16)
 val space18 get() = px(18)
 val space20 get() = px(20)
 val space22 get() = px(22)
 val space24 get() = px(24)

 // ---- component sizes ----
 val size30 get() = px(30)
 val size36 get() = px(36)
 val size40 get() = px(40)
 val size52 get() = px(52)
 val size56 get() = px(56)

 // ---- one-off layout dims ----
 val dim150 get() = px(150)
 val dim280 get() = px(280)
 val dim300 get() = px(300)
 val dim360 get() = px(360)
 val dim400 get() = px(400)
 val dim420 get() = px(420)
 val dim440 get() = px(440)
}
