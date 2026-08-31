package com.denis.naturalcam

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Превью камеры через OpenGL: кадры Camera2 -> OES-текстура -> шейдер со стилем -> экран.
 * Стиль изображения виден в реальном времени (не только на снимке).
 */
@Composable
fun GLCameraView(controller: CameraController, modifier: Modifier = Modifier) {
    val glView = remember { mutableGl() }

    DisposableEffect(Unit) {
        onDispose { glView.first.onPause() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).also { glv ->
                glv.setEGLContextClientVersion(3)
                val renderer = GLPreviewRenderer(
                    requestRender = { glv.requestRender() },
                    onSurfaceReady = { st, w, h -> controller.setSurface(st, w, h) },
                    getStyleParams = { PictureStyleProcessor.params(controller.settings.pictureStyle) },
                    getSensorOrientation = { controller.sensorOrientation },
                    getIsFront = { controller.isFront },
                    getLut = { controller.currentLut() },
                    getLutVersion = { controller.lutVersion },
                    getLutMix = { controller.lutMix },
                    getPeaking = { controller.focusPeaking },
                    getZebra = { controller.zebra },
                    getPreviewSize = { controller.previewBufferWidth() to controller.previewBufferHeight() },
                    getDisplayRotationDeg = {
                        when (glv.display?.rotation ?: android.view.Surface.ROTATION_0) {
                            android.view.Surface.ROTATION_90 -> 90
                            android.view.Surface.ROTATION_180 -> 180
                            android.view.Surface.ROTATION_270 -> 270
                            else -> 0
                        }
                    },
                    getCalRot = { controller.calRot },
                    getCalMirror = { controller.calMirror },
                    getDigitalZoom = { controller.digitalFactor() },
                    onTransform = { rot, fx, fy, m, st -> controller.updatePreviewTransform(rot, fx, fy, m, st) }
                )
                glv.setRenderer(renderer)
                glv.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glView.second(glv)
            }
        },
        onRelease = { it.onPause() }
    )
}

/** Небольшой держатель, чтобы DisposableEffect мог поставить превью на паузу. */
private class GlHolder {
    var view: GLSurfaceView? = null
    fun onPause() { view?.onPause() }
}

private fun mutableGl(): Pair<GlHolder, (GLSurfaceView) -> Unit> {
    val holder = GlHolder()
    return holder to { v: GLSurfaceView -> holder.view = v; v.onResume() }
}
