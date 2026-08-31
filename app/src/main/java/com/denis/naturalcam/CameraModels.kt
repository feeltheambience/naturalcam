package com.denis.naturalcam

import android.util.Range
import android.util.Size

/**
 * Статичные возможности одной физической/логической камеры.
 * Заполняется один раз при опросе устройства (экран "Разведчик").
 */
data class CameraCapabilities(
    val id: String,
    val facing: String,                 // "back" / "front" / "external"
    val hardwareLevel: String,          // LEGACY / LIMITED / FULL / LEVEL_3
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,  // ручные ISO/выдержка
    val supportsManualPostProc: Boolean,// ручной ББ и т.п.
    val isoRange: Range<Int>?,
    val exposureNanosRange: Range<Long>?,
    val focusMin: Float,                // 0 = бесконечность, макс = мин.дистанция (диоптрии)
    val hasManualFocus: Boolean,
    val evRange: Range<Int>?,
    val evStep: Double,
    val zoomRange: Range<Float>?,       // CONTROL_ZOOM_RATIO_RANGE (API 30+)
    val maxDigitalZoom: Float,
    val focalLengthsMm: List<Float>,
    val maxJpegSize: Size?,
    val maxRawSize: Size?,
    val physicalIds: List<String>,      // для логической камеры — её физические модули
    val aperture: Float? = null,        // диафрагма объектива (фиксированная у телефонов)
    val sensorOrientationDeg: Int = 90  // ориентация сенсора (ключ к правильному повороту превью)
)

/** Скин интерфейса: раскладка и акценты в стиле бренда. */
enum class UiSkin { NIKON, CANON }

/** Режимы экспозиции "как в фотоаппарате". */
enum class ExposureMode { P, S, M }
//  P — программный (авто ISO+выдержка), S — приоритет выдержки, M — полный ручной.
//  Диафрагму телефон не меняет физически, поэтому классический "A" смысла не имеет.

/** Текущие ручные настройки, применяемые к CaptureRequest. */
data class ManualSettings(
    val exposureMode: ExposureMode = ExposureMode.P,
    val autoIso: Boolean = true,
    val iso: Int = 100,
    val autoShutter: Boolean = true,
    val exposureNanos: Long = 8_000_000L, // 1/125 с по умолчанию
    val autoWb: Boolean = true,
    val wbKelvin: Int = 5200,
    val autoFocus: Boolean = true,
    val focusDistance: Float = 0f,        // диоптрии; 0 = бесконечность
    val evComp: Int = 0,                  // шаги экспокоррекции
    val zoomRatio: Float = 1.0f,
    val captureRaw: Boolean = false,
    val pictureStyle: PictureStyle = PictureStyle.NEUTRAL
)

/**
 * "Стили изображения" в духе Canon Picture Styles.
 * На этапе MVP-1 это просто пометка в EXIF/имени файла + лёгкая коррекция.
 * Полноценные 3D-LUT подключим на этапе GPU-проявки RAW.
 */
enum class PictureStyle(val title: String) {
    NEUTRAL("Neutral"),
    STANDARD("Standard"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    FILM("Film")
}
