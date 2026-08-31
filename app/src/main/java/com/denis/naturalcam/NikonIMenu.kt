package com.denis.naturalcam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * i-меню как на Nikon Z: оверлей поверх живого кадра, 12 пунктов (2 ряда × 6).
 * Тап по плитке — снизу разворачивается строка значений, тап по значению применяет.
 * Тумблеры (пикинг/зебра) переключаются сразу.
 */
private class IItem(
    val label: String,
    val value: String,
    val enabled: Boolean = true,
    val options: List<Pair<String, () -> Unit>>? = null,  // null => тумблер
    val toggle: (() -> Unit)? = null
)

@Composable
private fun buildIItems(controller: CameraController): List<IItem> {
    val s = controller.settings
    val caps = controller.activeCaps
    val evStep = caps?.evStep ?: (1.0 / 3.0)

    // Выдержки — стандартный ряд, обрезанный по возможностям сенсора
    val expR = caps?.exposureNanosRange
    val shutterOpts = mutableListOf<Pair<String, () -> Unit>>(
        "AUTO" to { controller.updateSettings { it.copy(autoShutter = true) } }
    )
    listOf(4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2).forEach { d ->
        val ns = (1_000_000_000.0 / d).toLong()
        if (expR == null || (ns >= expR.lower && ns <= expR.upper)) {
            shutterOpts += "1/$d" to {
                controller.updateSettings { it.copy(exposureNanos = ns, autoShutter = false) }
            }
        }
    }

    val isoR = caps?.isoRange
    val isoOpts = mutableListOf<Pair<String, () -> Unit>>(
        "AUTO" to { controller.updateSettings { it.copy(autoIso = true) } }
    )
    listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800).forEach { v ->
        if (isoR == null || (v >= isoR.lower && v <= isoR.upper)) {
            isoOpts += v.toString() to {
                controller.updateSettings { it.copy(iso = v, autoIso = false) }
            }
        }
    }

    val evOpts = listOf(-2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0).map { ev ->
        val label = if (ev == 0.0) "±0" else String.format("%+.1f", ev)
        label to {
            val steps = (ev / evStep).roundToInt()
            val r = caps?.evRange
            controller.updateSettings {
                it.copy(evComp = if (r != null) steps.coerceIn(r.lower, r.upper) else steps)
            }
        }
    }

    val wbOpts = mutableListOf<Pair<String, () -> Unit>>(
        "AWB" to { controller.updateSettings { it.copy(autoWb = true) } }
    )
    listOf(3200, 4200, 5200, 6500, 7500).forEach { k ->
        wbOpts += "${k}K" to {
            controller.updateSettings { it.copy(wbKelvin = k, autoWb = false) }
        }
    }

    return listOf(
        IItem("РЕЖИМ", s.exposureMode.name, options = ExposureMode.values().map { m ->
            m.name to { controller.setExposureMode(m) }
        }),
        IItem("ВЫДЕРЖКА", if (s.autoShutter) "AUTO" else formatShutter(s.exposureNanos),
            enabled = caps?.supportsManualSensor == true, options = shutterOpts),
        IItem("ISO", if (s.autoIso) "AUTO" else s.iso.toString(),
            enabled = caps?.supportsManualSensor == true, options = isoOpts),
        IItem("ЭКСП.КОРР", String.format("%+.1f", s.evComp * evStep), options = evOpts),
        IItem("ББ", if (s.autoWb) "AWB" else "${s.wbKelvin}K",
            enabled = caps?.supportsManualPostProc == true, options = wbOpts),
        IItem("ФОКУС", if (s.autoFocus) "AF" else "MF",
            enabled = caps?.hasManualFocus == true, options = listOf(
                "AF" to { controller.updateSettings { it.copy(autoFocus = true) } },
                "MF" to { controller.updateSettings { it.copy(autoFocus = false) } }
            )),
        IItem("СТИЛЬ", s.pictureStyle.title, options = PictureStyle.values().map { st ->
            st.title to { controller.setPictureStyle(st) }
        }),
        IItem("LUT", controller.lutName, options = listOf(
            "Нет" to { controller.setLut(null, "Нет") },
            "Тёплая" to { controller.setLut(Lut3D.warmFilm(), "Тёплая") },
            "Винтаж" to { controller.setLut(Lut3D.vintage(), "Винтаж") },
            "Тил-оранж" to { controller.setLut(Lut3D.tealOrange(), "Тил-оранж") }
        )),
        IItem("СИЛА LUT", "${(controller.lutMix * 100).toInt()}%",
            enabled = controller.currentLut() != null,
            options = listOf(0, 25, 50, 75, 100).map { p ->
                "$p%" to { controller.updateLutMix(p / 100f) }
            }),
        IItem("ФОРМАТ", if (s.captureRaw) "RAW+JPG" else "JPEG",
            enabled = caps?.supportsRaw == true, options = listOf(
                "JPEG" to { controller.updateSettings { it.copy(captureRaw = false) } },
                "RAW+JPG" to { controller.updateSettings { it.copy(captureRaw = true) } }
            )),
        IItem("ПИКИНГ", if (controller.focusPeaking) "ВКЛ" else "выкл",
            toggle = { controller.togglePeaking() }),
        IItem("ЗЕБРА", if (controller.zebra) "ВКЛ" else "выкл",
            toggle = { controller.toggleZebra() })
    )
}

