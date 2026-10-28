package me.sorihiko.dango.shader

import me.sorihiko.dango.modern.Imm
import me.sorihiko.dango.utils.Color

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

import java.nio.FloatBuffer

/**
 * 自己的着色器（OpenGL 3.2 core 版本，GLSL 150）。顶点属性固定：0 = a_Pos，1 = a_UV（见 [me.sorihiko.dango.modern.Imm]）。
 * [bind] 时记下原来的着色器，[unbind] 时换回去（Minecraft 记着它自己绑的是哪个，不能直接改成 0）。
 */
abstract class GlShader(
    val vertPath: String,
    val fragPath: String,
) {
    private var programId: Int = 0
    private var uniforms = mutableMapOf<String, Int>()
    // Uniform values belong to this program and survive program switches. Keep the
    // last submitted values, avoiding repeated transforms/samplers/colors per quad.
    private val floatValues = mutableMapOf<Int, FloatArray>()
    private val intValues = mutableMapOf<Int, Int>()

    private fun values(id: Int, count: Int): FloatArray =
        floatValues.getOrPut(id) { FloatArray(count) { Float.NaN } }

    fun linkProgram() {
        if (programId != 0) return

        val vsh = compileShader(vertPath, GL20.GL_VERTEX_SHADER)
        val fsh = compileShader(fragPath, GL20.GL_FRAGMENT_SHADER)

        programId = GL20.glCreateProgram()
        GL20.glAttachShader(programId, vsh)
        GL20.glAttachShader(programId, fsh)
        GL20.glBindAttribLocation(programId, Imm.ATTR_POS, "a_Pos")
        GL20.glBindAttribLocation(programId, Imm.ATTR_UV, "a_UV")
        GL20.glLinkProgram(programId)
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetProgramInfoLog(programId, 1024)
            throw RuntimeException("Shader program linking failed: $log")
        }
        GL20.glDeleteShader(vsh)
        GL20.glDeleteShader(fsh)

        uniformsFields().forEach {
            getUniformId(it)
        }
    }

    open fun uniformsFields(): Array<String> {
        return emptyArray()
    }

    fun getProgramID(): Int = programId

    private var previous = 0
    private var restoreOnUnbind = true

    fun bind() {
        restoreOnUnbind = !Imm.inScope
        if (restoreOnUnbind) previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        GL20.glUseProgram(programId)
    }

    fun unbind() {
        if (restoreOnUnbind) GL20.glUseProgram(previous)
    }

    private fun getUniformId(name: String): Int {
        if (uniforms.containsKey(name)) return uniforms[name]!!
        val i = GL20.glGetUniformLocation(programId, name)
        uniforms[name] = i
        return i
    }

    private fun compileShader(srcPath: String, type: Int): Int {
        val source = this.javaClass
            .getResourceAsStream("/assets/dangomusic/shaders/$srcPath")
            ?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: error("Shader not found: $srcPath")

        val shaderId = GL20.glCreateShader(type)
        GL20.glShaderSource(shaderId, source)
        GL20.glCompileShader(shaderId)
        if (GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(shaderId, 1024)
            throw RuntimeException("Shader compilation failed: $log")
        }
        return shaderId
    }

    protected fun setUniform2f(field: String, a: Float, b: Float) {
        val id = getUniformId(field)
        if (id < 0) return
        val v = values(id, 2)
        if (v[0] == a && v[1] == b) return
        GL20.glUniform2f(id, a, b)
        v[0] = a; v[1] = b
    }

    protected fun setUniform4f(field: String, a: Float, b: Float, c: Float, d: Float) {
        val id = getUniformId(field)
        if (id < 0) return
        val v = values(id, 4)
        if (v[0] == a && v[1] == b && v[2] == c && v[3] == d) return
        GL20.glUniform4f(id, a, b, c, d)
        v[0] = a; v[1] = b; v[2] = c; v[3] = d
    }

    protected fun setUniformColor(field: String, color: Color) {
        setUniform4f(field, color.r, color.g, color.b, color.a)
    }

    protected fun setUniform1f(field: String, a: Float) {
        val id = getUniformId(field)
        if (id < 0) return
        val v = values(id, 1)
        if (v[0] == a) return
        GL20.glUniform1f(id, a)
        v[0] = a
    }

    protected fun setUniform1i(field: String, a: Int) {
        val id = getUniformId(field)
        if (id < 0) return
        if (intValues[id] == a) return
        GL20.glUniform1i(id, a)
        intValues[id] = a
    }

    protected fun setUniformMat4(field: String, mat4Buffer: FloatBuffer) {
        val id = getUniformId(field)
        if (id < 0) return
        val v = values(id, 16)
        val start = mat4Buffer.position()
        var same = true
        for (i in 0..15) if (v[i] != mat4Buffer.get(start + i)) { same = false; break }
        if (same) return
        GL20.glUniformMatrix4fv(id, false, mat4Buffer)
        for (i in 0..15) v[i] = mat4Buffer.get(start + i)
    }
}
