package com.denis.naturalcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * "Стили изображения" в духе Canon Picture Styles.
 *
 * Честная цветокоррекция без нейросетей: контраст + насыщенность + тёплый/холодный
 * сдвиг каналов + подъём теней. Работает на любом телефоне (декодируем JPEG в Bitmap,
 * прогоняем через ColorMatrix, кодируем обратно).
 *
 * NEUTRAL = без изменений (максимально «как снял сенсор»).
 * Полноценные 3D-LUT (.cube) подключим на этапе GPU-проявки RAW.
 */
object PictureStyleProcessor {

    /** Параметры стиля: [contrast, saturation, rScale, gScale, bScale, lift(0..1)].
     *  Используются и в CPU-обработке JPEG, и в GL-шейдере превью — цвет совпадает. */
    fun params(style: PictureStyle): FloatArray = when (style) {
        PictureStyle.NEUTRAL   -> floatArrayOf(1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.00f)
        PictureStyle.STANDARD  -> floatArrayOf(1.12f, 1.15f, 1.00f, 1.00f, 1.00f, 0.00f)
        PictureStyle.PORTRAIT  -> floatArrayOf(1.05f, 1.04f, 1.06f, 1.00f, 0.93f, 0.00f)
        PictureStyle.LANDSCAPE -> floatArrayOf(1.15f, 1.30f, 1.00f, 1.03f, 1.05f, 0.00f)
        PictureStyle.FILM      -> floatArrayOf(0.90f, 0.85f, 1.05f, 1.00f, 0.92f, 12f / 255f)
    }

    fun apply(
        jpeg: ByteArray, style: PictureStyle,
        lut: Lut3D? = null, lutMix: Float = 1f,
        cropFactor: Float = 1f      // цифровой зум сверх аппаратного: кроп центра, честные пиксели
    ): ByteArray {
        if (style == PictureStyle.NEUTRAL && lut == null && cropFactor <= 1.01f) return jpeg
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        // Кроп центральной области 1/f × 1/f (без апскейла — сохраняем реальное разрешение кропа)
        val src = if (cropFactor > 1.01f) {
            val cw = (decoded.width / cropFactor).toInt().coerceAtLeast(16)
            val ch = (decoded.height / cropFactor).toInt().coerceAtLeast(16)
            val cx = (decoded.width - cw) / 2
            val cy = (decoded.height - ch) / 2
            val c = Bitmap.createBitmap(decoded, cx, cy, cw, ch)
            if (c != decoded) decoded.recycle()
            c
        } else decoded
        return try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint().apply {
                isFilterBitmap = true
                if (style != PictureStyle.NEUTRAL) colorFilter = ColorMatrixColorFilter(buildMatrix(style))
            }
            canvas.drawBitmap(src, 0f, 0f, paint)
            if (lut != null && lutMix > 0f) applyLut(out, lut, lutMix)
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 100, bos)
            out.recycle()
            bos.toByteArray()
        } catch (e: Throwable) {
            jpeg
        } finally {
            src.recycle()
        }
    }

    /** Трилинейная выборка из 3D-LUT для каждого пикселя (та же математика, что в шейдере). */
    private fun applyLut(bmp: Bitmap, lut: Lut3D, mix: Float) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val n = lut.size
        val d = lut.data
        val m = (n - 1).toFloat()

        fun sample(rf: Float, gf: Float, bf: Float, out: FloatArray) {
            val x = (rf * m); val y = (gf * m); val z = (bf * m)
            val x0 = x.toInt().coerceIn(0, n - 1); val x1 = (x0 + 1).coerceAtMost(n - 1)
            val y0 = y.toInt().coerceIn(0, n - 1); val y1 = (y0 + 1).coerceAtMost(n - 1)
            val z0 = z.toInt().coerceIn(0, n - 1); val z1 = (z0 + 1).coerceAtMost(n - 1)
            val fx = x - x0; val fy = y - y0; val fz = z - z0
            out[0] = 0f; out[1] = 0f; out[2] = 0f
            for (c in 0..2) {
                val c000 = d[((z0 * n + y0) * n + x0) * 3 + c]
                val c100 = d[((z0 * n + y0) * n + x1) * 3 + c]
                val c010 = d[((z0 * n + y1) * n + x0) * 3 + c]
                val c110 = d[((z0 * n + y1) * n + x1) * 3 + c]
                val c001 = d[((z1 * n + y0) * n + x0) * 3 + c]
                val c101 = d[((z1 * n + y0) * n + x1) * 3 + c]
                val c011 = d[((z1 * n + y1) * n + x0) * 3 + c]
                val c111 = d[((z1 * n + y1) * n + x1) * 3 + c]
                val c00 = c000 + (c100 - c000) * fx
                val c10 = c010 + (c110 - c010) * fx
                val c01 = c001 + (c101 - c001) * fx
                val c11 = c011 + (c111 - c011) * fx
                val c0 = c00 + (c10 - c00) * fy
                val c1 = c01 + (c11 - c01) * fy
                out[c] = c0 + (c1 - c0) * fz
            }
        }

        val tmp = FloatArray(3)
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val a = p and -0x1000000
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            sample(r, g, b, tmp)
            val nr = (r + (tmp[0] - r) * mix).coerceIn(0f, 1f)
            val ng = (g + (tmp[1] - g) * mix).coerceIn(0f, 1f)
            val nb = (b + (tmp[2] - b) * mix).coerceIn(0f, 1f)
            pixels[i] = a or ((nr * 255f).toInt() shl 16) or ((ng * 255f).toInt() shl 8) or (nb * 255f).toInt()
            i++
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun buildMatrix(style: PictureStyle): ColorMatrix {
        val cm = ColorMatrix() // identity
        when (style) {
            PictureStyle.NEUTRAL -> {}
            PictureStyle.STANDARD -> {
                cm.postConcat(contrast(1.12f))
                cm.postConcat(saturation(1.15f))
            }
            PictureStyle.PORTRAIT -> {
                cm.postConcat(contrast(1.05f))
                cm.postConcat(saturation(1.04f))
                cm.postConcat(channelScale(1.06f, 1.0f, 0.93f)) // теплее кожа
            }
            PictureStyle.LANDSCAPE -> {
                cm.postConcat(contrast(1.15f))
                cm.postConcat(saturation(1.30f))
                cm.postConcat(channelScale(1.0f, 1.03f, 1.05f)) // сочнее зелень/небо
            }
            PictureStyle.FILM -> {
                cm.postConcat(contrast(0.90f))                  // мягче
                cm.postConcat(saturation(0.85f))                // приглушённее
                cm.postConcat(channelScale(1.05f, 1.0f, 0.92f)) // тёплый оттенок
                cm.postConcat(lift(12f))                        // блёклые тени
            }
        }
        return cm
    }

    /** Контраст вокруг средней точки: out = c*in + 128*(1-c). */
    private fun contrast(c: Float): ColorMatrix {
        val t = 128f * (1f - c)
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun saturation(s: Float): ColorMatrix = ColorMatrix().apply { setSaturation(s) }

    private fun channelScale(r: Float, g: Float, b: Float): ColorMatrix =
        ColorMatrix(
            floatArrayOf(
                r, 0f, 0f, 0f, 0f,
                0f, g, 0f, 0f, 0f,
                0f, 0f, b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

    /** Подъём теней (добавка ко всем каналам). */
    private fun lift(v: Float): ColorMatrix =
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, v,
                0f, 1f, 0f, 0f, v,
                0f, 0f, 1f, 0f, v,
                0f, 0f, 0f, 1f, 0f
            )
        )
}
