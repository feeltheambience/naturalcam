package com.denis.naturalcam

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.OutputStream
import java.util.concurrent.Executor
import kotlin.math.abs

private const val TAG = "NaturalCam"

/**
 * Вся работа с Camera2 в одном месте. Держит своё состояние в Compose-стейтах,
 * поэтому UI просто читает поля и перерисовывается.
 */
class CameraController(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // --- Наблюдаемое состояние для UI ---
    var capabilities by mutableStateOf<List<CameraCapabilities>>(emptyList()); private set
    var activeId by mutableStateOf<String?>(null); private set
    var settings by mutableStateOf(ManualSettings()); private set
    var status by mutableStateOf("Инициализация…"); private set
    var lastSavedName by mutableStateOf<String?>(null); private set

    // Живая гистограмма яркости (64 корзины) + доля пересветов
    var histogram by mutableStateOf(IntArray(64)); private set
    var clipHighPct by mutableStateOf(0f); private set

    // Ориентация сенсора активной камеры (для поворота кадра в GL-превью)
    var sensorOrientation by mutableStateOf(90); private set
    var isFront by mutableStateOf(false); private set

    // Скин интерфейса (Nikon / Canon)
    var uiSkin by mutableStateOf(UiSkin.NIKON); private set
    fun toggleSkin() { uiSkin = if (uiSkin == UiSkin.NIKON) UiSkin.CANON else UiSkin.NIKON }

    // --- Калибровка превью (если формула ориентации не совпала с конкретным сенсором) ---
    // Пользователь крутит до правильной картинки; сохраняется на устройстве per-camera.
    private val prefs by lazy { context.getSharedPreferences("naturalcam", Context.MODE_PRIVATE) }
    var calRot by mutableStateOf(0); private set        // добавка к повороту, шаг 90°
    var calMirror by mutableStateOf(false); private set // доп. зеркало
    private fun loadCal(id: String) {
        calRot = prefs.getInt("cal_rot_$id", 0)
        calMirror = prefs.getBoolean("cal_mir_$id", false)
    }
    fun bumpCalRot() {
        calRot = (calRot + 90) % 360
        activeId?.let { prefs.edit().putInt("cal_rot_$it", calRot).apply() }
    }
    fun toggleCalMirror() {
        calMirror = !calMirror
        activeId?.let { prefs.edit().putBoolean("cal_mir_$it", calMirror).apply() }
    }
    fun resetCal() {
        calRot = 0; calMirror = false
        activeId?.let { prefs.edit().remove("cal_rot_$it").remove("cal_mir_$it").apply() }
    }

    // Подсказка про CAL — скрывается навсегда крестиком
    var calHintHidden by mutableStateOf(false); private set
    fun hideCalHint() {
        calHintHidden = true
        prefs.edit().putBoolean("cal_hint_hidden", true).apply()
    }

    /**
     * Схема ориентации изменилась в v0.8 (портрет-лок): калибровки, сделанные при
     * свободном вращении окна (v0.7), теперь сдвигают картинку на 90° — чистим их разом.
     */
    private fun migrateCal() {
        calHintHidden = prefs.getBoolean("cal_hint_hidden", false)
        if (prefs.getInt("cal_schema", 1) < 2) {
            val stale = prefs.all.keys.filter { it.startsWith("cal_rot_") || it.startsWith("cal_mir_") }
            val e = prefs.edit()
            stale.forEach { e.remove(it) }
            e.putInt("cal_schema", 2).apply()
            calRot = 0; calMirror = false
        }
    }

    // --- Физическое положение телефона (окно всегда в портрете, как у всех камер) ---
    // uiRotation крутит иконки интерфейса; deviceOrientation идёт в EXIF снимка.
    var uiRotation by mutableStateOf(0); private set          // 0/90/180/270, по часовой
    @Volatile private var deviceOrientation = 0
    private var orientationListener: android.view.OrientationEventListener? = null
    private fun startOrientationTracking() {
        orientationListener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val snapped = ((o + 45) / 90 * 90) % 360
                deviceOrientation = snapped
                if (uiRotation != snapped) uiRotation = snapped
            }
        }.apply { if (canDetectOrientation()) enable() }
    }

    /** Ориентация JPEG по официальной формуле Camera2 — снимок в галерее лежит как снимался. */
    private fun jpegOrientation(): Int {
        var d = deviceOrientation
        if (isFront) d = -d
        return (sensorOrientation + d + 360) % 360
    }

    // Размер буфера превью (для коррекции пропорций в GL)
    fun previewBufferWidth(): Int = previewSize.width
    fun previewBufferHeight(): Int = previewSize.height

    // 3D-LUT (киношная/плёночная цветокоррекция)
    var lutName by mutableStateOf("Нет"); private set
    var lutMix by mutableStateOf(1f); private set
    private var lut: Lut3D? = null
    var lutVersion by mutableStateOf(0); private set
    fun currentLut(): Lut3D? = lut
    fun setLut(newLut: Lut3D?, name: String) {
        lut = newLut
        lutName = name
        lutVersion++    // сигнал рендереру перезалить текстуру
    }
    fun updateLutMix(v: Float) { lutMix = v.coerceIn(0f, 1f) }

    // Операторские оверлеи
    var focusPeaking by mutableStateOf(false); private set
    var zebra by mutableStateOf(false); private set
    var gridLines by mutableStateOf(false); private set
    fun togglePeaking() { focusPeaking = !focusPeaking }
    fun toggleZebra() { zebra = !zebra }
    fun toggleGrid() { gridLines = !gridLines }

    private var afRegion: android.hardware.camera2.params.MeteringRectangle? = null

    // Актуальный трансформ превью (пишет GL-рендерер каждый кадр) — нужен тап-фокусу,
    // чтобы точка на экране совпадала с точкой в кадре после кропа и поворота.
    @Volatile private var pRot = 90
    @Volatile private var pFracX = 1f
    @Volatile private var pFracY = 1f
    @Volatile private var pMirror = false
    var stRotInfo by mutableStateOf(0); private set   // поворот, вшитый прошивкой в ST-матрицу
    fun updatePreviewTransform(rot: Int, fx: Float, fy: Float, mirrored: Boolean, stRot: Int) {
        pRot = rot; pFracX = fx; pFracY = fy; pMirror = mirrored
        if (stRotInfo != stRot) stRotInfo = stRot
    }

    /** Режим экспозиции в стиле фотоаппарата (P/S/M). */
    fun setExposureMode(m: ExposureMode) {
        settings = when (m) {
            ExposureMode.P -> settings.copy(exposureMode = m, autoIso = true, autoShutter = true)
            ExposureMode.S -> settings.copy(exposureMode = m, autoIso = true, autoShutter = false)
            ExposureMode.M -> settings.copy(exposureMode = m, autoIso = false, autoShutter = false)
        }
        applyRepeating()
    }

    /**
     * Тап по превью: точка фокуса/экспозиции. nx,ny — 0..1 в координатах экрана (y вниз).
     * Повторяет цепочку шейдера: экран → кроп(frac) → зеркало → поворот → координаты кадра.
     */
    fun focusAt(nx: Float, ny: Float) {
        val c = characteristics ?: return
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        // экран (y вниз) -> нормализованные texcoords (y вверх), относительно центра
        var dx = nx - 0.5f
        var dy = (1f - ny) - 0.5f
        dx *= pFracX; dy *= pFracY                      // кроп (cover)
        if (pMirror) dx = -dx                            // итоговое зеркало (фронт/калибровка)
        val rad = Math.toRadians(pRot.toDouble())        // поворот как в шейдере
        val rx = (dx * Math.cos(rad) - dy * Math.sin(rad)).toFloat()
        val ry = (dx * Math.sin(rad) + dy * Math.cos(rad)).toFloat()
        val u = (rx + 0.5f).coerceIn(0f, 1f)
        val v = (1f - (ry + 0.5f)).coerceIn(0f, 1f)      // texcoords y-вверх -> строки кадра y-вниз
        val w = active.width(); val h = active.height()
        val cx = (u * w).toInt(); val cy = (v * h).toInt()
        val half = (w * 0.07f).toInt().coerceAtLeast(50)
        afRegion = android.hardware.camera2.params.MeteringRectangle(
            (cx - half).coerceIn(0, w - 1), (cy - half).coerceIn(0, h - 1),
            (half * 2).coerceAtMost(w), (half * 2).coerceAtMost(h),
            android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
        )
        if (maxAf <= 0) return
        applyRepeating()
        // Одноразовый триггер автофокуса
        val s = session ?: return
        val cam = device ?: return
        val preview = previewSurface ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(preview)
            applyToRequest(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, handler)
        } catch (_: Exception) {}
    }

    val activeCaps: CameraCapabilities?
        get() = capabilities.firstOrNull { it.id == activeId }

    // --- Внутреннее состояние Camera2 ---
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var previewSurface: Surface? = null
    private var pendingTexture: SurfaceTexture? = null
    private var previewSize: Size = Size(1920, 1080)
    private var viewWidth = 1080
    private var viewHeight = 1920

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var analysisReader: ImageReader? = null
    private var frameCount = 0
    private var lastResult: TotalCaptureResult? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val executor = Executor { r -> (handler ?: Handler(context.mainLooper)).post(r) }

    // ---------------------------------------------------------------------
    // 1. РАЗВЕДКА: опрос всех камер устройства
    // ---------------------------------------------------------------------
    fun probeAll() {
        migrateCal()
        val list = mutableListOf<CameraCapabilities>()
        try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val facingInt = c.get(CameraCharacteristics.LENS_FACING)
                val facing = when (facingInt) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }
                val hwLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "?"
                }
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val maxJpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
                val maxRaw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }

                val zoomRange: Range<Float>? = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                val focusMin = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val physical: List<String> = try {
                    c.physicalCameraIds.toList()
                } catch (e: Throwable) { emptyList() }

                list.add(
                    CameraCapabilities(
                        id = id,
                        facing = facing,
                        hardwareLevel = hwLevel,
                        supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                        supportsManualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                        supportsManualPostProc = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
                        isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                        exposureNanosRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                        focusMin = focusMin,
                        hasManualFocus = focusMin > 0f,
                        evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
                        evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0,
                        zoomRange = zoomRange,
                        maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
                        focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
                        maxJpegSize = maxJpeg,
                        maxRawSize = maxRaw,
                        physicalIds = physical,
                        aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
                        sensorOrientationDeg = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "probeAll failed", e)
            status = "Ошибка опроса камер: ${e.message}"
        }
        capabilities = list
        if (activeId == null) {
            // По умолчанию — основная тыловая камера с максимальным JPEG
            activeId = list.filter { it.facing == "back" }
                .maxByOrNull { (it.maxJpegSize?.width ?: 0).toLong() * (it.maxJpegSize?.height ?: 0) }?.id
                ?: list.firstOrNull()?.id
        }
        tryOpen()   // если поверхность уже готова — камера стартует сама, без ручного переключения
    }

    // ---------------------------------------------------------------------
    // 2. Жизненный цикл
    // ---------------------------------------------------------------------
    fun startBackground() {
        thread = HandlerThread("cam-bg").apply { start() }
        handler = Handler(thread!!.looper)
        startOrientationTracking()
        tryOpen()
    }

    fun setSurface(texture: SurfaceTexture, viewW: Int, viewH: Int) {
        pendingTexture = texture
        viewWidth = viewW.coerceAtLeast(1)
        viewHeight = viewH.coerceAtLeast(1)
        previewSurface = Surface(texture)
        tryOpen()
    }

    fun selectCamera(id: String) {
        if (id == activeId) return
        activeId = id
        closeCameraOnly()
        tryOpen()
    }

    /**
     * Открывает камеру, когда ГОТОВО ВСЁ: bg-поток, список камер (activeId) и поверхность.
     * Вызывается из всех трёх мест готовности — кто последний, тот и запускает.
     * Работает на bg-handler'е (у GL-потока нет Looper'а для Camera2-колбэков).
     */
    private var opening = false
    private fun tryOpen() {
        val h = handler ?: return           // bg-поток ещё не поднят — probeAll() дозапустит
        h.post {
            val id = activeId ?: return@post
            if (previewSurface == null) return@post
            if (opening) return@post
            when {
                device == null -> { opening = true; openCamera(id) }
                session == null -> createSession()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun openCamera(id: String) {
        try {
            characteristics = manager.getCameraCharacteristics(id)
            sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            isFront = characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            loadCal(id)
            status = "Открываю камеру $id…"
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    opening = false
                    device = cam
                    createSession()
                }
                override fun onDisconnected(cam: CameraDevice) {
                    opening = false
                    cam.close(); device = null
                    status = "Камера отключена"
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    opening = false
                    cam.close(); device = null
                    status = "Ошибка камеры: код $error"
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            Log.e(TAG, "openCamera", e)
            status = "Не удалось открыть камеру: ${e.message}"
        }
    }

    private fun choosePreviewSize(viewW: Int, viewH: Int) {
        val c = characteristics ?: try { manager.getCameraCharacteristics(activeId ?: return) } catch (e: Exception) { return }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return
        val targetAspect = if (viewH != 0) viewW.toFloat() / viewH else 9f / 16f
        // Берём не больше 1080p по высоте, ближайшее по соотношению сторон
        previewSize = sizes
            .filter { it.height <= 1080 }
            .ifEmpty { sizes.toList() }
            .minByOrNull { abs((it.width.toFloat() / it.height) - (1f / targetAspect)) + abs(it.height - 1080) / 4000f }
            ?: previewSize
    }

    private fun createSession() {
        val cam = device ?: return
        val preview = previewSurface ?: return
        val caps = activeCaps
        try {
            session?.close()
            val c = characteristics!!

            // Размер превью подбираем под АКТИВНУЮ камеру и текущий размер вью
            choosePreviewSize(viewWidth, viewHeight)
            pendingTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

            val jpegSize = caps?.maxJpegSize ?: Size(4000, 3000)
            jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    reader.acquireNextImage()?.use { img ->
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        saveJpeg(bytes)
                    }
                }, handler)
            }

            rawReader = null
            if (settings.captureRaw && caps?.supportsRaw == true && caps.maxRawSize != null) {
                val rs = caps.maxRawSize
                rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireNextImage()
                        val result = lastResult
                        if (image != null && result != null) {
                            saveDng(c, result, image)
                        }
                        image?.close()
                    }, handler)
                }
            }

            // Небольшой YUV-поток для анализа кадра (гистограмма).
            // В RAW-режиме отключаем: комбинация preview+YUV+JPEG+RAW не гарантируется
            // спецификацией Camera2 и может не сконфигурироваться на части устройств.
            analysisReader = null
            if (!settings.captureRaw) {
                analysisReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val img = reader.acquireLatestImage()
                        if (img != null) {
                            try {
                                if (frameCount++ % 3 == 0) computeHistogram(img)
                            } catch (_: Throwable) {
                            } finally {
                                img.close()
                            }
                        }
                    }, handler)
                }
            }

            val surfaces = mutableListOf(preview, jpegReader!!.surface)
            analysisReader?.let { surfaces.add(it.surface) }
            rawReader?.let { surfaces.add(it.surface) }

            val configs = surfaces.map { OutputConfiguration(it) }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, configs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        status = "Готово"
                        applyRepeating()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        status = "Не удалось настроить сессию"
                    }
                }
            )
            cam.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "createSession", e)
            status = "Ошибка сессии: ${e.message}"
        }
    }

    // ---------------------------------------------------------------------
    // 3. Применение ручных настроек к превью
    // ---------------------------------------------------------------------
    fun updateSettings(transform: (ManualSettings) -> ManualSettings) {
        val old = settings
        settings = transform(old)
        // Если поменяли флаг RAW — нужно пересобрать сессию (меняется набор поверхностей)
        if (old.captureRaw != settings.captureRaw) {
            createSession()
        } else {
            applyRepeating()
        }
    }

    fun setZoom(ratio: Float) {
        val r = clampZoom(ratio)
        settings = settings.copy(zoomRatio = r)
        applyRepeating()
    }

    /** Аппаратный максимум зума (что отдаёт Camera2). */
    fun hwMaxZoom(): Float =
        activeCaps?.zoomRange?.upper ?: (activeCaps?.maxDigitalZoom ?: 1f)

    fun minZoom(): Float = activeCaps?.zoomRange?.lower ?: 1f

    /** Полный максимум: аппаратный × цифровой кроп (честный, без ИИ-дорисовки). */
    fun totalMaxZoom(): Float = hwMaxZoom() * DIGITAL_EXTRA

    /** Цифровая добавка сверх аппаратного зума (1.0 = только аппаратный). */
    fun digitalFactor(): Float {
        val hw = hwMaxZoom()
        val z = settings.zoomRatio
        return if (z <= hw) 1f else (z / hw).coerceAtMost(DIGITAL_EXTRA)
    }

    fun clampZoom(ratio: Float): Float = ratio.coerceIn(minZoom(), totalMaxZoom())

    /** Шаг зума с аппаратной кнопки (качелька громкости / кольцо): ~8% за клик. */
    fun stepZoom(dir: Int) {
        val f = if (dir > 0) 1.08f else 1f / 1.08f
        setZoom(settings.zoomRatio * f)
    }

    /** Плавный зум от энкодера/скролла (колесо аксессуара). */
    fun scrollZoom(delta: Float) {
        val f = Math.pow(1.15, delta.toDouble()).toFloat()
        setZoom(settings.zoomRatio * f)
    }

    /** Диагностика неизвестных аппаратных кнопок — код показывается в статус-строке. */
    fun noteHardwareInput(msg: String) { status = msg }

    companion object {
        /** Насколько тянем цифровым кропом сверх аппаратного предела. */
        const val DIGITAL_EXTRA = 3f
    }

    private fun applyToRequest(b: CaptureRequest.Builder) {
        val s = settings
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Зум (плавный, API 30+)
        // Аппаратная часть зума — не выше предела камеры; остаток добирается цифровым кропом
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampZoom(s.zoomRatio).coerceAtMost(hwMaxZoom()))

        // Экспозиция
        val fullAuto = s.autoIso && s.autoShutter
        if (fullAuto) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evComp)
        } else {
            // Хотя бы один параметр ручной → полностью ручная экспозиция.
            // Для оставленного на "авто" параметра берём текущее значение как есть.
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val isoRange = activeCaps?.isoRange
            val expRange = activeCaps?.exposureNanosRange
            val iso = if (isoRange != null) s.iso.coerceIn(isoRange.lower, isoRange.upper) else s.iso
            val exp = if (expRange != null) s.exposureNanos.coerceIn(expRange.lower, expRange.upper) else s.exposureNanos
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 0L)
        }

        // Баланс белого
        if (s.autoWb) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        } else if (activeCaps?.supportsManualPostProc == true) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToGains(s.wbKelvin))
        }

        // Фокус
        if (s.autoFocus || activeCaps?.hasManualFocus != true) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            afRegion?.let { r ->
                val regions = arrayOf(r)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                b.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
            }
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            val fm = activeCaps?.focusMin ?: 10f
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDistance.coerceIn(0f, fm))
        }
    }

    private fun applyRepeating() {
        val s = session ?: return
        val cam = device ?: return
        val preview = previewSurface ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(preview)
            analysisReader?.let { b.addTarget(it.surface) }
            applyToRequest(b)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "applyRepeating", e)
        }
    }

    // ---------------------------------------------------------------------
    // 4. Съёмка
    // ---------------------------------------------------------------------

    fun capturePhoto() {
        val s = session ?: return
        val cam = device ?: return
        val jpeg = jpegReader ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            applyToRequest(b)
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            b.addTarget(jpeg.surface)
            val useRaw = settings.captureRaw && rawReader != null
            if (useRaw) b.addTarget(rawReader!!.surface)

            status = "Съёмка…"
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    lastResult = result
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "capturePhoto", e)
            status = "Ошибка съёмки: ${e.message}"
        }
    }

    // ---------------------------------------------------------------------
    // 5. Сохранение
    // ---------------------------------------------------------------------
    private fun baseName(): String {
        // Без Date.now() усложнений — используем счётчик времени из SystemClock через nanoTime приближённо.
        val stamp = System.currentTimeMillis()
        return "NC_$stamp"
    }

    private fun saveJpeg(rawBytes: ByteArray) {
        val style = settings.pictureStyle
        val bytes = PictureStyleProcessor.apply(rawBytes, style, lut, lutMix, digitalFactor())
        val styleTag = if (style == PictureStyle.NEUTRAL) "" else "_${style.name.lowercase()}"
        val name = "${baseName()}$styleTag.jpg"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NaturalCam")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out: OutputStream -> out.write(bytes) }
                lastSavedName = name
                status = "Сохранено: $name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveJpeg", e)
            status = "Не сохранён JPEG: ${e.message}"
        }
    }

    private fun saveDng(c: CameraCharacteristics, result: TotalCaptureResult, image: android.media.Image) {
        val name = "${baseName()}.dng"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NaturalCam")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                DngCreator(c, result).use { dng ->
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        dng.writeImage(out, image)
                    }
                }
                status = "Сохранён RAW: $name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveDng", e)
            status = "Не сохранён DNG: ${e.message}"
        }
    }

    private fun computeHistogram(img: android.media.Image) {
        val plane = img.planes[0]           // Y (яркость)
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = img.width
        val h = img.height
        val bins = IntArray(64)
        var clipped = 0
        var total = 0
        val step = 4
        var y = 0
        while (y < h) {
            val rowStart = y * rowStride
            var x = 0
            while (x < w) {
                val idx = rowStart + x * pixStride
                if (idx < buf.limit()) {
                    val v = buf.get(idx).toInt() and 0xFF
                    bins[v shr 2]++
                    if (v >= 250) clipped++
                    total++
                }
                x += step
            }
            y += step
        }
        histogram = bins
        clipHighPct = if (total > 0) clipped * 100f / total else 0f
    }

    fun setPictureStyle(style: PictureStyle) {
        settings = settings.copy(pictureStyle = style)
    }

    // ---------------------------------------------------------------------
    // Утилиты
    // ---------------------------------------------------------------------
    /** Грубое приближение "цветовая температура → усиления RGB" для ручного ББ. */
    private fun kelvinToGains(kelvin: Int): android.hardware.camera2.params.RggbChannelVector {
        val t = kelvin.coerceIn(2000, 10000) / 100.0
        // Формула Tanner Helland (упрощённая), затем инвертируем в усиления.
        val r: Double
        val g: Double
        val b: Double
        if (t <= 66) {
            r = 255.0
            g = (99.4708025861 * Math.log(t) - 161.1195681661).coerceIn(0.0, 255.0)
            b = if (t <= 19) 0.0 else (138.5177312231 * Math.log(t - 10) - 305.0447927307).coerceIn(0.0, 255.0)
        } else {
            r = (329.698727446 * Math.pow(t - 60, -0.1332047592)).coerceIn(0.0, 255.0)
            g = (288.1221695283 * Math.pow(t - 60, -0.0755148492)).coerceIn(0.0, 255.0)
            b = 255.0
        }
        // Усиление обратно пропорционально яркости канала (компенсируем цвет источника).
        val rg = (255.0 / r.coerceAtLeast(1.0)).toFloat()
        val gg = (255.0 / g.coerceAtLeast(1.0)).toFloat()
        val bg = (255.0 / b.coerceAtLeast(1.0)).toFloat()
        // Нормируем по зелёному (обычно = 1.0)
        val norm = gg
        return android.hardware.camera2.params.RggbChannelVector(
            (rg / norm).coerceIn(1f, 4f),
            1.0f,
            1.0f,
            (bg / norm).coerceIn(1f, 4f)
        )
    }

    private fun closeCameraOnly() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        analysisReader?.close(); analysisReader = null
    }

    fun release() {
        orientationListener?.disable(); orientationListener = null
        closeCameraOnly()
        previewSurface?.release(); previewSurface = null
        thread?.quitSafely(); thread = null; handler = null
    }
}
