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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Главное МЕНЮ в структуре Nikon: слева вертикальные вкладки
 * (СЪЁМКА / НАСТРОЙКА / СЕРВИС), справа список пунктов; тап по пункту
 * раскрывает подменю значений прямо под строкой.
 */
private enum class MTab(val title: String, val mark: String) {
    SHOOT("СЪЁМКА", "📷"),
    CUSTOM("НАСТРОЙКА", "✎"),
    SERVICE("СЕРВИС", "🔧")
}

private class MRow(
    val label: String,
    val value: String,
    val enabled: Boolean = true,
    val options: List<Pair<String, () -> Unit>>? = null,
    val action: (() -> Unit)? = null,          // прямое действие (напр. открыть экран)
    val custom: Boolean = false                 // раскрыть кастомный блок (калибровка)
)

@Composable
fun MainMenuScreen(controller: CameraController, onBack: () -> Unit, onOpenScout: () -> Unit) {
    var tab by remember { mutableStateOf(MTab.SHOOT) }
    var expanded by remember { mutableStateOf(-1) }

    // Состояние ручной проверки обновлений
    var updStatus by remember { mutableStateOf<String?>(null) }
    var updRel by remember { mutableStateOf<UpdateManager.Release?>(null) }
    var updProgress by remember { mutableStateOf(-1) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val s = controller.settings
    val caps = controller.activeCaps

    val rows: List<MRow> = when (tab) {
        MTab.SHOOT -> listOf(
            MRow("Режим экспозиции", s.exposureMode.name, options = ExposureMode.values().map { m ->
                m.name to { controller.setExposureMode(m) }
            }),
            MRow("Стиль изображения", s.pictureStyle.title, options = PictureStyle.values().map { st ->
                st.title to { controller.setPictureStyle(st) }
            }),
            MRow("LUT (плёнка)", controller.lutName, options = listOf(
                "Нет" to { controller.setLut(null, "Нет") },
                "Тёплая" to { controller.setLut(Lut3D.warmFilm(), "Тёплая") },
                "Винтаж" to { controller.setLut(Lut3D.vintage(), "Винтаж") },
                "Тил-оранж" to { controller.setLut(Lut3D.tealOrange(), "Тил-оранж") }
            )),
            MRow("Сила LUT", "${(controller.lutMix * 100).toInt()}%",
                enabled = controller.currentLut() != null,
                options = listOf(0, 25, 50, 75, 100).map { p -> "$p%" to { controller.updateLutMix(p / 100f) } }),
            MRow("Баланс белого", if (s.autoWb) "AWB" else "${s.wbKelvin}K",
                enabled = caps?.supportsManualPostProc == true,
                options = (listOf<Pair<String, () -> Unit>>(
                    "AWB" to { controller.updateSettings { it.copy(autoWb = true) } }
                ) + listOf(3200, 4200, 5200, 6500, 7500).map { k ->
                    "${k}K" to { controller.updateSettings { it.copy(wbKelvin = k, autoWb = false) } }
                })),
            MRow("Формат снимка", if (s.captureRaw) "RAW+JPG" else "JPEG",
                enabled = caps?.supportsRaw == true, options = listOf(
                    "JPEG" to { controller.updateSettings { it.copy(captureRaw = false) } },
                    "RAW+JPG" to { controller.updateSettings { it.copy(captureRaw = true) } }
                ))
        )
        MTab.CUSTOM -> listOf(
            MRow("Фокус-пикинг", if (controller.focusPeaking) "ВКЛ" else "Выкл", options = listOf(
                "ВКЛ" to { if (!controller.focusPeaking) controller.togglePeaking() },
                "Выкл" to { if (controller.focusPeaking) controller.togglePeaking() }
            )),
            MRow("Зебра (пересветы)", if (controller.zebra) "ВКЛ" else "Выкл", options = listOf(
                "ВКЛ" to { if (!controller.zebra) controller.toggleZebra() },
                "Выкл" to { if (controller.zebra) controller.toggleZebra() }
            )),
            MRow("Сетка (трети)", if (controller.gridLines) "ВКЛ" else "Выкл", options = listOf(
                "ВКЛ" to { if (!controller.gridLines) controller.toggleGrid() },
                "Выкл" to { if (controller.gridLines) controller.toggleGrid() }
            )),
            MRow("Калибровка превью",
                "${controller.calRot}°${if (controller.calMirror) "+зерк" else ""}",
                custom = true)
        )
        MTab.SERVICE -> listOf(
            MRow("Разведчик камер", "открыть", action = onOpenScout),
            MRow("Проверить обновления", updStatus ?: "проверить", custom = true),
            MRow("Версия", "NaturalCam ${BuildConfig.VERSION_NAME}")
        )
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0A0A0A))
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
            }
            Text("МЕНЮ", color = NIKON_YELLOW, fontSize = 18.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxSize()) {
            // Вертикальные вкладки (как левая колонка меню Nikon)
            Column(Modifier.width(96.dp).fillMaxHeight().background(Color(0xFF101010))) {
                MTab.values().forEach { t ->
                    val selT = t == tab
                    Row(
                        Modifier.fillMaxWidth().height(64.dp)
                            .background(if (selT) Color(0xFF1C1A10) else Color.Transparent)
                            .clickable { tab = t; expanded = -1 },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight()
                            .background(if (selT) NIKON_YELLOW else Color.Transparent))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(t.mark, fontSize = 16.sp)
                            Text(t.title, color = if (selT) Color.White else Color(0xFF777777),
                                fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // Список пунктов
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                rows.forEachIndexed { i, r ->
                    val open = expanded == i
                    Row(
                        Modifier.fillMaxWidth().height(52.dp)
                            .background(if (open) Color(0xFF181818) else Color.Transparent,
                                RoundedCornerShape(6.dp))
                            .clickable(enabled = r.enabled) {
                                when {
                                    r.action != null -> r.action.invoke()
                                    else -> expanded = if (open) -1 else i
                                }
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(r.label,
                            color = if (r.enabled) Color.White else Color(0xFF555555),
                            fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(r.value,
                            color = if (r.enabled) NIKON_YELLOW else Color(0xFF555555),
                            fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold)
                        if (r.options != null || r.custom || r.action != null) {
                            Text(if (open) " ▾" else " ▸", color = Color(0xFF777777), fontSize = 13.sp)
                        }
                    }
                    // Подменю значений
                    if (open && r.options != null) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            r.options.forEach { (lbl, act) ->
                                val activeOpt = lbl.equals(r.value, ignoreCase = true)
                                Box(
                                    Modifier.background(
                                        if (activeOpt) NIKON_YELLOW else Color(0xFF1E1E1E),
                                        RoundedCornerShape(6.dp))
                                        .border(1.dp,
                                            if (activeOpt) NIKON_YELLOW else Color(0xFF3A3A3A),
                                            RoundedCornerShape(6.dp))
                                        .clickable { act() }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(lbl, color = if (activeOpt) Color.Black else Color.White,
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    // Кастомные блоки
                    if (open && r.custom && r.label.startsWith("Калибровка")) {
                        CalibrationRow(controller, NIKON_YELLOW)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (open && r.custom && r.label.startsWith("Проверить")) {
                        Column(Modifier.padding(8.dp)) {
                            val rel = updRel
                            when {
                                updProgress in 0..99 -> Text("Скачивание… $updProgress%",
                                    color = NIKON_YELLOW, fontSize = 13.sp)
                                rel != null -> Box(
                                    Modifier.background(NIKON_YELLOW, RoundedCornerShape(6.dp))
                                        .clickable {
                                            updProgress = 0
                                            Thread {
                                                val f = UpdateManager.download(context, rel) { p -> updProgress = p }
                                                if (f != null) { UpdateManager.install(context, f); updProgress = -1 }
                                                else { updStatus = "Ошибка скачивания"; updProgress = -1 }
                                            }.start()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text("Установить ${rel.versionName}", color = Color.Black,
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                else -> Box(
                                    Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                                        .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(6.dp))
                                        .clickable {
                                            updStatus = "Проверяю…"
                                            Thread {
                                                val r2 = UpdateManager.check(BuildConfig.VERSION_CODE)
                                                updRel = r2
                                                updStatus = if (r2 == null) "У вас последняя версия"
                                                else "Доступна ${r2.versionName}"
                                            }.start()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text("Проверить сейчас", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
