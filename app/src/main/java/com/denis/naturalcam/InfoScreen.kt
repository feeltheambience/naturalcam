package com.denis.naturalcam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun InfoScreen(controller: CameraController, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Назад", tint = Color.White)
            }
            Text("Разведчик камер", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} • Android ${android.os.Build.VERSION.RELEASE} • NaturalCam ${BuildConfig.VERSION_NAME}",
            color = Color(0xFFDDDDDD), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            "Что твой телефон реально отдаёт стороннему приложению через Camera2. " +
                "Смотри на RAW и «Ручной сенсор» — без них «естественная» съёмка без ИИ невозможна. " +
                "Если фото «не очень» — пришли скриншот этого экрана.",
            color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)
        )

        controller.capabilities.forEach { c ->
            CameraCard(c, active = c.id == controller.activeId)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CameraCard(c: CameraCapabilities, active: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (active) Color(0xFF2A2410) else Color(0xFF1C1C1C),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Камера ${c.id}  •  ${c.facing}", color = Color.White,
                fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(c.hardwareLevel, color = levelColor(c.hardwareLevel),
                fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Flag("RAW / DNG", c.supportsRaw)
        Flag("Ручной сенсор (ISO/выдержка)", c.supportsManualSensor)
        Flag("Ручной ББ (post-proc)", c.supportsManualPostProc)
        Flag("Ручной фокус", c.hasManualFocus)

        Spacer(Modifier.height(6.dp))
        Info("ISO", c.isoRange?.let { "${it.lower}–${it.upper}" } ?: "—")
        Info("Выдержка", c.exposureNanosRange?.let {
            "${formatShutter(it.lower)} … ${formatShutter(it.upper)}"
        } ?: "—")
        Info("Зум (плавный)", c.zoomRange?.let { String.format("%.1fx – %.1fx", it.lower, it.upper) }
            ?: "цифровой до ${String.format("%.1fx", c.maxDigitalZoom)}")
        Info("Экспокоррекция", c.evRange?.let { "${it.lower}…${it.upper} шаг ${c.evStep}" } ?: "—")
        Info("Ориентация сенсора", "${c.sensorOrientationDeg}°")
        Info("Фокусные", if (c.focalLengthsMm.isEmpty()) "—"
            else c.focalLengthsMm.joinToString { String.format("%.1f", it) } + " мм")
        Info("Макс. JPEG", c.maxJpegSize?.let { "${it.width}×${it.height}" } ?: "—")
        Info("Макс. RAW", c.maxRawSize?.let { "${it.width}×${it.height}" } ?: "—")
        if (c.physicalIds.isNotEmpty()) {
            Info("Физ. модули", c.physicalIds.joinToString())
        }
    }
}

@Composable
private fun Flag(label: String, on: Boolean) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(if (on) "✓ " else "✗ ", color = if (on) Color(0xFF7BC67B) else Color(0xFFBB5555),
            fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Text(label, color = if (on) Color.White else Color.Gray, fontSize = 13.sp)
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text("$label: ", color = Color(0xFF999999), fontSize = 12.sp)
        Text(value, color = Color(0xFFDDDDDD), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun levelColor(level: String): Color = when (level) {
    "LEVEL_3", "FULL" -> Color(0xFF7BC67B)
    "LIMITED" -> Color(0xFFE0C040)
    else -> Color(0xFFBB5555)
}