// Индексы пунктов i-меню (для открытия сразу с нужным пунктом по тапу в панели)
object IMenuIdx {
    const val MODE = 0; const val SHUTTER = 1; const val ISO = 2; const val EV = 3
    const val WB = 4; const val FOCUS = 5; const val STYLE = 6; const val LUT = 7
    const val LUT_MIX = 8; const val FORMAT = 9; const val PEAK = 10; const val ZEBRA = 11
}

@Composable
fun NikonIMenuOverlay(controller: CameraController, initialSel: Int = -1, onClose: () -> Unit) {
    var sel by remember(initialSel) { mutableStateOf(initialSel) }
    val items = buildIItems(controller)

    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xEE000000))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("i", color = NIKON_YELLOW, fontSize = 20.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text("БЫСТРОЕ МЕНЮ", color = Color(0xFF8A8A8A), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("✕", color = Color(0xFFAAAAAA), fontSize = 18.sp,
                modifier = Modifier.clickable { onClose() }.padding(6.dp))
        }

        // Строка значений выбранного пункта (как выбор опции на Nikon)
        val cur = items.getOrNull(sel)
        if (cur?.options != null) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                cur.options.forEach { (lbl, act) ->
                    val activeOpt = lbl == cur.value
                    Box(
                        Modifier.background(
                            if (activeOpt) NIKON_YELLOW else Color(0xFF1E1E1E),
                            RoundedCornerShape(6.dp)
                        )
                            .border(1.dp, if (activeOpt) NIKON_YELLOW else Color(0xFF3A3A3A),
                                RoundedCornerShape(6.dp))
                            .clickable { act() }
                            .padding(horizontal = 13.dp, vertical = 9.dp)
                    ) {
                        Text(lbl, color = if (activeOpt) Color.Black else Color.White,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // 2 ряда × 6 плиток
        items.chunked(6).forEachIndexed { rowIdx, row ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEachIndexed { colIdx, item ->
                    val idx = rowIdx * 6 + colIdx
                    val selected = idx == sel
                    Column(
                        Modifier.weight(1f).height(52.dp)
                            .background(Color(0xFF141414), RoundedCornerShape(5.dp))
                            .border(
                                1.5.dp,
                                when {
                                    selected -> NIKON_YELLOW
                                    item.enabled -> Color(0xFF333333)
                                    else -> Color(0xFF1E1E1E)
                                },
                                RoundedCornerShape(5.dp)
                            )
                            .clickable(enabled = item.enabled) {
                                if (item.options == null) item.toggle?.invoke()
                                else sel = if (selected) -1 else idx
                            }
                            .padding(horizontal = 5.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(item.label,
                            color = if (item.enabled) Color(0xFF8A8A8A) else Color(0xFF4A4A4A),
                            fontSize = 7.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        Text(item.value,
                            color = if (item.enabled) Color.White else Color(0xFF4A4A4A),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                }
            }
        }
    }
}
