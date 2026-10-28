package com.huigu.phone10.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 一块被雨淋过的玻璃。
 *
 * 画的顺序是：底色渐变 → 几团虚掉的柔光 → 一道斜光带（glare）→ 水膜上的明暗斑
 * → 挂着的水珠。水珠按大小分三种画法：极小的只点一个亮核，中等的加一圈暗边，
 * 够大的才做完整结构——先从左到右取一小块背景、上下翻转、贴进珠子里当折射，
 * 再叠暗边、内亮边、左上高光、下缘月牙。
 *
 * 静态的那一层（背景 + 水膜 + 全部水珠）只在尺寸或配色变化时重画一次，之后每帧
 * 只贴一张图，再加几朵打在玻璃上的水花。水花是唯一在动的东西。
 */
internal class ErpanRainView(context: Context) : View(context) {

    var palette: GlassPalette = GlassPalette.Mist
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private var wDp = 0f
    private var hDp = 0f

    /** 背景单独留一张，是给大珠子做折射取样的。 */
    private var background: Bitmap? = null
    private var still: Bitmap? = null

    private val beads = ArrayList<Bead>()
    private val splashes = ArrayList<Splash>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val clipPath = Path()
    private val src = RectF()
    private val dst = RectF()

    private var seed = SEED
    private val live: Boolean get() = isShown && wDp > 0f

    private fun srand(): Float {
        seed = seed * 1664525 + 1013904223
        return (seed.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }

    private fun srnd(a: Float, b: Float) = a + srand() * (b - a)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        wDp = w / d
        hDp = h / d
        rebuild()
    }

    private fun rebuild() {
        if (wDp <= 0f || hDp <= 0f) return
        background?.recycle()
        still?.recycle()
        background = null
        still = null
        seed = SEED
        paintBackground()
        makeBeads()
        paintStill()
        invalidate()
    }

    // ── 背景 ────────────────────────────────────────────────────────────

    private fun paintBackground() {
        val w = (wDp * d).toInt().coerceAtLeast(1)
        val h = (hDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(d, d)

        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                wDp * 0.5f, -hDp * 0.10f, hDp * 1.05f,
                intArrayOf(palette.g1, palette.g2, palette.g3, palette.g4),
                floatArrayOf(0f, 0.38f, 0.74f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, wDp, hDp, base)

        // 几团很大的柔光，被 blur 糊开，玻璃才有厚薄。
        val blobs = arrayOf(
            floatArrayOf(wDp * 0.22f, hDp * 0.20f, wDp * 0.46f, 0f),
            floatArrayOf(wDp * 0.86f, hDp * 0.34f, wDp * 0.40f, 1f),
            floatArrayOf(wDp * 0.12f, hDp * 0.72f, wDp * 0.48f, 2f),
            floatArrayOf(wDp * 0.66f, hDp * 0.86f, wDp * 0.52f, 0f),
            floatArrayOf(wDp * 0.50f, hDp * 0.48f, wDp * 0.30f, 1f),
        )
        val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(wDp * 0.10f, BlurMaskFilter.Blur.NORMAL)
        }
        for (b in blobs) {
            blobPaint.color = when (b[3].toInt()) {
                0 -> palette.blob1
                1 -> palette.blob2
                else -> palette.blob3
            }
            c.drawCircle(b[0], b[1], b[2], blobPaint)
        }
        blobPaint.maskFilter = null

        // 一道斜着擦过玻璃的高光。
        val glare = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                wDp * 0.10f, 0f, wDp * 0.90f, hDp,
                intArrayOf(0x00FFFFFF, 0x57FFFFFF, 0x1AFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.46f, 0.54f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, wDp, hDp, glare)

        background = bmp
    }

    // ── 水珠 ────────────────────────────────────────────────────────────

    private fun inCaptionZone(x: Float, y: Float) =
        y > hDp * 0.60f && x > wDp * 0.13f && x < wDp * 0.87f

