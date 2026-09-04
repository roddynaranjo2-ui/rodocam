/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.jetpackcamera.core.camera.effects

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import androidx.annotation.WorkerThread
import androidx.camera.core.DynamicRange
import androidx.graphics.opengl.egl.EGLConfigAttributes
import androidx.graphics.opengl.egl.EGLManager
import androidx.graphics.opengl.egl.EGLSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the external camera texture into an output surface. When [assist] is set the fragment
 * shader additionally draws the viewfinder assist features in the same pass: a 3x3 Sobel filter
 * on luma that tints strong edges (focus peaking) and/or animated diagonal stripes over pixels
 * whose luma reaches the zebra threshold.
 */
internal class ShaderCopy(
    private val dynamicRange: DynamicRange,
    private val assist: ViewfinderAssistEffectConfig? = null
) : RenderCallbacks {

    // Called on worker thread only
    private var externalTextureId: Int = -1
    private var programHandle = -1
    private var texMatrixLoc = -1
    private var samplerLoc = -1
    private var positionLoc = -1
    private var texCoordLoc = -1
    private var texelSizeLoc = -1
    private var peakingColorLoc = -1
    private var peakingThresholdLoc = -1
    private var peakingFeatherLoc = -1
    private var zebraThresholdLoc = -1
    private var zebraPeriodLoc = -1
    private var zebraDutyLoc = -1
    private var zebraAlphaLoc = -1
    private var zebraPhaseLoc = -1
    private var inputWidth = 0
    private var inputHeight = 0
    private val focusPeaking: FocusPeakingConfig?
        get() = assist?.peaking
    private val zebras: ZebraConfig?
        get() = assist?.zebras
    private val usePeaking: Boolean
        get() = focusPeaking != null
    private val useZebras: Boolean
        get() = zebras != null
    private val useAssist: Boolean
        get() = usePeaking || useZebras
    private val glExtensions: Set<String> by lazy {
        checkGlThread()
        buildSet {
            GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(" ")?.also {
                addAll(it)
            }
        }
    }
    private val use10bitPipeline: Boolean
        get() = dynamicRange.bitDepth == DynamicRange.BIT_DEPTH_10_BIT

    override val glThreadName: String
        get() = TAG

    override val provideEGLSpec: () -> EGLSpec
        get() = { if (use10bitPipeline) EGLSpec.V14ES3 else EGLSpec.V14 }

    override val initConfig: EGLManager.() -> EGLConfig
        get() = {
            checkNotNull(
                loadConfig(
                    EGLConfigAttributes {
                        if (use10bitPipeline) {
                            TEN_BIT_REQUIRED_EGL_EXTENSIONS.forEach {
                                check(isExtensionSupported(it)) {
                                    "Required extension for 10-bit HDR is not " +
                                        "supported: $it"
                                }
                            }
                            include(EGLConfigAttributes.RGBA_1010102)
                            EGL14.EGL_RENDERABLE_TYPE to
                                EGLExt.EGL_OPENGL_ES3_BIT_KHR
                            EGL14.EGL_SURFACE_TYPE to
                                (EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT)
                        } else {
                            include(EGLConfigAttributes.RGBA_8888)
                        }
                    }
                )
            ) {
                "Unable to select EGLConfig"
            }
        }

    override val initRenderer: () -> Unit
        get() = {
            if (use10bitPipeline && glExtensions.contains("GL_KHR_debug")) {
                GLDebug.enableES3DebugErrorLogging()
            }

            createProgram(
                if (use10bitPipeline) {
                    TEN_BIT_VERTEX_SHADER
                } else {
                    DEFAULT_VERTEX_SHADER
                },
                when {
                    use10bitPipeline && useAssist -> TEN_BIT_ASSIST_FRAGMENT_SHADER
                    use10bitPipeline -> TEN_BIT_FRAGMENT_SHADER
                    useAssist -> DEFAULT_ASSIST_FRAGMENT_SHADER
                    else -> DEFAULT_FRAGMENT_SHADER
                }
            )
            loadLocations()
            createTexture()
            useAndConfigureProgram()
        }

    override val createSurfaceTexture
        get() = { width: Int, height: Int ->
            inputWidth = width
            inputHeight = height
            SurfaceTexture(externalTextureId).apply {
                setDefaultBufferSize(width, height)
            }
        }

    override val createOutputSurface
        get() = { eglSpec: EGLSpec,
                config: EGLConfig,
                surface: Surface,
                _: Int,
                _: Int ->
            eglSpec.eglCreateWindowSurface(
                config,
                surface,
                EGLConfigAttributes {
                    if (use10bitPipeline) {
                        EGL_GL_COLORSPACE_KHR to EGL_GL_COLORSPACE_BT2020_HLG_EXT
                    }
                }
            )
        }

    override val drawFrame
        get() = { outputWidth: Int,
                outputHeight: Int,
                surfaceTransform: FloatArray ->
            checkGlThread()
            GLES20.glViewport(
                0,
                0,
                outputWidth,
                outputHeight
            )
            GLES20.glScissor(
                0,
                0,
                outputWidth,
                outputHeight
            )

            GLES20.glUniformMatrix4fv(
                texMatrixLoc,
                /*count=*/
                1,
                /*transpose=*/
                false,
                surfaceTransform,
                /*offset=*/
                0
            )
            checkGlErrorOrThrow("glUniformMatrix4fv")

            if (useAssist) {
                applyAssistUniforms()
            }

            // Draw the rect.
            GLES20.glDrawArrays(
                GLES20.GL_TRIANGLE_STRIP,
                /*firstVertex=*/
                0,
                /*vertexCount=*/
                4
            )
            checkGlErrorOrThrow("glDrawArrays")
        }

    @WorkerThread
    private fun applyAssistUniforms() {
        // Texel size in normalised texture coordinates; fall back to a 720p estimate before the
        // input size is known so the very first frame still renders sensibly.
        val w = if (inputWidth > 0) inputWidth else FALLBACK_INPUT_WIDTH
        val h = if (inputHeight > 0) inputHeight else FALLBACK_INPUT_HEIGHT
        GLES20.glUniform2f(texelSizeLoc, 1f / w, 1f / h)

        // Focus peaking: a threshold above 1 disables the pass (Sobel magnitude is <= 1).
        val peaking = focusPeaking
        if (peaking != null) {
            GLES20.glUniform3f(
                peakingColorLoc,
                peaking.colorRgb[0],
                peaking.colorRgb[1],
                peaking.colorRgb[2]
            )
            GLES20.glUniform1f(peakingThresholdLoc, peaking.threshold)
            GLES20.glUniform1f(peakingFeatherLoc, peaking.feather)
        } else {
            GLES20.glUniform3f(peakingColorLoc, 0f, 0f, 0f)
            GLES20.glUniform1f(peakingThresholdLoc, DISABLED_THRESHOLD)
            GLES20.glUniform1f(peakingFeatherLoc, 0f)
        }

        // Zebras: stripes are computed in output pixel space so their width does not depend on
        // the stream resolution; the phase advances with time so the stripes crawl like on a
        // broadcast monitor. A threshold above 1 disables the pass.
        val zebra = zebras
        if (zebra != null) {
            val periodPx = zebra.stripePeriodPx
            GLES20.glUniform1f(zebraThresholdLoc, zebra.threshold)
            GLES20.glUniform1f(zebraPeriodLoc, periodPx)
            GLES20.glUniform1f(zebraDutyLoc, zebra.stripeDutyCycle)
            GLES20.glUniform1f(zebraAlphaLoc, zebra.stripeAlpha)
            GLES20.glUniform1f(zebraPhaseLoc, zebraPhasePx(System.nanoTime(), periodPx))
        } else {
            GLES20.glUniform1f(zebraThresholdLoc, DISABLED_THRESHOLD)
            GLES20.glUniform1f(zebraPeriodLoc, 1f)
            GLES20.glUniform1f(zebraDutyLoc, 0f)
            GLES20.glUniform1f(zebraAlphaLoc, 0f)
            GLES20.glUniform1f(zebraPhaseLoc, 0f)
        }
        checkGlErrorOrThrow("assist uniforms")
    }

    @WorkerThread
    fun createTexture() {
        checkGlThread()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGlErrorOrThrow("glGenTextures")
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        checkGlErrorOrThrow("glBindTexture $texId")
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        checkGlErrorOrThrow("glTexParameter")
        externalTextureId = texId
    }

    @WorkerThread
    fun useAndConfigureProgram() {
        checkGlThread()
        // Select the program.
        GLES20.glUseProgram(programHandle)
        checkGlErrorOrThrow("glUseProgram")

        // Set the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniform1i(samplerLoc, 0)

        if (use10bitPipeline) {
            val vaos = IntArray(1)
            GLES30.glGenVertexArrays(1, vaos, 0)
            GLES30.glBindVertexArray(vaos[0])
            checkGlErrorOrThrow("glBindVertexArray")
        }

        val vbos = IntArray(2)
        GLES20.glGenBuffers(2, vbos, 0)
        checkGlErrorOrThrow("glGenBuffers")

        // Connect vertexBuffer to "aPosition".
        val coordsPerVertex = 2
        val vertexStride = 0
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbos[0])
        checkGlErrorOrThrow("glBindBuffer")
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            VERTEX_BUF.capacity() * SIZEOF_FLOAT,
            VERTEX_BUF,
            GLES20.GL_STATIC_DRAW
        )
        checkGlErrorOrThrow("glBufferData")

        // Enable the "aPosition" vertex attribute.
        GLES20.glEnableVertexAttribArray(positionLoc)
        checkGlErrorOrThrow("glEnableVertexAttribArray")

        GLES20.glVertexAttribPointer(
            positionLoc,
            coordsPerVertex,
            GLES20.GL_FLOAT,
            /*normalized=*/
            false,
            vertexStride,
            0
        )
        checkGlErrorOrThrow("glVertexAttribPointer")

        // Connect texBuffer to "aTextureCoord".
        val coordsPerTex = 2
        val texStride = 0
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbos[1])
        checkGlErrorOrThrow("glBindBuffer")

        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            TEX_BUF.capacity() * SIZEOF_FLOAT,
            TEX_BUF,
            GLES20.GL_STATIC_DRAW
        )
        checkGlErrorOrThrow("glBufferData")

        // Enable the "aTextureCoord" vertex attribute.
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        checkGlErrorOrThrow("glEnableVertexAttribArray")

        GLES20.glVertexAttribPointer(
            texCoordLoc,
            coordsPerTex,
            GLES20.GL_FLOAT,
            /*normalized=*/
            false,
            texStride,
            0
        )
        checkGlErrorOrThrow("glVertexAttribPointer")
    }

    @WorkerThread
    private fun createProgram(vertShader: String, fragShader: String) {
        checkGlThread()
        var vertexShader = -1
        var fragmentShader = -1
        var program = -1
        try {
            fragmentShader = loadShader(
                GLES20.GL_FRAGMENT_SHADER,
                fragShader
            )
            vertexShader = loadShader(
                GLES20.GL_VERTEX_SHADER,
                vertShader
            )
            program = GLES20.glCreateProgram()
            checkGlErrorOrThrow("glCreateProgram")
            GLES20.glAttachShader(program, vertexShader)
            checkGlErrorOrThrow("glAttachShader")
            GLES20.glAttachShader(program, fragmentShader)
            checkGlErrorOrThrow("glAttachShader")
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(
                program,
                GLES20.GL_LINK_STATUS,
                linkStatus,
                /*offset=*/
                0
            )
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "Could not link program: " + GLES20.glGetProgramInfoLog(
                    program
                )
            }
            programHandle = program
        } catch (e: Exception) {
            if (vertexShader != -1) {
                GLES20.glDeleteShader(vertexShader)
            }
            if (fragmentShader != -1) {
                GLES20.glDeleteShader(fragmentShader)
            }
            if (program != -1) {
                GLES20.glDeleteProgram(program)
            }
            throw e
        }
    }

    @WorkerThread
    private fun loadLocations() {
        checkGlThread()
        positionLoc = GLES20.glGetAttribLocation(programHandle, "aPosition")
        checkLocationOrThrow(positionLoc, "aPosition")
        texCoordLoc = GLES20.glGetAttribLocation(programHandle, "aTextureCoord")
        checkLocationOrThrow(texCoordLoc, "aTextureCoord")
        texMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uTexMatrix")
        checkLocationOrThrow(texMatrixLoc, "uTexMatrix")
        samplerLoc = GLES20.glGetUniformLocation(programHandle, VAR_TEXTURE)
        checkLocationOrThrow(samplerLoc, VAR_TEXTURE)
        if (useAssist) {
            texelSizeLoc = uniformLocation(VAR_TEXEL_SIZE)
            peakingColorLoc = uniformLocation(VAR_PEAKING_COLOR)
            peakingThresholdLoc = uniformLocation(VAR_PEAKING_THRESHOLD)
            peakingFeatherLoc = uniformLocation(VAR_PEAKING_FEATHER)
            zebraThresholdLoc = uniformLocation(VAR_ZEBRA_THRESHOLD)
            zebraPeriodLoc = uniformLocation(VAR_ZEBRA_PERIOD)
            zebraDutyLoc = uniformLocation(VAR_ZEBRA_DUTY)
            zebraAlphaLoc = uniformLocation(VAR_ZEBRA_ALPHA)
            zebraPhaseLoc = uniformLocation(VAR_ZEBRA_PHASE)
        }
    }

    @WorkerThread
    private fun uniformLocation(name: String): Int {
        val location = GLES20.glGetUniformLocation(programHandle, name)
        checkLocationOrThrow(location, name)
        return location
    }

    @WorkerThread
    private fun loadShader(shaderType: Int, source: String): Int {
        checkGlThread()
        val shader = GLES20.glCreateShader(shaderType)
        checkGlErrorOrThrow("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(
            shader,
            GLES20.GL_COMPILE_STATUS,
            compiled,
            /*offset=*/
            0
        )
        check(compiled[0] == GLES20.GL_TRUE) {
            Log.w(TAG, "Could not compile shader: $source")
            try {
                return@check "Could not compile shader type " +
                    "$shaderType: ${GLES20.glGetShaderInfoLog(shader)}"
            } finally {
                GLES20.glDeleteShader(shader)
            }
        }
        return shader
    }

    @WorkerThread
    private fun checkGlErrorOrThrow(op: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { op + ": GL error 0x" + Integer.toHexString(error) }
    }

    private fun checkLocationOrThrow(location: Int, label: String) {
        check(location >= 0) { "Unable to locate '$label' in program" }
    }

    companion object {
        private const val SIZEOF_FLOAT = 4

        private val VERTEX_BUF = floatArrayOf(
            // 0 bottom left
            -1.0f,
            -1.0f,
            // 1 bottom right
            1.0f,
            -1.0f,
            // 2 top left
            -1.0f,
            1.0f,
            // 3 top right
            1.0f,
            1.0f
        ).toBuffer()

        private val TEX_BUF = floatArrayOf(
            // 0 bottom left
            0.0f,
            0.0f,
            // 1 bottom right
            1.0f,
            0.0f,
            // 2 top left
            0.0f,
            1.0f,
            // 3 top right
            1.0f,
            1.0f
        ).toBuffer()

        private const val TAG = "ShaderCopy"
        private const val GL_THREAD_NAME = TAG

        private const val VAR_TEXTURE_COORD = "vTextureCoord"
        private val DEFAULT_VERTEX_SHADER =
            """
        uniform mat4 uTexMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 $VAR_TEXTURE_COORD;
        void main() {
            gl_Position = aPosition;
            $VAR_TEXTURE_COORD = (uTexMatrix * aTextureCoord).xy;
        }
            """.trimIndent()

        private val TEN_BIT_VERTEX_SHADER =
            """
        #version 300 es
        in vec4 aPosition;
        in vec4 aTextureCoord;
        uniform mat4 uTexMatrix;
        out vec2 $VAR_TEXTURE_COORD;
        void main() {
          gl_Position = aPosition;
          $VAR_TEXTURE_COORD = (uTexMatrix * aTextureCoord).xy;
        }
            """.trimIndent()

        private const val VAR_TEXTURE = "sTexture"
        private val DEFAULT_FRAGMENT_SHADER =
            """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 $VAR_TEXTURE_COORD;
        uniform samplerExternalOES $VAR_TEXTURE;
        void main() {
            gl_FragColor = texture2D($VAR_TEXTURE, $VAR_TEXTURE_COORD);
        }
            """.trimIndent()

        private val TEN_BIT_FRAGMENT_SHADER =
            """
        #version 300 es
        #extension GL_EXT_YUV_target : require
        precision mediump float;
        uniform __samplerExternal2DY2YEXT $VAR_TEXTURE;
        in vec2 $VAR_TEXTURE_COORD;
        out vec3 outColor;
        
        vec3 yuvToRgb(vec3 yuv) {
          const vec3 yuvOffset = vec3(0.0625, 0.5, 0.5);
          const mat3 yuvToRgbColorTransform = mat3(
            1.1689f, 1.1689f, 1.1689f,
            0.0000f, -0.1881f, 2.1502f,
            1.6853f, -0.6530f, 0.0000f
          );
          return clamp(yuvToRgbColorTransform * (yuv - yuvOffset), 0.0, 1.0);
        }
        
        void main() {
          outColor = yuvToRgb(texture($VAR_TEXTURE, $VAR_TEXTURE_COORD).xyz);
        }
            """.trimIndent()

        private const val VAR_TEXEL_SIZE = "uTexelSize"
        private const val VAR_PEAKING_COLOR = "uPeakingColor"
        private const val VAR_PEAKING_THRESHOLD = "uPeakingThreshold"
        private const val VAR_PEAKING_FEATHER = "uPeakingFeather"
        private const val VAR_ZEBRA_THRESHOLD = "uZebraThreshold"
        private const val VAR_ZEBRA_PERIOD = "uZebraPeriodPx"
        private const val VAR_ZEBRA_DUTY = "uZebraDuty"
        private const val VAR_ZEBRA_ALPHA = "uZebraAlpha"
        private const val VAR_ZEBRA_PHASE = "uZebraPhasePx"
        private const val FALLBACK_INPUT_WIDTH = 1280
        private const val FALLBACK_INPUT_HEIGHT = 720
        private const val LUMA_WEIGHTS = "vec3(0.299, 0.587, 0.114)"

        /** Threshold that no luma / gradient can reach: disables a pass in the shared shader. */
        internal const val DISABLED_THRESHOLD = 2f

        /** Stripe crawl speed in output pixels per second. */
        internal const val ZEBRA_CRAWL_PX_PER_SECOND = 24f

        /**
         * Phase (in output pixels, `0 until periodPx`) of the zebra stripes at [nowNanos] so that
         * they crawl at [ZEBRA_CRAWL_PX_PER_SECOND].
         */
        internal fun zebraPhasePx(nowNanos: Long, periodPx: Float): Float {
            if (periodPx <= 0f) return 0f
            val seconds = (nowNanos / 1_000_000L % 1_000_000L) / 1000.0
            val phase = (seconds * ZEBRA_CRAWL_PX_PER_SECOND) % periodPx
            return phase.toFloat()
        }

        /**
         * GLSL body shared by both assist shaders. `sampleLuma(offset)` must be defined by the
         * including shader and return the luma (0..1) at `vTextureCoord + offset`.
         *
         * `assistColor(rgb, luma)` applies, in order, the zebra stripes (where `luma` reaches
         * the zebra threshold) and the focus peaking tint (where the Sobel magnitude exceeds the
         * peaking threshold). Either pass is disabled by a threshold above 1.
         */
        private val ASSIST_GLSL_CORE =
            """
        uniform vec2 $VAR_TEXEL_SIZE;
        uniform vec3 $VAR_PEAKING_COLOR;
        uniform float $VAR_PEAKING_THRESHOLD;
        uniform float $VAR_PEAKING_FEATHER;
        uniform float $VAR_ZEBRA_THRESHOLD;
        uniform float $VAR_ZEBRA_PERIOD;
        uniform float $VAR_ZEBRA_DUTY;
        uniform float $VAR_ZEBRA_ALPHA;
        uniform float $VAR_ZEBRA_PHASE;

        float peakingMask() {
          if ($VAR_PEAKING_THRESHOLD > 1.0) {
            return 0.0;
          }
          vec2 t = $VAR_TEXEL_SIZE;
          float tl = sampleLuma(vec2(-t.x, -t.y));
          float tc = sampleLuma(vec2( 0.0, -t.y));
          float tr = sampleLuma(vec2( t.x, -t.y));
          float ml = sampleLuma(vec2(-t.x,  0.0));
          float mr = sampleLuma(vec2( t.x,  0.0));
          float bl = sampleLuma(vec2(-t.x,  t.y));
          float bc = sampleLuma(vec2( 0.0,  t.y));
          float br = sampleLuma(vec2( t.x,  t.y));
          float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
          float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
          float magnitude = length(vec2(gx, gy)) * 0.25;
          return smoothstep(
            $VAR_PEAKING_THRESHOLD,
            $VAR_PEAKING_THRESHOLD + max($VAR_PEAKING_FEATHER, 0.001),
            magnitude
          );
        }

        float zebraMask(float luma) {
          if ($VAR_ZEBRA_THRESHOLD > 1.0 || luma < $VAR_ZEBRA_THRESHOLD) {
            return 0.0;
          }
          // 45-degree stripes in output pixel space, crawling with the phase.
          float diagonal = gl_FragCoord.x + gl_FragCoord.y + $VAR_ZEBRA_PHASE;
          float cycle = fract(diagonal / max($VAR_ZEBRA_PERIOD, 1.0));
          return step(cycle, $VAR_ZEBRA_DUTY) * $VAR_ZEBRA_ALPHA;
        }

        vec3 assistColor(vec3 rgb, float luma) {
          // Zebras alternate between the (near white) source and a dark stripe.
          vec3 striped = mix(rgb, vec3(0.08), zebraMask(luma));
          return mix(striped, $VAR_PEAKING_COLOR, peakingMask());
        }
            """.trimIndent()

        /** Viewfinder assist on the 8-bit path: samples RGB, derives luma with BT.601 weights. */
        private val DEFAULT_ASSIST_FRAGMENT_SHADER =
            """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 $VAR_TEXTURE_COORD;
        uniform samplerExternalOES $VAR_TEXTURE;
        float sampleLuma(vec2 o) {
            return dot(texture2D($VAR_TEXTURE, $VAR_TEXTURE_COORD + o).rgb, $LUMA_WEIGHTS);
        }
        $ASSIST_GLSL_CORE
        void main() {
            vec4 color = texture2D($VAR_TEXTURE, $VAR_TEXTURE_COORD);
            float luma = dot(color.rgb, $LUMA_WEIGHTS);
            gl_FragColor = vec4(assistColor(color.rgb, luma), color.a);
        }
            """.trimIndent()

        /** Viewfinder assist on the 10-bit YUV path: luma is the Y channel directly. */
        private val TEN_BIT_ASSIST_FRAGMENT_SHADER =
            """
        #version 300 es
        #extension GL_EXT_YUV_target : require
        precision mediump float;
        uniform __samplerExternal2DY2YEXT $VAR_TEXTURE;
        in vec2 $VAR_TEXTURE_COORD;
        out vec3 outColor;
        float sampleLuma(vec2 o) {
          return texture($VAR_TEXTURE, $VAR_TEXTURE_COORD + o).x;
        }
        $ASSIST_GLSL_CORE

        vec3 yuvToRgb(vec3 yuv) {
          const vec3 yuvOffset = vec3(0.0625, 0.5, 0.5);
          const mat3 yuvToRgbColorTransform = mat3(
            1.1689f, 1.1689f, 1.1689f,
            0.0000f, -0.1881f, 2.1502f,
            1.6853f, -0.6530f, 0.0000f
          );
          return clamp(yuvToRgbColorTransform * (yuv - yuvOffset), 0.0, 1.0);
        }

        void main() {
          vec3 yuv = texture($VAR_TEXTURE, $VAR_TEXTURE_COORD).xyz;
          // Y is limited range (16..235 of 255): normalise so the zebra threshold matches 8-bit.
          float luma = clamp((yuv.x - 0.0625) / 0.8588, 0.0, 1.0);
          outColor = assistColor(yuvToRgb(yuv), luma);
        }
            """.trimIndent()

        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540

        private val TEN_BIT_REQUIRED_EGL_EXTENSIONS = listOf(
            "EGL_EXT_gl_colorspace_bt2020_hlg"
        )

        private fun FloatArray.toBuffer(): FloatBuffer {
            val bb = ByteBuffer.allocateDirect(size * SIZEOF_FLOAT)
            bb.order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(this)
            fb.position(0)
            return fb
        }

        private fun checkGlThread() {
            check(GL_THREAD_NAME == Thread.currentThread().name)
        }
    }
}
