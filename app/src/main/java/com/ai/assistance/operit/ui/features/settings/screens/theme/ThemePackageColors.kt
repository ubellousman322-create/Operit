package com.ai.assistance.operit.ui.features.settings.screens.theme

import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 主题包的颜色加工。
 *
 * 主题包里的 `primary` 往往是给「大色块」挑的浅色，直接当 M3 的 primary 用，
 * 压在白底上当文字色就看不见了（三个包对 #FFFBFE 只有 3.57 / 3.28 / 1.33 : 1，
 * 而正文要求 ≥ 4.5 : 1）。这里按 WCAG 把明度压到达标，色相饱和度原样保留，
 * 保证「浅粉加深之后还是粉」。
 *
 * 算法移植自主题包管理器插件的 colors.js，行为保持一致。
 */
internal object ThemePackageColors {

    private const val LIGHT_SURFACE = 0xFFFFFBFE.toInt()
    private const val DARK_SURFACE = 0xFF1C1B1F.toInt()
    private const val MIN_TEXT_CONTRAST = 4.5

    private data class Rgb(val r: Int, val g: Int, val b: Int)
    private data class Hsl(val h: Double, val s: Double, val l: Double)
    private data class Hsv(val h: Double, val s: Double, val v: Double)

    // ---------------------------------------------------------------- 通道

    private fun clampByte(value: Double): Int {
        val rounded = Math.round(value)
        return when {
            rounded < 0L -> 0
            rounded > 255L -> 255
            else -> rounded.toInt()
        }
    }

