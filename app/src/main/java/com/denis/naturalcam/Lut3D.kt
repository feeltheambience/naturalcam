package com.denis.naturalcam

/**
 * 3D-LUT (цветовая таблица) для цветокоррекции «как в кино».
 * data — размер size^3 * 3, порядок: красный меняется быстрее всего
 * (как в формате .cube и как ждёт GL_TEXTURE_3D: x=r быстрее, потом g, потом b).
 */
class Lut3D(val size: Int, val data: FloatArray) {

    companion object {

        /** Единичная LUT (ничего не меняет) — нужна как заглушка, чтобы sampler3D всегда был валиден. */
        fun identity(): Lut3D {
            val n = 2
            val d = FloatArray(n * n * n * 3)
            var i = 0
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                d[i++] = r.toFloat(); d[i++] = g.toFloat(); d[i++] = b.toFloat()
            }
            return Lut3D(n, d)
        }

        /** Генерация LUT по функции преобразования цвета. */
        fun generate(size: Int, transform: (Float, Float, Float) -> FloatArray): Lut3D {
            val d = FloatArray(size * size * size * 3)
            var i = 0
            val m = (size - 1).toFloat()
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val out = transform(r / m, g / m, b / m)
                d[i++] = out[0].coerceIn(0f, 1f)
                d[i++] = out[1].coerceIn(0f, 1f)
                d[i++] = out[2].coerceIn(0f, 1f)
            }
            return Lut3D(size, d)
        }

        // --- Встроенные «плёнки» ---

        fun warmFilm(): Lut3D = generate(17) { r, g, b ->
            basic(r, g, b, contrast = 1.08f, sat = 0.96f, rMul = 1.06f, gMul = 1.0f, bMul = 0.92f, lift = 0.03f)
        }

        fun vintage(): Lut3D = generate(17) { r, g, b ->
            basic(r, g, b, contrast = 0.90f, sat = 0.78f, rMul = 1.05f, gMul = 1.0f, bMul = 0.90f, lift = 0.06f)
        }

        /** Кино-сплит-тон: тени в бирюзу, света в оранжевый. */
        fun tealOrange(): Lut3D = generate(17) { r, g, b ->
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val shadow = 1f - lum
            val high = lum
            var nr = r + 0.06f * high - 0.02f * shadow
            var ng = g + 0.015f * high + 0.02f * shadow
            var nb = b - 0.04f * high + 0.06f * shadow
            // лёгкий контраст
            nr = (nr - 0.5f) * 1.08f + 0.5f
            ng = (ng - 0.5f) * 1.08f + 0.5f
            nb = (nb - 0.5f) * 1.08f + 0.5f
            floatArrayOf(nr, ng, nb)
        }

        private fun basic(
            r: Float, g: Float, b: Float,
            contrast: Float, sat: Float,
            rMul: Float, gMul: Float, bMul: Float, lift: Float
        ): FloatArray {
            var nr = r * rMul; var ng = g * gMul; var nb = b * bMul
            nr = (nr - 0.5f) * contrast + 0.5f
            ng = (ng - 0.5f) * contrast + 0.5f
            nb = (nb - 0.5f) * contrast + 0.5f
            val l = 0.299f * nr + 0.587f * ng + 0.114f * nb
            nr = l + (nr - l) * sat
            ng = l + (ng - l) * sat
            nb = l + (nb - l) * sat
            return floatArrayOf(nr + lift, ng + lift, nb + lift)
        }

        /** Парсер стандартного .cube (3D). Возвращает null, если формат не распознан. */
        fun parseCube(text: String): Lut3D? {
            var size = -1
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith("LUT_3D_SIZE", ignoreCase = true) ->
                        size = line.split(Regex("\\s+")).last().toIntOrNull() ?: -1
                    line.startsWith("LUT_1D_SIZE", ignoreCase = true) -> return null // 1D не поддерживаем
                    line.startsWith("TITLE", ignoreCase = true) -> {}
                    line.startsWith("DOMAIN_MIN", ignoreCase = true) -> {}
                    line.startsWith("DOMAIN_MAX", ignoreCase = true) -> {}
                    else -> {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            val r = parts[0].toFloatOrNull()
                            val g = parts[1].toFloatOrNull()
                            val b = parts[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                values.add(r); values.add(g); values.add(b)
                            }
                        }
                    }
                }
            }
            if (size < 2) return null
            if (values.size != size * size * size * 3) return null
            return Lut3D(size, values.toFloatArray())
        }
    }
}