    private fun makeBeads() {
        beads.clear()
        splashes.clear()

        fun push(x: Float, y: Float) {
            // 字幕压着的那块少放一些，字要看得清。
            if (inCaptionZone(x, y) && srand() < 0.55f) return
            val t = srand()
            val r = when {
                t < 0.72f -> srnd(0.50f, 1.35f)
                t < 0.94f -> srnd(1.35f, 2.80f)
                else -> srnd(2.80f, 4.80f)
            }
            val big = r > 2.6f
            beads.add(
                Bead(
                    x = x, y = y,
                    rx = r * (0.82f + srand() * 0.28f),
                    ry = r * (if (big) srnd(1.02f, 1.32f) else srnd(0.90f, 1.12f)),
                    a = if (r < 1.4f) srnd(0.25f, 0.68f) else srnd(0.42f, 0.92f),
                    drop = big
                )
            )
        }

        // ① 底子：铺满整块玻璃，每个角落都有几颗。
        val uniform = (wDp * hDp / 1600f).toInt().coerceIn(40, 4000)
        repeat(uniform) {
            val x = srnd(-wDp * 0.04f, wDp * 1.04f)
            val y = srnd(-hDp * 0.04f, hDp * 1.04f)
            push(x, y)
            // 三成的珠子旁边自然再带一两颗，就成了两三颗的小簇。
            if (srand() < 0.34f) {
                val k = if (srand() < 0.60f) 1 else 2
                repeat(k) { push(x + srnd(-13f, 13f), y + srnd(-13f, 13f)) }
            }
        }

        // ② 再补几处更密的：竖直的串，像水淌过留下的痕迹。
        val clusters = (wDp * hDp / 8000f).toInt().coerceIn(4, 400)
        repeat(clusters) {
            val cx = srnd(-wDp * 0.08f, wDp * 1.08f)
            val cy = srnd(-hDp * 0.08f, hDp * 1.08f)
            val spread = srnd(wDp * 0.008f, wDp * 0.035f)
            val count = srnd(5f, 16f).toInt().coerceAtLeast(1)
            repeat(count) { i ->
                val tx = cx + srnd(-spread * 0.35f, spread * 0.35f)
                val ty = cy + (i.toFloat() / max(1, count - 1) - 0.5f) * spread * 3.4f +
                    srnd(-spread * 0.12f, spread * 0.12f)
                push(tx, ty)
            }
        }
    }

