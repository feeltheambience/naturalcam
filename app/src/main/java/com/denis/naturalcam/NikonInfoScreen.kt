package com.denis.naturalcam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Быстрое меню, у каждого скина СВОЯ механика:
 *  - NIKON: i-меню — сетка плиток, тап по плитке циклит значение (как на Z-серии);
 *  - CANON: Q-меню — список параметров слева, крупное значение и стрелки ◀ ▶ справа
 *    (механика Quick Control: выбрал пункт → крутишь значение).
 */
@Composable
fun QuickMenuScreen(controller: CameraController, onBack: () -> Unit) {
    if (controller.uiSkin == UiSkin.NIKON) NikonIMenu(controller, onBack)
    else CanonQMenu(controller, onBack)
}

// --- Описание пункта меню (общее) ---
private class QP(
    val name: String,
    val value: String,
    val enabled: Boolean = true,
    val cycle: (Int) -> Unit
)

@Composable
private fun buildParams(controller: CameraController): List<QP> {
    val s = controller.settings
    val caps = controller.activeCaps
    val evStep = caps?.evStep ?: 0.0
    return listOf(
        QP("РЕЖИМ", s.exposureMode.name) { d -> cycleMode(controller, d) },
        QP("ВЫДЕРЖКА", if (s.autoShutter) "AUTO" else formatShutter(s.exposureNanos),
            caps?.supportsManualSensor == true) { _ ->
            controller.updateSettings { it.copy(autoShutter = !it.autoShutter) }
        },
        QP("ISO", if (s.autoIso) "AUTO" else s.iso.toString(),
            caps?.supportsManualSensor == true) { _ ->
            controller.updateSettings { it.copy(autoIso = !it.autoIso) }
        },
        QP("ЭКСП.КОРР", String.format("%+.1f", s.evComp * evStep)) { d -> cycleEv(controller, d) },
        QP("ББ", if (s.autoWb) "AWB" else "${s.wbKelvin}K",
            caps?.supportsManualPostProc == true) { d -> cycleWb(controller, d) },
        QP("ФОКУС", if (s.autoFocus) "AF" else "MF",
            caps?.hasManualFocus == true) { _ ->
            controller.updateSettings { it.copy(autoFocus = !it.autoFocus) }
        },
        QP("СТИЛЬ", s.pictureStyle.title) { d -> cycleStyle(controller, d) },
        QP("LUT", controller.lutName) { d -> cycleLut(controller, d) },
        QP("СИЛА LUT", "${(controller.lutMix * 100).toInt()}%",
            controller.currentLut() != null) { d -> cycleLutMix(controller, d) },
        QP("ФОРМАТ", if (s.captureRaw) "RAW+JPG" else "JPEG",
            caps?.supportsRaw == true) { _ ->
            controller.updateSettings { it.copy(captureRaw = !it.captureRaw) }
        },
        QP("ПИКИНГ", if (controller.focusPeaking) "ВКЛ" else "выкл") { _ -> controller.togglePeaking() },
        QP("ЗЕБРА", if (controller.zebra) "ВКЛ" else "выкл") { _ -> controller.toggleZebra() },
        QP("СЕТКА", if (controller.gridLines) "ВКЛ" else "выкл") { _ -> controller.toggleGrid() },
    )
}

// ---------------------------------------------------------------------------
// NIKON i-меню: сетка плиток 3 колонки
// ---------------------------------------------------------------------------
@Composable
private fun NikonIMenu(controller: CameraController, onBack: () -> Unit) {
    val params = buildParams(controller)
    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
            }
            Text("i", color = NIKON_YELLOW, fontSize = 24.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            Text("МЕНЮ БЫСТРЫХ НАСТРОЕК", color = Color(0xFF8A8A8A), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        params.chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { p ->
                    NikonTile(p, Modifier.weight(1f))
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "Тап по плитке переключает значение. Точная подстройка — ползунки на экране съёмки.",
            color = Color(0xFF666666), fontSize = 10.sp
        )
    }
}

@Composable
private fun NikonTile(p: QP, modifier: Modifier) {
    Column(
        modifier.height(64.dp)
            .background(Color(0xFF161616), RoundedCornerShape(6.dp))
            .border(1.dp, if (p.enabled) Color(0xFF3A3A3A) else Color(0xFF222222), RoundedCornerShape(6.dp))
            .clickable(enabled = p.enabled) { p.cycle(1) }
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(p.name, color = if (p.enabled) Color(0xFF8A8A8A) else Color(0xFF4A4A4A),
            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(p.value, color = if (p.enabled) Color.White else Color(0xFF4A4A4A),
            fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// CANON Q-меню: список слева, крупное значение + ◀ ▶ справа
// ---------------------------------------------------------------------------
@Composable
private fun CanonQMenu(controller: CameraController, onBack: () -> Unit) {
    val params = buildParams(controller)
    var sel by remember { mutableStateOf(0) }
    if (sel >= params.size) sel = 0
    val current = params[sel]

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0C0C0C))
            .statusBarsPadding().navigationBarsPadding()
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
            }
            Text("Q", color = CANON_RED, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(10.dp))
            Text("QUICK CONTROL", color = Color(0xFF8A8A8A), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize()) {
            // Список параметров
            Column(
                Modifier.width(150.dp).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
            ) {
                params.forEachIndexed { i, p ->
                    val selected = i == sel
                    Row(
                        Modifier.fillMaxWidth().height(38.dp)
                            .background(if (selected) Color(0xFF1E1214) else Color.Transparent)
                            .clickable { sel = i },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.width(4.dp).fillMaxHeight()
                                .background(if (selected) CANON_RED else Color.Transparent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(p.name,
                            color = if (!p.enabled) Color(0xFF4A4A4A)
                            else if (selected) Color.White else Color(0xFFAAAAAA),
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            // Значение выбранного + стрелки
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(current.name, color = Color(0xFF8A8A8A), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text(
                    current.value,
                    color = if (current.enabled) Color.White else Color(0xFF4A4A4A),
                    fontSize = 34.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    CanonArrow("◀", current.enabled) { current.cycle(-1) }
                    CanonArrow("▶", current.enabled) { current.cycle(1) }
                }
                Spacer(Modifier.height(20.dp))
                Text("SET: стрелками меняется значение", color = Color(0xFF555555), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun CanonArrow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp)
            .background(Color(0xFF181818), CircleShape)
            .border(1.5.dp, if (enabled) CANON_RED else Color(0xFF333333), CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) Color.White else Color(0xFF4A4A4A), fontSize = 20.sp)
    }
}
