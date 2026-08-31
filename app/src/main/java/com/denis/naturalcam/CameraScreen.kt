package com.denis.naturalcam

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.exp as mexp

// ---------------------------------------------------------------------------
// Цвета скинов
// ---------------------------------------------------------------------------
val NIKON_YELLOW = Color(0xFFFFE100)
val CANON_RED = Color(0xFFD40000)
fun skinAccent(skin: UiSkin): Color = if (skin == UiSkin.NIKON) NIKON_YELLOW else CANON_RED

// ---------------------------------------------------------------------------
// Корневой экран + OTA-баннер
// ---------------------------------------------------------------------------
@Composable
fun RootScreen(controller: CameraController) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        controller.startBackground()
        controller.probeAll()
    }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    var release by remember { mutableStateOf<UpdateManager.Release?>(null) }
    var dl by remember { mutableStateOf(DL_IDLE) }
    LaunchedEffect(Unit) {
        Thread { release = UpdateManager.check(BuildConfig.VERSION_CODE) }.start()
    }

    var showMainMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        when {
            showInfo -> InfoScreen(controller) { showInfo = false }
            showMenu -> QuickMenuScreen(controller) { showMenu = false }
            showMainMenu -> MainMenuScreen(
                controller,
                onBack = { showMainMenu = false },
                onOpenScout = { showMainMenu = false; showInfo = true }
            )
            else -> CameraScreen(
                controller,
                onOpenMenu = { showMenu = true },
                onOpenMainMenu = { showMainMenu = true }
            )
        }
        val rel = release
        if (rel != null) {
            UpdateBanner(
                rel = rel, progress = dl,
                onUpdate = {
                    dl = 0
                    Thread {
                        val f = UpdateManager.download(context, rel) { p -> dl = p }
                        if (f != null) { UpdateManager.install(context, f); dl = DL_IDLE }
                        else dl = DL_ERROR
                    }.start()
                },
                onDismiss = { release = null }
            )
        }
    }
}

private const val DL_IDLE = -1
private const val DL_ERROR = -2