    private fun makeStill() {
        val bmp = Bitmap.createBitmap(
            (wDp * d).toInt().coerceAtLeast(1),
            (hDp * d).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        c.scale(d, d)

        // 一层水膜：极淡的明暗斑，让玻璃看着是湿的而不是干的。
        val film = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(90) {
            val x = srnd(-wDp * 0.05f, wDp * 1.05f)
            val y = srnd(-hDp * 0.05f, hDp * 1.05f)
            val r = srnd(wDp * 0.05f, wDp * 0.20f)
            val bright = srand() < 0.56f
            val tint = if (bright) 0xFFFFFFFF.toInt() else 0xFF587696.toInt()
            val alpha = if (bright) srnd(0.05f, 0.10f) else srnd(0.03f, 0.07f)
            film.shader = RadialGradient(
                x, y, r,
                intArrayOf(withAlpha(tint, alpha), 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(x, y, r, film)
        }
        film.shader = null

        // 小的先画，大的压在上面。
        val ordered = beads.sortedBy { it.rx * it.ry }
        for (b in ordered) paintBead(c, b)

        still = bmp
    }

    private fun paintStill() {
        if (background == null) return
        makeStill()
    }

    private fun beadShape(rx: Float, ry: Float, drop: Boolean, x: Float, y: Float): Path {
        val p = clipPath
        p.reset()
        if (drop && ry > rx * 1.14f) {
            p.moveTo(x, y - ry)
            p.cubicTo(x + rx * 0.54f, y - ry * 0.50f, x + rx, y + ry * 0.30f, x, y + ry)
            p.cubicTo(x - rx, y + ry * 0.30f, x - rx * 0.54f, y - ry * 0.50f, x, y - ry)
        } else {
            p.addOval(RectF(x - rx, y - ry, x + rx, y + ry), Path.Direction.CW)
        }
        p.close()
        return p
    }

    private fun paintBead(c: Canvas, b: Bead) {
        val r = max(b.rx, b.ry)

        // 极小：内部结构根本看不见，一个亮点就够。
        if (r < 1.15f) {
            fill.color = withAlpha(0xFFFFFFFF.toInt(), 0.28f + b.a * 0.42f)
            c.drawCircle(b.x, b.y, r * 1.10f, fill)
            return
        }

        // 小、中：亮核加一圈暗边。
        if (r < 2.6f) {
            fill.color = withAlpha(0xFFFFFFFF.toInt(), 0.18f + b.a * 0.40f)
            c.drawOval(RectF(b.x - b.rx * 0.86f, b.y - b.ry * 0.86f, b.x + b.rx * 0.86f, b.y + b.ry * 0.86f), fill)
            line.color = withAlpha(0xFF486686.toInt(), 0.10f + b.a * 0.22f)
            line.strokeWidth = max(0.5f, r * 0.22f)
            c.drawOval(RectF(b.x - b.rx, b.y - b.ry, b.x + b.rx, b.y + b.ry), line)
            return
        }

        // 大的才有资格做完整结构。
        val bg = background
        if (bg != null) {
            // 水珠是块透镜：里面是倒过来的世界，还放大了一点。
            c.save()
            c.clipPath(beadShape(b.rx, b.ry, b.drop, b.x, b.y))
            c.scale(1f, -1f, b.x, b.y)
            val zoom = 1.55f
            val sw = (b.rx * 2f) / zoom * d
            val sh = (b.ry * 2f) / zoom * d
            if (sw > 0.4f && sh > 0.4f) {
                src.set(b.x * d - sw / 2f, b.y * d - sh / 2f, b.x * d + sw / 2f, b.y * d + sh / 2f)
                dst.set(
                    b.x - b.rx * 1.06f, b.y - b.ry * 1.06f,
                    b.x + b.rx * 1.06f, b.y + b.ry * 1.06f
                )
                runCatching { c.drawBitmap(bg, src, dst, null) }
            }
            c.restore()

            // 叠一层水色，珠子才不是一块贴上去的玻璃。
            c.save()
            c.clipPath(beadShape(b.rx, b.ry, b.drop, b.x, b.y))
            fill.shader = LinearGradient(
                b.x, b.y - b.ry, b.x, b.y + b.ry,
                intArrayOf(
                    withAlpha(0xFFFFFFFF.toInt(), 0.28f * b.a),
                    withAlpha(0xFFD2E4F4.toInt(), 0.08f * b.a),
                    withAlpha(0xFF5A7896.toInt(), 0.20f * b.a)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(b.x - b.rx * 1.2f, b.y - b.ry * 1.2f, b.x + b.rx * 1.2f, b.y + b.ry * 1.2f, fill)
            fill.shader = null
            c.restore()
        }

        // 外暗边
        line.color = withAlpha(0xFF3A526C.toInt(), 0.20f * b.a + 0.08f)
        line.strokeWidth = max(0.7f, r * 0.16f)
        c.drawPath(beadShape(b.rx, b.ry, b.drop, b.x, b.y), line)

        // 内亮边
        line.color = withAlpha(0xFFFFFFFF.toInt(), minOf(1f, 0.30f + b.a * 0.52f))
        line.strokeWidth = max(0.6f, r * 0.12f)
        c.drawPath(
            beadShape(b.rx * 0.82f, b.ry * 0.82f, b.drop && b.ry > b.rx * 1.14f, b.x, b.y),
            line
        )

        // 左上高光
        fill.color = withAlpha(0xFFFFFFFF.toInt(), minOf(1f, 0.42f + b.a * 0.52f))
        val hx = b.x - b.rx * 0.28f
        val hy = b.y - b.ry * 0.36f
        val hrx = max(0.5f, b.rx * 0.26f)
        val hry = max(0.4f, b.ry * 0.17f)
        c.save()
        c.rotate(-18f, hx, hy)
        c.drawOval(RectF(hx - hrx, hy - hry, hx + hrx, hy + hry), fill)
        c.restore()

        // 下缘月牙
        val crescent = Path()
        if (b.drop && b.ry > b.rx * 1.14f) {
            crescent.moveTo(b.x - b.rx * 0.58f, b.y + b.ry * 0.28f)
            crescent.quadTo(b.x, b.y + b.ry * 0.96f, b.x + b.rx * 0.58f, b.y + b.ry * 0.28f)
        } else {
            crescent.addArc(
                RectF(b.x - b.rx * 0.60f, b.y - b.ry * 0.60f, b.x + b.rx * 0.60f, b.y + b.ry * 0.60f),
                43f, 94f
            )
        }
        line.color = withAlpha(0xFFFFFFFF.toInt(), minOf(1f, 0.26f + b.a * 0.46f))
        line.strokeWidth = max(0.6f, r * 0.18f)
        c.drawPath(crescent, line)
    }

    // ── 打在玻璃上的水花 ────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = this.d
        canvas.save()
        canvas.scale(d, d)
        still?.let { canvas.drawBitmap(it, null, dst.set(0f, 0f, wDp, hDp), null) }
        drawSplashes(canvas)
        canvas.restore()
        if (live) postInvalidateOnAnimation()
    }

    private fun drawSplashes(c: Canvas) {
        // 每帧大约 0.9 的概率落一朵，同屏最多 40 朵。
        if (Math.random() < 0.9 && splashes.size < 40) {
            val n = if (Math.random() < 0.45) 2 else 1
            repeat(n) {
                val count = 3 + (Math.random() * 3).toInt()
                val dirs = FloatArray(count * 3)
                for (i in 0 until count) {
                    dirs[i * 3] = (Math.random() * Math.PI * 2).toFloat()
                    dirs[i * 3 + 1] = (2.2 + Math.random() * 3.3).toFloat()
                    dirs[i * 3 + 2] = (0.5 + Math.random() * 0.5).toFloat()
                }
                splashes.add(
                    Splash(
                        x = (Math.random() * wDp).toFloat(),
                        y = (-20 + Math.random() * (hDp * 0.98 + 20)).toFloat(),
                        r = (1.0 + Math.random() * 1.6).toFloat(),
                        dirs = dirs
                    )
                )
            }
        }

        var i = splashes.size - 1
        while (i >= 0) {
            val s = splashes[i]
            s.life -= 0.072f
            s.ring += 2.1f
            if (s.life <= 0f) {
                splashes.removeAt(i)
                i--
                continue
            }
            val l = s.life

            // 被砸的那一下，玻璃上留一小块暗影。
            fill.color = withAlpha(0xFF426080.toInt(), l * 0.13f)
            c.drawCircle(s.x + s.r * 0.3f, s.y + s.r * 0.4f, s.r * l * 1.8f, fill)

            // 溅开的短芒，不是一圈圆环。
            var k = 0
            while (k < s.dirs.size) {
                val a = s.dirs[k]
                val len = s.dirs[k + 1]
                val wid = s.dirs[k + 2]
                val r0 = s.r * 0.55f
                val r1 = r0 + len * l
                line.color = withAlpha(0xFFFFFFFF.toInt(), minOf(1f, l * 1.15f))
                line.strokeWidth = wid
                c.drawLine(
                    s.x + cos(a) * r0, s.y + sin(a) * r0,
                    s.x + cos(a) * r1, s.y + sin(a) * r1,
                    line
                )
                k += 3
            }

            // 中心那滴水
            fill.color = withAlpha(0xFFFFFFFF.toInt(), minOf(1f, l * 1.25f))
            c.drawCircle(s.x, s.y, max(0.5f, s.r * l), fill)

            // 外面一圈很淡的扩散，只是辅助，一眼看不出来。
            if (l > 0.4f) {
                line.color = withAlpha(0xFFFFFFFF.toInt(), (l - 0.4f) * 0.55f)
                line.strokeWidth = 0.7f
                c.drawCircle(s.x, s.y, s.ring * 0.8f, line)
            }
            i--
        }
    }

    /** 页面切走时把两张位图放掉，别一直压着内存。 */
    fun release() {
        background?.recycle()
        still?.recycle()
        background = null
        still = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    /** 被重新挂回来时尺寸没变，onSizeChanged 不会响，得自己把两张图补回来。 */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (still == null && wDp > 0f) {
            rebuild()
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private class Bead(
        val x: Float, val y: Float,
        val rx: Float, val ry: Float,
        val a: Float, val drop: Boolean,
    )

    private class Splash(
        val x: Float, val y: Float, val r: Float,
        var life: Float = 1f,
        var ring: Float = 0f,
        val dirs: FloatArray,
    )

    private companion object {
        /** 固定的种子：每次进通话页都是同一扇窗，不会换一张脸。 */
        const val SEED = 20261101
    }
}

/** 三套玻璃，配色跟预览页一一对应。 */
internal data class GlassPalette(
    val key: String,
    val g1: Int, val g2: Int, val g3: Int, val g4: Int,
    val blob1: Int, val blob2: Int, val blob3: Int,
    val ink: Int, val ink2: Int, val ink3: Int,
    val glow: Int,
) {
    companion object {
        val Mist = GlassPalette(
            key = "mist",
            g1 = 0xFFEEF2F6.toInt(), g2 = 0xFFDBE3EC.toInt(),
            g3 = 0xFFC3CFDD.toInt(), g4 = 0xFFAEBBCB.toInt(),
            blob1 = 0xD9FFFFFF.toInt(), blob2 = 0x4D788EA8, blob3 = 0x73AABED4,
            ink = 0xFF243040.toInt(), ink2 = 0xFF5A6B7E.toInt(), ink3 = 0xFF8296AA.toInt(),
            glow = 0xFFD9749A.toInt(),
        )
        val Cyan = GlassPalette(
            key = "cyan",
            g1 = 0xFFE4EDEE.toInt(), g2 = 0xFFCDDADC.toInt(),
            g3 = 0xFFB2C3C7.toInt(), g4 = 0xFF9CADB4.toInt(),
            blob1 = 0xCCFFFFFF.toInt(), blob2 = 0x52557374, blob3 = 0x73AFB6B7.toInt(),
            ink = 0xFF1C2A30.toInt(), ink2 = 0xFF4F626A.toInt(), ink3 = 0xFF7B8F96.toInt(),
            glow = 0xFFC96F8B.toInt(),
        )
        val Warm = GlassPalette(
            key = "warm",
            g1 = 0xFFF3ECE5.toInt(), g2 = 0xFFE2D8CE.toInt(),
            g3 = 0xFFCBC0B4.toInt(), g4 = 0xFFB6A99B.toInt(),
            blob1 = 0xFFFFFDFA.toInt(), blob2 = 0x47917864, blob3 = 0x73C4AF9B,
            ink = 0xFF2F2721.toInt(), ink2 = 0xFF6B5C50.toInt(), ink3 = 0xFF918173.toInt(),
            glow = 0xFFD4738C.toInt(),
        )

        val All = listOf(Mist, Cyan, Warm)

        fun of(key: String?): GlassPalette = All.firstOrNull { it.key == key } ?: Mist
    }
}
