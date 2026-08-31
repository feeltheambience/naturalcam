package com.denis.naturalcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // Контроллер живёт на уровне активности — чтобы аппаратные кнопки
    // (качелька громкости, колесо зума, кнопка затвора) могли им управлять.
    lateinit var controller: CameraController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = CameraController(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFC9A227))) {
                val context = LocalContext.current
                var granted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { ok -> granted = ok }

                if (granted) {
                    RootScreen(controller)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                            Text("Разрешить доступ к камере", Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Аппаратное управление: громкость = зум, затвор = снимок.
    // Неизвестные кнопки показываем в статусе — чтобы привязать колесо зума.
    // ------------------------------------------------------------------
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::controller.isInitialized) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_ZOOM_IN -> {
                controller.stepZoom(+1); true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ZOOM_OUT -> {
                controller.stepZoom(-1); true
            }
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event?.repeatCount == 0) controller.capturePhoto(); true
            }
            KeyEvent.KEYCODE_FOCUS -> true   // полунажатие затвора — глотаем (AF непрерывный)
            KeyEvent.KEYCODE_BACK -> super.onKeyDown(keyCode, event)
            else -> {
                // Диагностика для колеса/кнопок аксессуара: покажем код в статусе
                controller.noteHardwareInput("кнопка: код $keyCode")
                super.onKeyDown(keyCode, event)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (::controller.isInitialized && event.action == MotionEvent.ACTION_SCROLL) {
            // Колесо/энкодер может приходить осью скролла
            val v = event.getAxisValue(MotionEvent.AXIS_SCROLL)
                .takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                controller.scrollZoom(v)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
