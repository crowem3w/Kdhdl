package org.example.test.orb

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Direct GLES2 port of the "Orb  " WebGL/OGL shader.
 *
 * The fragment shader body is left mathematically identical to the original
 * GLSL source so the visual result matches; the additions are a [saturation]
 * uniform used to desaturate the orb for a monochrome look, and [color1] /
 * [color2] / [color3] uniforms so callers can drive a custom brand palette
 * instead of the original violet/cyan/navy one.
 */
internal class OrbRenderer : GLSurfaceView.Renderer {

    // ---- Public, thread-safe-ish knobs (written from the UI thread, read from the GL thread) ----
    @Volatile var hue: Float = 0f
    @Volatile var hoverIntensity: Float = 0.3f
    @Volatile var rotateOnHover: Boolean = true
    @Volatile var forceHoverState: Boolean = false
    @Volatile var saturation: Float = 0f // 0 = fully monochrome, 1 = original color
    @Volatile var backgroundColor: FloatArray = floatArrayOf(0f, 0f, 0f)

    // Base palette the orb cycles/mixes between. Defaults match the original
    // violet/cyan/navy Orb.jsx palette; callers can override for a brand look
    // (e.g. a white-core, teal-glow orb) without touching the shader itself.
    @Volatile var color1: FloatArray = floatArrayOf(0.611765f, 0.262745f, 0.996078f)
    @Volatile var color2: FloatArray = floatArrayOf(0.298039f, 0.760784f, 0.913725f)
    @Volatile var color3: FloatArray = floatArrayOf(0.062745f, 0.078431f, 0.600000f)

    /** Updated by the view's touch listener; 1f while a touch is inside the orb, else 0f. */
    @Volatile var targetHover: Float = 0f

    private var program = 0
    private var positionHandle = 0
    private var uvHandle = 0
    private var uTime = 0
    private var uResolution = 0
    private var uHue = 0
    private var uHover = 0
    private var uRot = 0
    private var uHoverIntensity = 0
    private var uBackgroundColor = 0
    private var uSaturation = 0
    private var uColor1 = 0
    private var uColor2 = 0
    private var uColor3 = 0

    private var vertexBuffer: FloatBuffer
    private var uvBuffer: FloatBuffer

    private var viewportWidth = 1
    private var viewportHeight = 1

    private var startTimeNanos = 0L
    private var currentHover = 0f
    private var currentRot = 0f
    private var lastFrameNanos = 0L

    init {
        // A single oversized triangle that covers the whole clip-space viewport once
        // rasterized, avoiding a seam down the middle of a two-triangle quad.
        val positions = floatArrayOf(
            -1f, -1f,
            3f, -1f,
            -1f, 3f,
        )
        val uvs = floatArrayOf(
            0f, 0f,
            2f, 0f,
            0f, 2f,
        )
        vertexBuffer = ByteBuffer.allocateDirect(positions.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(positions); position(0) }
        uvBuffer = ByteBuffer.allocateDirect(uvs.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(uvs); position(0) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SRC)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "position")
        uvHandle = GLES20.glGetAttribLocation(program, "uv")
        uTime = GLES20.glGetUniformLocation(program, "iTime")
        uResolution = GLES20.glGetUniformLocation(program, "iResolution")
        uHue = GLES20.glGetUniformLocation(program, "hue")
        uHover = GLES20.glGetUniformLocation(program, "hover")
        uRot = GLES20.glGetUniformLocation(program, "rot")
        uHoverIntensity = GLES20.glGetUniformLocation(program, "hoverIntensity")
        uBackgroundColor = GLES20.glGetUniformLocation(program, "backgroundColor")
        uSaturation = GLES20.glGetUniformLocation(program, "saturation")
        uColor1 = GLES20.glGetUniformLocation(program, "baseColor1")
        uColor2 = GLES20.glGetUniformLocation(program, "baseColor2")
        uColor3 = GLES20.glGetUniformLocation(program, "baseColor3")