    private fun argbToRgb(argb: Int): Rgb =
        Rgb((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)

    private fun rgbToArgb(rgb: Rgb): Int =
        (0xFF shl 24) or
            (clampByte(rgb.r.toDouble()) shl 16) or
            (clampByte(rgb.g.toDouble()) shl 8) or
            clampByte(rgb.b.toDouble())

    private fun channelLuminance(value8: Int): Double {
        val c = value8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(rgb: Rgb): Double =
        0.2126 * channelLuminance(rgb.r) +
            0.7152 * channelLuminance(rgb.g) +
            0.0722 * channelLuminance(rgb.b)

    private fun contrastRatio(a: Rgb, b: Rgb): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = if (la > lb) la else lb
        val lo = if (la > lb) lb else la
        return (hi + 0.05) / (lo + 0.05)
    }

    // ---------------------------------------------------------------- HSL / HSV

    private fun rgbToHsl(rgb: Rgb): Hsl {
        val r = rgb.r / 255.0
        val g = rgb.g / 255.0
        val b = rgb.b / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val lightness = (hi + lo) / 2
        if (hi == lo) return Hsl(0.0, 0.0, lightness)
        val delta = hi - lo
        val saturation = if (lightness > 0.5) delta / (2 - hi - lo) else delta / (hi + lo)
        val hue =
            when (hi) {
                r -> (g - b) / delta + (if (g < b) 6 else 0)
                g -> (b - r) / delta + 2
                else -> (r - g) / delta + 4
            } * 60
        return Hsl(hue, saturation, lightness)
    }

    private fun hueToChannel(p: Double, q: Double, tIn: Double): Double {
        var t = tIn
        if (t < 0) t += 1
        if (t > 1) t -= 1
        if (t < 1.0 / 6) return p + (q - p) * 6 * t
        if (t < 1.0 / 2) return q
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6
        return p
    }

    private fun hslToRgb(hsl: Hsl): Rgb {
        val hue = ((hsl.h % 360) + 360) % 360
        val saturation = hsl.s.coerceIn(0.0, 1.0)
        val lightness = hsl.l.coerceIn(0.0, 1.0)
        if (saturation == 0.0) {
            val gray = lightness * 255
            return Rgb(clampByte(gray), clampByte(gray), clampByte(gray))
        }
        val q =
            if (lightness < 0.5) lightness * (1 + saturation)
            else lightness + saturation - lightness * saturation
        val p = 2 * lightness - q
        return Rgb(
            clampByte(hueToChannel(p, q, hue / 360 + 1.0 / 3) * 255),
            clampByte(hueToChannel(p, q, hue / 360) * 255),
            clampByte(hueToChannel(p, q, hue / 360 - 1.0 / 3) * 255),
        )
    }

    private fun rgbToHsv(rgb: Rgb): Hsv {
        val r = rgb.r / 255.0
        val g = rgb.g / 255.0
        val b = rgb.b / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val delta = hi - lo
        var hue = 0.0
        if (delta > 0) {
            hue =
                when (hi) {
                    r -> ((g - b) / delta) % 6
                    g -> (b - r) / delta + 2
                    else -> (r - g) / delta + 4
                } * 60
            if (hue < 0) hue += 360
        }
        return Hsv(hue, if (hi == 0.0) 0.0 else delta / hi, hi)
    }

    private fun hsvToRgb(hsv: Hsv): Rgb {
        val hue = ((hsv.h % 360) + 360) % 360
        val saturation = hsv.s.coerceIn(0.0, 1.0)
        val value = hsv.v.coerceIn(0.0, 1.0)
        val c = value * saturation
        val x = c * (1 - abs(((hue / 60) % 2) - 1))
        val m = value - c
        val (r, g, b) =
            when {
                hue < 60 -> Triple(c, x, 0.0)
                hue < 120 -> Triple(x, c, 0.0)
                hue < 180 -> Triple(0.0, c, x)
                hue < 240 -> Triple(0.0, x, c)
                hue < 300 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
        return Rgb(
            clampByte((r + m) * 255),
            clampByte((g + m) * 255),
            clampByte((b + m) * 255),
        )
    }

    private fun lighten(rgb: Rgb, factor: Double): Rgb =
        Rgb(
            clampByte(rgb.r + (255 - rgb.r) * factor),
            clampByte(rgb.g + (255 - rgb.g) * factor),
            clampByte(rgb.b + (255 - rgb.b) * factor),
        )

    // ---------------------------------------------------------------- 修正

    /** 浅色模式下把主色压暗到能在表面色上读出来。只下调明度，色相饱和度原样保留。 */
    private fun darkenForLight(color: Rgb, surface: Rgb, minRatio: Double = MIN_TEXT_CONTRAST): Rgb {
        if (contrastRatio(color, surface) >= minRatio) return color
        val hsl = rgbToHsl(color)
        for (step in 1..100) {
            val lightness = hsl.l - step * 0.01
            if (lightness < 0) break
            val candidate = hslToRgb(hsl.copy(l = lightness))
            if (contrastRatio(candidate, surface) >= minRatio) return candidate
        }
        return if (contrastRatio(Rgb(0, 0, 0), surface) >= contrastRatio(Rgb(255, 255, 255), surface)) {
            Rgb(0, 0, 0)
        } else {
            Rgb(255, 255, 255)
        }
    }

    /** 深色模式下宿主会先 lighten 0.2 再上屏，验收基准是上屏色。挑离原色最近的达标明度。 */
    private fun adjustForDark(color: Rgb, surface: Rgb, minRatio: Double = MIN_TEXT_CONTRAST): Rgb {
        if (contrastRatio(lighten(color, 0.2), surface) >= minRatio) return color
        val hsl = rgbToHsl(color)
        var bestLightness = -1.0
        var bestDistance = Double.MAX_VALUE
        for (step in 0..100) {
            val lightness = step / 100.0
            val candidate = hslToRgb(hsl.copy(l = lightness))
            if (contrastRatio(lighten(candidate, 0.2), surface) < minRatio) continue
            val distance = abs(lightness - hsl.l)
            if (distance < bestDistance) {
                bestDistance = distance
                bestLightness = lightness
            }
        }
        if (bestLightness >= 0) return hslToRgb(hsl.copy(l = bestLightness))
        return Rgb(255, 255, 255)
    }

    /** 返回修正后的 (主色, 副色)。 */
    fun resolveReadableColors(primaryArgb: Int, secondaryArgb: Int, dark: Boolean): Pair<Int, Int> {
        val surface = argbToRgb(if (dark) DARK_SURFACE else LIGHT_SURFACE)
        val primaryRgb = argbToRgb(primaryArgb)
        val secondaryRgb = argbToRgb(secondaryArgb)
        val safePrimary =
            if (dark) adjustForDark(primaryRgb, surface) else darkenForLight(primaryRgb, surface)
        val safeSecondary =
            if (dark) adjustForDark(secondaryRgb, surface) else darkenForLight(secondaryRgb, surface)
        return rgbToArgb(safePrimary) to rgbToArgb(safeSecondary)
    }

    /** 副色：主色基础上转一点色相、稍亮一点。 */
    fun deriveSecondary(primaryArgb: Int): Int {
        val hsv = rgbToHsv(argbToRgb(primaryArgb))
        return rgbToArgb(
            hsvToRgb(
                Hsv(
                    h = (hsv.h + 22) % 360,
                    s = max(0.18, hsv.s * 0.82),
                    v = min(0.92, hsv.v * 1.12),
                ),
            ),
        )
    }

    /** 侧边栏底色：保留主色相、只压明度，浅色近乎白的一层薄色，深色是暗调。 */
    fun deriveSidebarColor(primaryArgb: Int, dark: Boolean): Int {
        val hsl = rgbToHsl(argbToRgb(primaryArgb))
        return rgbToArgb(
            hslToRgb(
                Hsl(
                    h = hsl.h,
                    s = min(hsl.s, 0.38),
                    l = if (dark) 0.165 else 0.945,
                ),
            ),
        )
    }

    /**
     * 背景图顶部 18% 是亮底还是暗底。顶栏透明时标题图标直接压在图上，
     * 颜色得按图走，不能按主色走。拿不到图返回 null。
     */
    fun backgroundIsLight(path: String): Boolean? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sample = 1
        while (width / sample > 24) sample *= 2
        val bitmap =
            runCatching {
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                .getOrNull() ?: return null
        return try {
            val topRows = max(1, (bitmap.height * 0.18).toInt())
            var total = 0.0
            var count = 0
            for (y in 0 until topRows) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if ((pixel ushr 24) and 0xFF < 8) continue
                    total += relativeLuminance(argbToRgb(pixel))
                    count++
                }
            }
            if (count == 0) null else total / count >= 0.5
        } finally {
            bitmap.recycle()
        }
    }
}