// ---------------------------------------------------------------------------
// Экран камеры: общее превью + скин-специфичный HUD
// ---------------------------------------------------------------------------
@Composable
fun CameraScreen(controller: CameraController, onOpenMenu: () -> Unit, onOpenMainMenu: () -> Unit) {
    var panelOpen by remember { mutableStateOf(false) }
    var calOpen by remember { mutableStateOf(false) }
    var iMenuOpen by remember { mutableStateOf(false) }
    var iMenuSel by remember { mutableStateOf(-1) }
    // Тап по значению в нижней панели → i-меню сразу с этим пунктом
    val editParam: (Int) -> Unit = { idx -> iMenuSel = idx; iMenuOpen = true }
    val caps = controller.activeCaps
    val s = controller.settings
    val skin = controller.uiSkin
    val accent = skinAccent(skin)
    // Иконки поворачиваются под физическое положение телефона (окно всегда в портрете)
    val uiAngle by animateFloatAsState(targetValue = -controller.uiRotation.toFloat(), label = "uiRot")

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        GLCameraView(controller, Modifier.fillMaxSize())

        if (controller.gridLines) {
            Canvas(Modifier.fillMaxSize()) {
                val col = Color(0x55FFFFFF)
                for (i in 1..2) {
                    val x = size.width * i / 3f
                    val y = size.height * i / 3f
                    drawLine(col, Offset(x, 0f), Offset(x, size.height), 1.5f)
                    drawLine(col, Offset(0f, y), Offset(size.width, y), 1.5f)
                }
            }
        }

        // Тап по превью -> точка фокуса/экспозиции
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { o ->
                    controller.focusAt(
                        (o.x / size.width).coerceIn(0f, 1f),
                        (o.y / size.height).coerceIn(0f, 1f)
                    )
                }
            }
        )

        // Верхняя панель (общая): бренд-переключатель, ассистенты, линзы, статус
        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandToggle(skin) { controller.toggleSkin() }
                Spacer(Modifier.weight(1f))
                QuickToggle("PEAK", controller.focusPeaking, accent, uiAngle) { controller.togglePeaking() }
                Spacer(Modifier.width(6.dp))
                QuickToggle("ZEBRA", controller.zebra, accent, uiAngle) { controller.toggleZebra() }
                Spacer(Modifier.width(6.dp))
                QuickToggle("GRID", controller.gridLines, accent, uiAngle) { controller.toggleGrid() }
                Spacer(Modifier.width(6.dp))
                QuickToggle("CAL", calOpen, accent, uiAngle) { calOpen = !calOpen }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { panelOpen = !panelOpen }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Filled.Tune, "Ползунки", tint = if (panelOpen) accent else Color.White)
                }
                IconButton(onClick = onOpenMainMenu, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Filled.Menu, "Меню", tint = Color.White)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                controller.capabilities.forEach { c ->
                    LensChip(lensLabel(c, controller.capabilities), active = c.id == controller.activeId,
                        accent = accent, rotation = uiAngle) {
                        controller.selectCamera(c.id)
                    }
                }
            }
            Text(controller.status, color = Color(0xFF9A9A9A), fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp))
            if (calOpen) {
                CalibrationRow(controller, accent)
            } else if (!controller.calHintHidden && controller.calRot == 0 && !controller.calMirror) {
                // Пока камера не откалибрована — подсказка; крестик прячет навсегда
                Row(
                    Modifier.padding(top = 6.dp)
                        .background(Color(0xCC1A1A00), RoundedCornerShape(8.dp))
                        .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Картинка лежит боком? Тапни сюда — «Поворот +90°» до правильной, запомнится.",
                        color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.weight(1f).clickable { calOpen = true })
                    Text("✕", color = Color(0xFF999999), fontSize = 15.sp,
                        modifier = Modifier.clickable { controller.hideCalHint() }.padding(start = 8.dp))
                }
            }
        }

        // Гистограмма
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 120.dp, end = 12.dp)) {
            Histogram(controller.histogram, controller.clipHighPct)
        }

        // Canon: колонка быстрых настроек слева (как на EOS)
        if (skin == UiSkin.CANON) {
            CanonQuickColumn(
                controller,
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                rotation = uiAngle
            )
        }

        // Вертикальный слайдер зума: аппаратный диапазон + честный цифровой кроп сверху
        val minZ = controller.minZoom()
        val hwMax = controller.hwMaxZoom()
        val maxZ = controller.totalMaxZoom()
        ZoomSlider(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            zoom = s.zoomRatio, minZoom = minZ, maxZoom = maxZ, hwMax = hwMax,
            accent = accent, labelRotation = uiAngle,
            onZoom = { controller.setZoom(it) }
        )

        // Нижний блок
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            if (panelOpen) ManualPanel(controller)
            if (iMenuOpen && skin == UiSkin.NIKON) {
                // i-меню Nikon: оверлей вместо инфо-панели, как на Z-серии
                NikonIMenuOverlay(controller, initialSel = iMenuSel) { iMenuOpen = false; iMenuSel = -1 }
            } else {
                ZoomPresets(controller, minZ, maxZ, accent, uiAngle)
                if (skin == UiSkin.NIKON) NikonBar(controller, uiAngle, onEdit = editParam)
                else CanonBar(controller, uiAngle)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundBtn("⟳", accent, rotation = uiAngle) { cycleCamera(controller) }
                ShutterButton(accent) { controller.capturePhoto() }
                RoundBtn(if (skin == UiSkin.NIKON) "i" else "Q", accent, rotation = uiAngle) {
                    if (skin == UiSkin.NIKON) { iMenuSel = -1; iMenuOpen = !iMenuOpen } else onOpenMenu()
                }
            }
        }
    }
}

private fun cycleCamera(controller: CameraController) {
    val list = controller.capabilities
    if (list.isEmpty()) return
    val idx = list.indexOfFirst { it.id == controller.activeId }
    controller.selectCamera(list[(idx + 1).mod(list.size)].id)
}

private fun lensLabel(c: CameraCapabilities, all: List<CameraCapabilities>): String {
    if (c.facing == "front") return "фронт"
    // Первая (обычно единственная видимая) тыловая — главный логический модуль,
    // зум-кольцо переключает его линзы само. У больших сенсоров фокусное ~9мм,
    // поэтому эвристика «по миллиметрам» главную путала с телевиком.
    val backs = all.filter { it.facing == "back" }
    if (backs.firstOrNull()?.id == c.id) return "осн."
    val f = c.focalLengthsMm.minOrNull()
    return when {
        f != null && f < 3f -> "0.6×"
        f != null && f > 6f -> "теле"
        else -> "1×"
    }
}