        startTimeNanos = SystemClock.elapsedRealtimeNanos()
        lastFrameNanos = startTimeNanos
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val elapsedSeconds = (nowNanos - startTimeNanos) / 1_000_000_000f
        val dt = ((nowNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNanos = nowNanos

        val effectiveHover = if (forceHoverState) 1f else targetHover
        currentHover += (effectiveHover - currentHover) * 0.1f

        if (rotateOnHover && effectiveHover > 0.5f) {
            currentRot += dt * ROTATION_SPEED
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)

        GLES20.glUniform1f(uTime, elapsedSeconds)
        GLES20.glUniform3f(
            uResolution,
            viewportWidth.toFloat(),
            viewportHeight.toFloat(),
            viewportWidth.toFloat() / viewportHeight.toFloat(),
        )
        GLES20.glUniform1f(uHue, hue)
        GLES20.glUniform1f(uHover, currentHover)
        GLES20.glUniform1f(uRot, currentRot)
        GLES20.glUniform1f(uHoverIntensity, hoverIntensity)
        GLES20.glUniform1f(uSaturation, saturation)
        val c1 = color1
        val c2 = color2
        val c3 = color3
        GLES20.glUniform3f(uColor1, c1.getOrElse(0) { 0f }, c1.getOrElse(1) { 0f }, c1.getOrElse(2) { 0f })
        GLES20.glUniform3f(uColor2, c2.getOrElse(0) { 0f }, c2.getOrElse(1) { 0f }, c2.getOrElse(2) { 0f })
        GLES20.glUniform3f(uColor3, c3.getOrElse(0) { 0f }, c3.getOrElse(1) { 0f }, c3.getOrElse(2) { 0f })
        val bg = backgroundColor
        GLES20.glUniform3f(uBackgroundColor, bg.getOrElse(0) { 0f }, bg.getOrElse(1) { 0f }, bg.getOrElse(2) { 0f })

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private companion object {
        const val ROTATION_SPEED = 0.3f

        val VERTEX_SRC = """
            precision highp float;
            attribute vec2 position;
            attribute vec2 uv;
            varying vec2 vUv;
            void main() {
              vUv = uv;
              gl_Position = vec4(position, 0.0, 1.0);
            }
        """.trimIndent()

        // Fragment shader: same math as the original Orb.jsx GLSL, plus a `saturation`
        // uniform (1.0 = original colors, 0.0 = fully monochrome) and a final composite
        // against backgroundColor so the surface can render fully opaque.
        val FRAGMENT_SRC = """
            precision highp float;

            uniform float iTime;
            uniform vec3 iResolution;
            uniform float hue;
            uniform float hover;
            uniform float rot;
            uniform float hoverIntensity;
            uniform vec3 backgroundColor;
            uniform float saturation;
            uniform vec3 baseColor1;
            uniform vec3 baseColor2;
            uniform vec3 baseColor3;
            varying vec2 vUv;

            vec3 rgb2yiq(vec3 c) {
              float y = dot(c, vec3(0.299, 0.587, 0.114));
              float i = dot(c, vec3(0.596, -0.274, -0.322));
              float q = dot(c, vec3(0.211, -0.523, 0.312));
              return vec3(y, i, q);
            }

            vec3 yiq2rgb(vec3 c) {
              float r = c.x + 0.956 * c.y + 0.621 * c.z;
              float g = c.x - 0.272 * c.y - 0.647 * c.z;
              float b = c.x - 1.106 * c.y + 1.703 * c.z;
              return vec3(r, g, b);
            }

            vec3 adjustHue(vec3 color, float hueDeg) {
              float hueRad = hueDeg * 3.14159265 / 180.0;
              vec3 yiq = rgb2yiq(color);
              float cosA = cos(hueRad);
              float sinA = sin(hueRad);
              float i = yiq.y * cosA - yiq.z * sinA;
              float q = yiq.y * sinA + yiq.z * cosA;
              yiq.y = i;
              yiq.z = q;
              return yiq2rgb(yiq);
            }

            vec3 hash33(vec3 p3) {
              p3 = fract(p3 * vec3(0.1031, 0.11369, 0.13787));
              p3 += dot(p3, p3.yxz + 19.19);
              return -1.0 + 2.0 * fract(vec3(
                p3.x + p3.y,
                p3.x + p3.z,
                p3.y + p3.z
              ) * p3.zyx);
            }

            float snoise3(vec3 p) {
              const float K1 = 0.333333333;
              const float K2 = 0.166666667;
              vec3 i = floor(p + (p.x + p.y + p.z) * K1);
              vec3 d0 = p - (i - (i.x + i.y + i.z) * K2);
              vec3 e = step(vec3(0.0), d0 - d0.yzx);
              vec3 i1 = e * (1.0 - e.zxy);
              vec3 i2 = 1.0 - e.zxy * (1.0 - e);
              vec3 d1 = d0 - (i1 - K2);
              vec3 d2 = d0 - (i2 - K1);
              vec3 d3 = d0 - 0.5;
              vec4 h = max(0.6 - vec4(
                dot(d0, d0),
                dot(d1, d1),
                dot(d2, d2),
                dot(d3, d3)
              ), 0.0);
              vec4 n = h * h * h * h * vec4(
                dot(d0, hash33(i)),
                dot(d1, hash33(i + i1)),
                dot(d2, hash33(i + i2)),
                dot(d3, hash33(i + 1.0))
              );
              return dot(vec4(31.316), n);
            }

            vec4 extractAlpha(vec3 colorIn) {
              float a = max(max(colorIn.r, colorIn.g), colorIn.b);
              return vec4(colorIn.rgb / (a + 1e-5), a);
            }

            const float innerRadius = 0.01;
            const float noiseScale = 0.6;

            float light1(float intensity, float attenuation, float dist) {
              return intensity / (1.0 + dist * attenuation);
            }
            float light2(float intensity, float attenuation, float dist) {
              return intensity / (1.0 + dist * dist * attenuation);
            }

            vec4 draw(vec2 uv) {
              vec3 color1 = adjustHue(baseColor1, hue);
              vec3 color2 = adjustHue(baseColor2, hue);
              vec3 color3 = adjustHue(baseColor3, hue);

              float ang = atan(uv.y, uv.x);
              float len = length(uv);
              float invLen = len > 0.0 ? 1.0 / len : 0.0;

              float bgLuminance = dot(backgroundColor, vec3(0.299, 0.587, 0.114));

              float n0 = snoise3(vec3(uv * noiseScale, iTime * 0.5)) * 0.5 + 0.5;
              float r0 = mix(mix(innerRadius, 1.0, 0.4), mix(innerRadius, 1.0, 0.6), n0);
              float d0 = distance(uv, (r0 * invLen) * uv);
              float v0 = light1(1.0, 10.0, d0);

              v0 *= smoothstep(r0 * 1.05, r0, len);
              float innerFade = smoothstep(r0 * 0.8, r0 * 0.95, len);
              v0 *= mix(innerFade, 1.0, bgLuminance * 0.7);
              float cl = cos(ang + iTime * 2.0) * 0.5 + 0.5;

              float a = iTime * -1.0;
              vec2 pos = vec2(cos(a), sin(a)) * r0;
              float d = distance(uv, pos);
              float v1 = light2(1.5, 5.0, d);
              v1 *= light1(1.0, 50.0, d0);

              float v2 = smoothstep(1.0, mix(innerRadius, 1.0, n0 * 0.5), len);
              float v3 = smoothstep(innerRadius, mix(innerRadius, 1.0, 0.5), len);

              vec3 colBase = mix(color1, color2, cl);
              float fadeAmount = mix(1.0, 0.1, bgLuminance);

              vec3 darkCol = mix(color3, colBase, v0);
              darkCol = (darkCol + v1) * v2 * v3;
              darkCol = clamp(darkCol, 0.0, 1.0);

              vec3 lightCol = (colBase + v1) * mix(1.0, v2 * v3, fadeAmount);
              lightCol = mix(backgroundColor, lightCol, v0);
              lightCol = clamp(lightCol, 0.0, 1.0);

              vec3 finalCol = mix(darkCol, lightCol, bgLuminance);

              float lum = dot(finalCol, vec3(0.299, 0.587, 0.114));
              finalCol = mix(vec3(lum), finalCol, saturation);

              return extractAlpha(finalCol);
            }

            vec4 mainImage(vec2 fragCoord) {
              vec2 center = iResolution.xy * 0.5;
              float size = min(iResolution.x, iResolution.y);
              vec2 uv = (fragCoord - center) / size * 2.0;

              float angle = rot;
              float s = sin(angle);
              float c = cos(angle);
              uv = vec2(c * uv.x - s * uv.y, s * uv.x + c * uv.y);

              uv.x += hover * hoverIntensity * 0.1 * sin(uv.y * 10.0 + iTime);
              uv.y += hover * hoverIntensity * 0.1 * sin(uv.x * 10.0 + iTime);

              return draw(uv);
            }

            void main() {
              vec2 fragCoord = vUv * iResolution.xy;
              vec4 col = mainImage(fragCoord);
              // Manual "over" composite against an opaque backgroundColor, since this
              // surface is rendered opaque rather than alpha-blended with a page behind it.
              vec3 composited = col.rgb * col.a + backgroundColor * (1.0 - col.a);
              gl_FragColor = vec4(composited, 1.0);
            }
        """.trimIndent()
    }
}
