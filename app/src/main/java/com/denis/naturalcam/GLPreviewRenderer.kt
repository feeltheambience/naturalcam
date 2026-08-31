package com.denis.naturalcam

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Рендерит превью камеры через внешнюю OES-текстуру. Во фрагментном шейдере:
 *  1) базовый «стиль изображения» (контраст/насыщенность/каналы/тени);
 *  2) 3D-LUT (.cube) — киношная/плёночная цветокоррекция.
 *
 * OpenGL ES 3.0 (ради sampler3D). Camera2 пишет кадры в surfaceTexture,
 * onFrameAvailable дёргает requestRender, onDrawFrame рисует кадр.
 *
 * Ориентация повёрнута на sensorOrientation — знак/зеркало могут потребовать
 * правки на конкретном устройстве (ожидаемая точка тюнинга).
 */
class GLPreviewRenderer(
    private val requestRender: () -> Unit,
    private val onSurfaceReady: (SurfaceTexture, Int, Int) -> Unit,
    private val getStyleParams: () -> FloatArray,
    private val getSensorOrientation: () -> Int,
    private val getIsFront: () -> Boolean,
    private val getLut: () -> Lut3D?,
    private val getLutVersion: () -> Int,
    private val getLutMix: () -> Float,
    private val getPeaking: () -> Boolean,
    private val getZebra: () -> Boolean,
    private val getPreviewSize: () -> Pair<Int, Int>,
    private val getDisplayRotationDeg: () -> Int,
    private val getCalRot: () -> Int = { 0 },
    private val getCalMirror: () -> Boolean = { false },
    private val getDigitalZoom: () -> Float = { 1f },
    private val onTransform: (rot: Int, fracX: Float, fracY: Float, mirrored: Boolean, stRot: Int) -> Unit = { _, _, _, _, _ -> }
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var program = 0
    private var texId = 0
    private var lutTexId = 0
    private var uploadedLutVersion = Int.MIN_VALUE
    private lateinit var surfaceTexture: SurfaceTexture
    private var handedOff = false
    private var viewW = 1
    private var viewH = 1

    private val stMatrix = FloatArray(16)
    private val mvp = FloatArray(16)

    private val quadPos = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    private var aPos = 0
    private var aTex = 0
    private var uSTMatrix = 0
    private var uMVP = 0
    private var uContrast = 0
    private var uSat = 0
    private var uLift = 0
    private var uChan = 0
    private var uTexLoc = 0
    private var uLutLoc = 0
    private var uLutMix = 0
    private var uPeaking = 0
    private var uZebra = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aTex = GLES30.glGetAttribLocation(program, "aTex")
        uSTMatrix = GLES30.glGetUniformLocation(program, "uSTMatrix")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uContrast = GLES30.glGetUniformLocation(program, "uContrast")
        uSat = GLES30.glGetUniformLocation(program, "uSat")
        uLift = GLES30.glGetUniformLocation(program, "uLift")
        uChan = GLES30.glGetUniformLocation(program, "uChan")
        uTexLoc = GLES30.glGetUniformLocation(program, "uTex")
        uLutLoc = GLES30.glGetUniformLocation(program, "uLut")
        uLutMix = GLES30.glGetUniformLocation(program, "uLutMix")
        uPeaking = GLES30.glGetUniformLocation(program, "uPeaking")
        uZebra = GLES30.glGetUniformLocation(program, "uZebra")

        // Внешняя OES-текстура камеры (unit 0)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 3D-LUT текстура (unit 1) — по умолчанию единичная
        val lt = IntArray(1)
        GLES30.glGenTextures(1, lt, 0)
        lutTexId = lt[0]
        uploadLut(Lut3D.identity())
        uploadedLutVersion = Int.MIN_VALUE

        surfaceTexture = SurfaceTexture(texId)
        surfaceTexture.setOnFrameAvailableListener(this)
        handedOff = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
        if (!handedOff) {
            handedOff = true
            onSurfaceReady(surfaceTexture, width, height)
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
        } catch (_: Throwable) {
            return
        }

        // Перезалить LUT, если сменилась
        val ver = getLutVersion()
        if (ver != uploadedLutVersion) {
            uploadLut(getLut() ?: Lut3D.identity())
            uploadedLutVersion = ver
        }

        // АВТОКАЛИБРОВКА: некоторые прошивки (HyperOS) вшивают поворот прямо в
        // ST-матрицу SurfaceTexture. Извлекаем его (форма Flip·R(φ)) и вычитаем,
        // иначе поворот применяется дважды и кадр лежит боком.
        val stRot = run {
            val ang = Math.toDegrees(Math.atan2(-stMatrix[1].toDouble(), stMatrix[0].toDouble()))
            ((Math.round(ang / 90.0).toInt() * 90) % 360 + 360) % 360
        }

        // Поворот кадра под ориентацию: сенсор с поправкой на поворот экрана + калибровка.
        val sensor = getSensorOrientation()
        val disp = getDisplayRotationDeg()
        val front = getIsFront()
        val mirror = front xor getCalMirror()
        // Какой поворот должен ПОЛУЧИТЬСЯ на экране (определяет и своп сторон для кропа)
        val rotEffective = ((if (front) (sensor + disp) else (sensor - disp)) + getCalRot() + 720) % 360
        // Какой поворот НАМ осталось применить (ST уже довернёт на stRot)
        val rot = (rotEffective - stRot + 720) % 360

        // Пропорции изображения КАК ОТОБРАЖАЕТСЯ (по итоговому повороту rotEffective)
        val (bufW, bufH) = getPreviewSize()
        val dispImgAspect =
            if (rotEffective % 180 == 90) bufH.toFloat() / bufW.coerceAtLeast(1)
            else bufW.toFloat() / bufH.coerceAtLeast(1)
        val screenAspect = viewW.toFloat() / viewH

        // Cover-кроп в НОРМАЛИЗОВАННОМ текстурном пространстве (изотропно — пропорции не плывут).
        // Обрезаем ту сторону, что «лишняя», чтобы заполнить экран без искажений.
        var fracX = 1f; var fracY = 1f
        if (dispImgAspect > screenAspect) fracX = screenAspect / dispImgAspect
        else fracY = dispImgAspect / screenAspect
        // Цифровой зум сверх аппаратного — дополнительный равномерный кроп (пропорции не трогает)
        val dz = getDigitalZoom().coerceAtLeast(1f)
        fracX /= dz; fracY /= dz
        // Тап-фокусу сообщаем итоговый поворот: цепочка экран→кадр для него = флип + R(rotEffective)
        onTransform(rotEffective, fracX, fracY, mirror, stRot)

        // Матрица трансформации ТЕКСТУРНЫХ координат: кроп(в координатах экрана) -> зеркало -> поворот.
        Matrix.setIdentityM(mvp, 0)
        Matrix.translateM(mvp, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(mvp, 0, rot.toFloat(), 0f, 0f, 1f)
        if (mirror) Matrix.scaleM(mvp, 0, -1f, 1f, 1f)
        Matrix.scaleM(mvp, 0, fracX, fracY, 1f)
        Matrix.translateM(mvp, 0, -0.5f, -0.5f, 0f)

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val p = getStyleParams()
        GLES30.glUniformMatrix4fv(uSTMatrix, 1, false, stMatrix, 0)
        GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES30.glUniform1f(uContrast, p[0])
        GLES30.glUniform1f(uSat, p[1])
        GLES30.glUniform3f(uChan, p[2], p[3], p[4])
        GLES30.glUniform1f(uLift, p[5])
        GLES30.glUniform1f(uLutMix, if (getLut() == null) 0f else getLutMix())
        GLES30.glUniform1f(uPeaking, if (getPeaking()) 1f else 0f)
        GLES30.glUniform1f(uZebra, if (getZebra()) 1f else 0f)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glUniform1i(uTexLoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
        GLES30.glUniform1i(uLutLoc, 1)

        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quadPos)
        GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 0, quadTex)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aTex)
    }

    private fun uploadLut(lut: Lut3D) {
        val buf = ByteBuffer.allocateDirect(lut.data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(lut.data); position(0) }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB,
            lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buf
        )
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = loadShader(GLES30.GL_VERTEX_SHADER, vs)
        val f = loadShader(GLES30.GL_FRAGMENT_SHADER, fs)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, v)
        GLES30.glAttachShader(prog, f)
        GLES30.glLinkProgram(prog)
        return prog
    }

    private fun loadShader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        return s
    }

    companion object {
        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(data); position(0) }

        private const val VERTEX_SHADER = """#version 300 es
            in vec4 aPos;
            in vec4 aTex;
            uniform mat4 uSTMatrix;
            uniform mat4 uMVP;   // матрица ТЕКСТУРНЫХ координат (кроп+поворот)
            out vec2 vTex;
            void main() {
                gl_Position = aPos;                                  // квад на весь экран
                vec4 t = uMVP * vec4(aTex.xy, 0.0, 1.0);             // кроп + зеркало + поворот
                vTex = (uSTMatrix * vec4(t.xy, 0.0, 1.0)).xy;        // затем ST-трансформ камеры (флип)
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTex;
            out vec4 fragColor;
            uniform samplerExternalOES uTex;
            uniform sampler3D uLut;
            uniform float uContrast;
            uniform float uSat;
            uniform float uLift;
            uniform float uLutMix;
            uniform float uPeaking;
            uniform float uZebra;
            uniform vec3 uChan;
            void main() {
                vec3 c = texture(uTex, vTex).rgb;
                c *= uChan;
                c = (c - 0.5) * uContrast + 0.5;
                float l = dot(c, vec3(0.299, 0.587, 0.114));
                c = mix(vec3(l), c, uSat);
                c = c + uLift;
                c = clamp(c, 0.0, 1.0);
                vec3 graded = texture(uLut, c).rgb;
                c = mix(c, graded, uLutMix);
                c = clamp(c, 0.0, 1.0);

                float luma = dot(c, vec3(0.299, 0.587, 0.114));

                // Зебра: диагональные полосы на пересвете
                if (uZebra > 0.5 && luma > 0.92) {
                    if (mod(gl_FragCoord.x + gl_FragCoord.y, 14.0) < 7.0) {
                        c = vec3(0.0);
                    }
                }
                // Фокус-пикинг: подсветка резких краёв (по производным яркости)
                if (uPeaking > 0.5) {
                    float edge = length(vec2(dFdx(luma), dFdy(luma)));
                    if (edge > 0.06) {
                        c = mix(c, vec3(1.0, 0.15, 0.15), 0.85);
                    }
                }
                fragColor = vec4(c, 1.0);
            }
        """
    }
}