// ---------------------------------------------------------------------------
// НИЖНЯЯ ПАНЕЛЬ NIKON — как инфо-строка Nikon Z: чёрная, жёлтые акценты,
// крупные показания в рамочных блоках, шкала EV c подписями
// ---------------------------------------------------------------------------
@Composable
fun NikonBar(controller: CameraController, rotation: Float = 0f, onEdit: (Int) -> Unit = {}) {
    val s = controller.settings
    val caps = controller.activeCaps
    val evStep = caps?.evStep ?: 0.0
    Column(
        Modifier.fillMaxWidth().background(Color(0xF5000000))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Режим — тап открывает выбор P/S/M
            Box(
                Modifier.background(Color.Black, RoundedCornerShape(4.dp))
                    .border(2.dp, NIKON_YELLOW, RoundedCornerShape(4.dp))
                    .clickable { onEdit(IMenuIdx.MODE) }
                    .padding(horizontal = 13.dp, vertical = 7.dp)
                    .graphicsLayer { rotationZ = rotation }
            ) {
                Text(s.exposureMode.name, color = NIKON_YELLOW, fontSize = 24.sp,
                    fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            NikonValue("ВЫДЕРЖКА", if (s.autoShutter) "AUTO" else formatShutter(s.exposureNanos),
                rotation = rotation) { onEdit(IMenuIdx.SHUTTER) }
            NikonValue("ISO", if (s.autoIso) "AUTO" else s.iso.toString(),
                rotation = rotation) { onEdit(IMenuIdx.ISO) }
            caps?.aperture?.let {
                NikonValue("ДИАФР.", "f/" + String.format("%.1f", it), dim = true,
                    rotation = rotation, onClick = null)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NikonMiniChip(if (s.autoWb) "AWB" else "${s.wbKelvin}K", rotation = rotation) {
                    onEdit(IMenuIdx.WB)
                }
                NikonMiniChip(if (s.captureRaw) "RAW+JPG" else "JPG",
                    active = s.captureRaw, enabled = caps?.supportsRaw == true, rotation = rotation) {
                    onEdit(IMenuIdx.FORMAT)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        // Шкала EV — тап открывает выбор поправки
        Box(Modifier.fillMaxWidth().clickable { onEdit(IMenuIdx.EV) }) {
            NikonEvMeter(
                s.evComp * evStep,
                caps?.evRange?.let { it.lower * evStep to it.upper * evStep } ?: (-3.0 to 3.0)
            )
        }
    }
}

@Composable
private fun NikonValue(
    label: String, value: String, dim: Boolean = false,
    rotation: Float = 0f, onClick: (() -> Unit)? = null
) {
    Column(
        Modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 2.dp)
            .graphicsLayer { rotationZ = rotation }
    ) {
        Text(label, color = Color(0xFF8A8A8A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = if (dim) Color(0xFFBBBBBB) else Color.White,
            fontSize = if (dim) 17.sp else 23.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        // Жёлтое подчёркивание = «это можно тапнуть и поменять»
        if (onClick != null) {
            Box(Modifier.padding(top = 2.dp).width(30.dp).height(2.dp)
                .background(NIKON_YELLOW.copy(alpha = 0.7f)))
        }
    }
}

@Composable
private fun NikonMiniChip(text: String, active: Boolean = false, enabled: Boolean = true, rotation: Float = 0f, onClick: () -> Unit) {
    Box(
        Modifier.background(if (active) NIKON_YELLOW else Color(0xFF181818), RoundedCornerShape(3.dp))
            .border(1.dp, if (active) NIKON_YELLOW else Color(0xFF3A3A3A), RoundedCornerShape(3.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .graphicsLayer { rotationZ = rotation }
    ) {
        Text(text, color = if (!enabled) Color(0xFF555555) else if (active) Color.Black else Color(0xFFDDDDDD),
            fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/** Шкала EV Nikon: цифры -3..+3, засечки, жёлтый маркер. */
@Composable
fun NikonEvMeter(evValue: Double, range: Pair<Double, Double>) {
    val lo = range.first; val hi = range.second
    val frac = (((evValue - lo) / (hi - lo)).coerceIn(0.0, 1.0)).toFloat()
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("-3", "-2", "-1", "0", "+1", "+2", "+3").forEach {
                Text(it, color = Color(0xFF777777), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val midY = size.height / 2f
            drawRect(Color(0xFF3A3A3A), topLeft = Offset(0f, midY - 1f), size = Size(size.width, 2f))
            for (i in 0..18) {
                val x = size.width * i / 18f
                val tall = i % 3 == 0
                drawRect(Color(0xFF6E6E6E),
                    topLeft = Offset(x - 1f, midY - (if (tall) 6f else 3f)),
                    size = Size(2f, if (tall) 12f else 6f))
            }
            val mx = size.width * frac
            drawRect(NIKON_YELLOW, topLeft = Offset(mx - 2.5f, 0f), size = Size(5f, size.height))
        }
    }
}

// ---------------------------------------------------------------------------
// НИЖНЯЯ ПАНЕЛЬ CANON — как live view EOS: крупная триада 1/125 · f · ISO,
// шкала EV с точкой, режим в белой рамке
// ---------------------------------------------------------------------------
@Composable
fun CanonBar(controller: CameraController, rotation: Float = 0f) {
    val s = controller.settings
    val caps = controller.activeCaps
    val evStep = caps?.evStep ?: 0.0
    Column(
        Modifier.fillMaxWidth().background(Color(0xE6101010))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // Режим — белая рамка, как на верхнем дисплее EOS
            Box(
                Modifier.border(1.5.dp, Color.White, RoundedCornerShape(3.dp))
                    .clickable { cycleMode(controller, 1) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .graphicsLayer { rotationZ = rotation }
            ) {
                Text(s.exposureMode.name, color = Color.White, fontSize = 20.sp,
                    fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                if (s.autoShutter) "AUTO" else formatShutter(s.exposureNanos),
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.graphicsLayer { rotationZ = rotation }
            )
            Spacer(Modifier.width(14.dp))
            Text(
                caps?.aperture?.let { "F" + String.format("%.1f", it) } ?: "F—",
                color = Color(0xFFCCCCCC), fontSize = 21.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 2.dp).graphicsLayer { rotationZ = rotation }
            )
            Spacer(Modifier.weight(1f))
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.graphicsLayer { rotationZ = rotation }
            ) {
                Text("ISO", color = CANON_RED, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text(if (s.autoIso) "AUTO" else s.iso.toString(),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(6.dp))
        CanonEvMeter(
            s.evComp * evStep,
            caps?.evRange?.let { it.lower * evStep to it.upper * evStep } ?: (-3.0 to 3.0)
        )
    }
}

/** Шкала EV Canon: белые деления, красная точка-маркер. */
@Composable
fun CanonEvMeter(evValue: Double, range: Pair<Double, Double>) {
    val lo = range.first; val hi = range.second
    val frac = (((evValue - lo) / (hi - lo)).coerceIn(0.0, 1.0)).toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("-3", color = Color(0xFF999999), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Canvas(Modifier.weight(1f).height(14.dp).padding(horizontal = 6.dp)) {
            val midY = size.height / 2f
            drawRect(Color(0xFF555555), topLeft = Offset(0f, midY - 0.8f), size = Size(size.width, 1.6f))
            for (i in 0..12) {
                val x = size.width * i / 12f
                drawRect(Color(0xFF999999),
                    topLeft = Offset(x - 0.8f, midY - (if (i % 2 == 0) 5f else 3f)),
                    size = Size(1.6f, if (i % 2 == 0) 10f else 6f))
            }
            drawCircle(CANON_RED, radius = 5f, center = Offset(size.width * frac, midY))
        }
        Text("+3", color = Color(0xFF999999), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

// ---------------------------------------------------------------------------
// CANON: вертикальная колонка быстрых настроек (как на левой кромке EOS)
// ---------------------------------------------------------------------------
@Composable
fun CanonQuickColumn(controller: CameraController, modifier: Modifier = Modifier, rotation: Float = 0f) {
    val s = controller.settings
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CanonSideItem("WB", if (s.autoWb) "AWB" else "${s.wbKelvin / 100}", rotation = rotation) { cycleWb(controller, 1) }
        CanonSideItem("PIC", s.pictureStyle.title.take(4), rotation = rotation) { cycleStyle(controller, 1) }
        CanonSideItem("LUT", controller.lutName.take(4), rotation = rotation) { cycleLut(controller, 1) }
        CanonSideItem(if (s.autoFocus) "AF" else "MF", if (s.autoFocus) "серв" else "руч",
            active = !s.autoFocus, rotation = rotation) {
            controller.updateSettings { it.copy(autoFocus = !it.autoFocus) }
        }
        CanonSideItem("REC", if (s.captureRaw) "RAW" else "JPG", active = s.captureRaw,
            enabled = controller.activeCaps?.supportsRaw == true, rotation = rotation) {
            controller.updateSettings { it.copy(captureRaw = !it.captureRaw) }
        }
    }
}

@Composable
private fun CanonSideItem(
    label: String, value: String,
    active: Boolean = false, enabled: Boolean = true, rotation: Float = 0f, onClick: () -> Unit
) {
    Column(
        Modifier.width(52.dp)
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .border(1.dp, if (active) CANON_RED else Color(0x44FFFFFF), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 5.dp)
            .graphicsLayer { rotationZ = rotation },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = if (!enabled) Color(0xFF555555) else if (active) CANON_RED else Color.White,
            fontSize = 11.sp, fontWeight = FontWeight.Black)
        Text(value, color = if (enabled) Color(0xFFCCCCCC) else Color(0xFF555555), fontSize = 9.sp)
    }
}

// ---------------------------------------------------------------------------
// Циклы значений (общие для HUD и меню)
// ---------------------------------------------------------------------------
fun cycleMode(controller: CameraController, dir: Int) {
    val modes = ExposureMode.values()
    val cur = controller.settings.exposureMode.ordinal
    controller.setExposureMode(modes[(cur + dir + modes.size) % modes.size])
}

fun cycleStyle(controller: CameraController, dir: Int) {
    val v = PictureStyle.values()
    controller.setPictureStyle(v[(controller.settings.pictureStyle.ordinal + dir + v.size) % v.size])
}

private val LUT_ORDER = listOf("Нет", "Тёплая", "Винтаж", "Тил-оранж")
fun cycleLut(controller: CameraController, dir: Int) {
    val cur = LUT_ORDER.indexOf(controller.lutName).let { if (it < 0) 0 else it }
    when (LUT_ORDER[(cur + dir + LUT_ORDER.size) % LUT_ORDER.size]) {
        "Тёплая" -> controller.setLut(Lut3D.warmFilm(), "Тёплая")
        "Винтаж" -> controller.setLut(Lut3D.vintage(), "Винтаж")
        "Тил-оранж" -> controller.setLut(Lut3D.tealOrange(), "Тил-оранж")
        else -> controller.setLut(null, "Нет")
    }
}

private val WB_PRESETS = listOf(3200, 4200, 5200, 6500, 7500)
fun cycleWb(controller: CameraController, dir: Int) {
    val s = controller.settings
    if (s.autoWb) {
        controller.updateSettings {
            it.copy(autoWb = false, wbKelvin = if (dir >= 0) WB_PRESETS.first() else WB_PRESETS.last())
        }
    } else {
        val idx = WB_PRESETS.indexOfFirst { it >= s.wbKelvin }.let { if (it < 0) WB_PRESETS.size - 1 else it }
        val n = idx + dir
        if (n !in WB_PRESETS.indices) controller.updateSettings { it.copy(autoWb = true) }
        else controller.updateSettings { it.copy(wbKelvin = WB_PRESETS[n]) }
    }
}

fun cycleLutMix(controller: CameraController, dir: Int) {
    val step = ((controller.lutMix * 4).roundToInt() + dir).coerceIn(0, 4)
    controller.updateLutMix(step / 4f)
}

fun cycleEv(controller: CameraController, dir: Int) {
    val r = controller.activeCaps?.evRange ?: return
    controller.updateSettings {
        it.copy(evComp = (it.evComp + dir).coerceIn(r.lower, r.upper))
    }
}

// ---------------------------------------------------------------------------
// Калибровка превью: если картинка легла не так — крути до правильной,
// настройка сохраняется для каждой камеры. Цифры внизу — для отчёта.
// ---------------------------------------------------------------------------
@Composable
fun CalibrationRow(controller: CameraController, accent: Color) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Поворот +90°", active = controller.calRot != 0, enabled = true) {
                controller.bumpCalRot()
            }
            Chip("Зеркало", active = controller.calMirror, enabled = true) {
                controller.toggleCalMirror()
            }
            Chip("Сброс", active = false, enabled = true) { controller.resetCal() }
        }
        Text(
            "камера ${controller.activeId} • сенсор ${controller.sensorOrientation}° • " +
                "ST ${controller.stRotInfo}° • " +
                "поправка ${controller.calRot}°${if (controller.calMirror) " + зеркало" else ""} • " +
                "буфер ${controller.previewBufferWidth()}×${controller.previewBufferHeight()}",
            color = Color(0xFF9A9A9A), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 5.dp)
        )
        Text(
            "Крути «Поворот» пока картинка не станет правильной — запомнится для этой камеры.",
            color = Color(0xFF777777), fontSize = 9.sp
        )
    }
}

// ---------------------------------------------------------------------------
// Общие элементы HUD
// ---------------------------------------------------------------------------
@Composable
fun BrandToggle(skin: UiSkin, onClick: () -> Unit) {
    val nikon = skin == UiSkin.NIKON
    Row(
        Modifier.background(Color(0x66000000), RoundedCornerShape(6.dp))
            .border(1.dp, skinAccent(skin), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("NIKON", color = if (nikon) NIKON_YELLOW else Color(0xFF666666),
            fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(" / ", color = Color(0xFF666666), fontSize = 11.sp)
        Text("CANON", color = if (!nikon) CANON_RED else Color(0xFF666666),
            fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun QuickToggle(label: String, on: Boolean, accent: Color, rotation: Float = 0f, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) accent.copy(alpha = 0.22f) else Color(0x55000000), RoundedCornerShape(5.dp))
            .border(1.dp, if (on) accent else Color(0x44FFFFFF), RoundedCornerShape(5.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (on) accent else Color(0xFFBBBBBB), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer { rotationZ = rotation })
    }
}

@Composable
fun LensChip(label: String, active: Boolean, accent: Color, rotation: Float = 0f, onClick: () -> Unit) {
    Box(
        Modifier.background(if (active) accent else Color(0x55000000), CircleShape)
            .border(1.dp, if (active) accent else Color(0x55FFFFFF), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 9.dp)
    ) {
        Text(label, color = if (active) Color.Black else Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.graphicsLayer { rotationZ = rotation })
    }
}

@Composable
fun RoundBtn(label: String, accent: Color, rotation: Float = 0f, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp)
            .background(Color(0x55000000), CircleShape)
            .border(1.5.dp, accent.copy(alpha = 0.75f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer { rotationZ = rotation })
    }
}

@Composable
fun ShutterButton(accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(74.dp)
            .border(3.dp, accent, CircleShape)
            .padding(5.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(58.dp).background(Color.White, CircleShape))
    }
}

@Composable
fun Chip(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (active) Color(0xFFC9A227) else Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (!enabled) Color.Gray else if (active) Color.Black else Color.White,
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------
// Зум: вертикальный слайдер (лог-шкала) + пресеты кратности
// ---------------------------------------------------------------------------
@Composable
fun ZoomSlider(
    modifier: Modifier = Modifier,
    zoom: Float, minZoom: Float, maxZoom: Float,
    hwMax: Float = maxZoom,
    accent: Color,
    labelRotation: Float = 0f,
    onZoom: (Float) -> Unit
) {
    val logMin = ln(minZoom.toDouble())
    val logMax = ln(maxZoom.toDouble().coerceAtLeast(minZoom.toDouble() * 1.01))
    val frac = (((ln(zoom.toDouble()) - logMin) / (logMax - logMin)).toFloat()).coerceIn(0f, 1f)
    val zoomState = androidx.compose.runtime.rememberUpdatedState(zoom)
    val digital = zoom > hwMax + 0.05f

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.background(Color(0x88000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(
                String.format("%.1f×", zoom) + if (digital) " цифр" else "",
                color = if (digital) Color(0xFFFF9800) else accent, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.graphicsLayer { rotationZ = labelRotation }
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.width(42.dp).height(250.dp)
                .background(Color(0x44000000), RoundedCornerShape(21.dp))
                .pointerInput(minZoom, maxZoom) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val cur = zoomState.value.toDouble().coerceIn(minZoom.toDouble(), maxZoom.toDouble())
                        val curFrac = ((ln(cur) - logMin) / (logMax - logMin)).coerceIn(0.0, 1.0)
                        val nf = (curFrac - drag.y / size.height).coerceIn(0.0, 1.0)
                        onZoom(mexp(logMin + nf * (logMax - logMin)).toFloat())
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                val cx = size.width / 2f
                // дорожка
                drawRect(Color(0x66FFFFFF), topLeft = Offset(cx - 1.5f, 0f), size = Size(3f, size.height))
                // деления
                for (i in 0..8) {
                    val y = size.height * i / 8f
                    drawRect(Color(0x88FFFFFF), topLeft = Offset(cx - 7f, y - 1f), size = Size(14f, 2f))
                }
                // отметка аппаратного предела (выше — цифровой кроп)
                if (hwMax < maxZoom - 0.05f) {
                    val hwFrac = (((ln(hwMax.toDouble()) - logMin) / (logMax - logMin)).toFloat()).coerceIn(0f, 1f)
                    val yHw = size.height * (1f - hwFrac)
                    drawRect(Color(0xFFFF9800), topLeft = Offset(cx - 10f, yHw - 1.2f), size = Size(20f, 2.4f))
                }
                // заполнение от низа до текущего
                val yPos = size.height * (1f - frac)
                drawRect(accent.copy(alpha = 0.85f), topLeft = Offset(cx - 1.5f, yPos),
                    size = Size(3f, size.height - yPos))
                // ползунок
                drawCircle(accent, radius = 13f, center = Offset(cx, yPos))
                drawCircle(Color.White, radius = 5f, center = Offset(cx, yPos))
            }
        }
    }
}

@Composable
fun ZoomPresets(
    controller: CameraController,
    minZoom: Float, maxZoom: Float,
    accent: Color, rotation: Float
) {
    val zoom = controller.settings.zoomRatio
    // Пресеты по РЕАЛЬНОМУ диапазону камеры (до самого максимума, что отдаёт Camera2)
    var presets = listOf(0.6f, 1f, 2f, 3f, 5f, 10f, 15f, 20f, 30f, 50f, 100f)
        .filter { it >= minZoom - 0.01f && it <= maxZoom + 0.01f }
    if (presets.isNotEmpty() && maxZoom > presets.last() + 0.5f) presets = presets + maxZoom
    if (presets.size > 6) {
        // Равномерно прореживаем, но края (мин и макс) оставляем всегда
        val n = presets.size
        presets = (0..5).map { presets[Math.round(it * (n - 1) / 5.0).toInt()] }.distinct()
    }
    if (presets.size < 2) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        presets.forEach { p ->
            val active = kotlin.math.abs(zoom - p) < 0.05f
            val label = if (p == p.toInt().toFloat()) "${p.toInt()}×" else String.format("%.1f×", p)
            Box(
                Modifier.padding(horizontal = 4.dp)
                    .background(if (active) accent else Color(0x66000000), CircleShape)
                    .border(1.dp, if (active) accent else Color(0x55FFFFFF), CircleShape)
                    .clickable { controller.setZoom(p) }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(label, color = if (active) Color.Black else Color.White,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Панель тонкой настройки (ползунки) — общая
// ---------------------------------------------------------------------------
@Composable
fun ManualPanel(controller: CameraController) {
    val caps = controller.activeCaps
    val s = controller.settings
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xCC000000), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .height(320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        StyleRow(controller)
        Spacer(Modifier.height(6.dp))
        LutRow(controller)
        Spacer(Modifier.height(6.dp))
        val isoR = caps?.isoRange
        ParamSlider(
            label = "ISO", auto = s.autoIso,
            valueText = if (s.autoIso) "AUTO" else s.iso.toString(),
            enabled = isoR != null,
            fraction = if (isoR != null) (s.iso - isoR.lower).toFloat() / (isoR.upper - isoR.lower).coerceAtLeast(1) else 0f,
            onAuto = { controller.updateSettings { it.copy(autoIso = !it.autoIso) } },
            onFraction = { f ->
                if (isoR != null) {
                    val v = (isoR.lower + f * (isoR.upper - isoR.lower)).toInt()
                    controller.updateSettings { it.copy(iso = v, autoIso = false) }
                }
            }
        )
        val expR = caps?.exposureNanosRange
        ParamSlider(
            label = "Выдержка", auto = s.autoShutter,
            valueText = if (s.autoShutter) "AUTO" else formatShutter(s.exposureNanos),
            enabled = expR != null,
            fraction = if (expR != null) expToFraction(s.exposureNanos, expR.lower, expR.upper) else 0f,
            onAuto = { controller.updateSettings { it.copy(autoShutter = !it.autoShutter) } },
            onFraction = { f ->
                if (expR != null) {
                    val v = fractionToExp(f, expR.lower, expR.upper)
                    controller.updateSettings { it.copy(exposureNanos = v, autoShutter = false) }
                }
            }
        )
        val evR = caps?.evRange
        if (evR != null && evR.upper > evR.lower) {
            ParamSlider(
                label = "Эксп. корр.", auto = false, showAuto = false,
                valueText = String.format("%+.1f EV", s.evComp * caps.evStep),
                enabled = true,
                fraction = (s.evComp - evR.lower).toFloat() / (evR.upper - evR.lower),
                onAuto = {},
                onFraction = { f ->
                    val v = (evR.lower + f * (evR.upper - evR.lower)).toInt()
                    controller.updateSettings { it.copy(evComp = v) }
                }
            )
        }
        ParamSlider(
            label = "ББ (K)", auto = s.autoWb,
            valueText = if (s.autoWb) "AUTO" else "${s.wbKelvin}K",
            enabled = caps?.supportsManualPostProc == true,
            fraction = (s.wbKelvin - 2000f) / 8000f,
            onAuto = { controller.updateSettings { it.copy(autoWb = !it.autoWb) } },
            onFraction = { f ->
                val v = (2000 + f * 8000).toInt()
                controller.updateSettings { it.copy(wbKelvin = v, autoWb = false) }
            }
        )
        val fm = caps?.focusMin ?: 0f
        ParamSlider(
            label = "Фокус", auto = s.autoFocus,
            valueText = if (s.autoFocus) "AF" else if (s.focusDistance <= 0.01f) "∞" else String.format("%.2f m", 1f / s.focusDistance),
            enabled = caps?.hasManualFocus == true,
            fraction = if (fm > 0f) s.focusDistance / fm else 0f,
            onAuto = { controller.updateSettings { it.copy(autoFocus = !it.autoFocus) } },
            onFraction = { f ->
                controller.updateSettings { it.copy(focusDistance = f * fm, autoFocus = false) }
            }
        )
    }
}

@Composable
fun ParamSlider(
    label: String, auto: Boolean, valueText: String, enabled: Boolean, fraction: Float,
    showAuto: Boolean = true, onAuto: () -> Unit, onFraction: (Float) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (enabled) Color.White else Color.Gray, fontSize = 12.sp,
            modifier = Modifier.width(92.dp))
        Slider(
            value = fraction.coerceIn(0f, 1f), onValueChange = onFraction,
            enabled = enabled && !auto, modifier = Modifier.weight(1f)
        )
        Text(valueText, color = if (auto) Color(0xFFC9A227) else Color.White, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(72.dp))
        if (showAuto) Chip("A", active = auto, enabled = enabled, onClick = onAuto)
    }
}

@Composable
fun StyleRow(controller: CameraController) {
    val current = controller.settings.pictureStyle
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PictureStyle.values().forEach { st ->
                Chip(st.title, active = st == current, enabled = true) {
                    controller.setPictureStyle(st)
                }
            }
        }
        Text("стиль виден в превью и применяется к снимку", color = Color(0xFF888888), fontSize = 9.sp)
    }
}

@Composable
fun LutRow(controller: CameraController) {
    val context = LocalContext.current
    val current = controller.lutName

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Thread {
                try {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() } ?: return@Thread
                    val lut = Lut3D.parseCube(text)
                    if (lut != null) controller.setLut(lut, "Свой .cube")
                    else controller.setLut(null, "Нет")
                } catch (_: Throwable) {
                }
            }.start()
        }
    }

    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Chip("Нет", active = current == "Нет", enabled = true) { controller.setLut(null, "Нет") }
            Chip("Тёплая", active = current == "Тёплая", enabled = true) { controller.setLut(Lut3D.warmFilm(), "Тёплая") }
            Chip("Винтаж", active = current == "Винтаж", enabled = true) { controller.setLut(Lut3D.vintage(), "Винтаж") }
            Chip("Тил-оранж", active = current == "Тил-оранж", enabled = true) { controller.setLut(Lut3D.tealOrange(), "Тил-оранж") }
            Chip("Загрузить .cube", active = current == "Свой .cube", enabled = true) { picker.launch(arrayOf("*/*")) }
        }
        if (controller.currentLut() != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Сила LUT", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(92.dp))
                Slider(value = controller.lutMix, onValueChange = { controller.updateLutMix(it) },
                    modifier = Modifier.weight(1f))
                Text("${(controller.lutMix * 100).toInt()}%", color = Color.White, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Гистограмма
// ---------------------------------------------------------------------------
@Composable
fun Histogram(bins: IntArray, clipPct: Float) {
    val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
    Column {
        Box(
            Modifier.width(160.dp).height(44.dp)
                .background(Color(0x66000000), RoundedCornerShape(6.dp))
                .padding(4.dp)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val bw = size.width / bins.size
                bins.forEachIndexed { i, v ->
                    val bh = size.height * (v.toFloat() / max)
                    drawRect(
                        color = if (i >= 62) Color(0xFFE06050) else Color(0xCCFFFFFF),
                        topLeft = Offset(i * bw, size.height - bh),
                        size = Size(bw * 0.85f, bh)
                    )
                }
            }
        }
        if (clipPct >= 1f) {
            Text("⚠ пересвет ${String.format("%.0f", clipPct)}%",
                color = Color(0xFFE06050), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Утилиты формата
// ---------------------------------------------------------------------------
fun formatShutter(nanos: Long): String {
    val sec = nanos / 1e9
    return if (sec >= 1.0) String.format("%.1f\"", sec)
    else "1/" + Math.round(1.0 / sec).toInt()
}

fun fractionToExp(t: Float, lo: Long, hi: Long): Long {
    val a = ln(lo.toDouble()); val b = ln(hi.toDouble())
    return mexp(a + (b - a) * t).toLong().coerceIn(lo, hi)
}

fun expToFraction(v: Long, lo: Long, hi: Long): Float {
    val a = ln(lo.toDouble()); val b = ln(hi.toDouble())
    return (((ln(v.toDouble()) - a) / (b - a)).toFloat()).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------
// Баннер обновления (OTA)
// ---------------------------------------------------------------------------
@Composable
fun BoxScope.UpdateBanner(
    rel: UpdateManager.Release, progress: Int,
    onUpdate: () -> Unit, onDismiss: () -> Unit
) {
    Row(
        Modifier.align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 54.dp, start = 12.dp, end = 12.dp)
            .fillMaxWidth()
            .background(Color(0xF01A1A1A), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFFFFC400), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Обновление ${rel.versionName}", color = Color.White,
                fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (rel.notes.isNotBlank()) {
                Text(rel.notes, color = Color(0xFFAAAAAA), fontSize = 11.sp, maxLines = 2)
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            progress in 0..99 -> Text("$progress%", color = Color(0xFFFFC400),
                fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            progress == 100 -> Text("установка…", color = Color(0xFFFFC400), fontSize = 12.sp)
            progress == DL_ERROR -> Box(
                Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                    .clickable { onUpdate() }.padding(horizontal = 12.dp, vertical = 7.dp)
            ) { Text("повторить", color = Color.White, fontSize = 12.sp) }
            else -> {
                Box(
                    Modifier.background(Color(0xFFFFC400), RoundedCornerShape(6.dp))
                        .clickable { onUpdate() }.padding(horizontal = 14.dp, vertical = 7.dp)
                ) { Text("Обновить", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(4.dp))
                Text("✕", color = Color(0xFF888888), fontSize = 16.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp))
            }
        }
    }
}
