package dev.luna5ama.gltest.terrain

import dev.fastmc.common.MathUtil
import dev.fastmc.common.TickTimer
import dev.fastmc.common.toRadians
import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.*
import dev.luna5ama.glwrapper.enums.*
import dev.luna5ama.glwrapper.objects.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.asMutable
import dev.luna5ama.kmogus.copyToMutableArr
import org.joml.Matrix4f
import org.lwjgl.glfw.GLFW

class AccumEnvCubemap(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val skyboxShader = register(
        TerrainDrawShader(
            ShaderSource.Vert("terrain/sky/Skybox.vert.glsl"),
            ShaderSource.Geom("terrain/sky/Skybox.geom.glsl"),
            ShaderSource.Frag("terrain/sky/Skybox.frag.glsl")
        )
    )

    private val drawShader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/sky/SkyboxDraw.vert.glsl"),
            ShaderSource.Frag("terrain/sky/SkyboxDraw.frag.glsl")
        )
    ).apply {
        MemoryStack {
            val projectionMatrix = Matrix4f()
            projectionMatrix.identity()
            projectionMatrix.perspective(90.0f.toRadians(), 1.0f, 0.01f, 1.0f)

            val viewMatrix = Matrix4f()
            val tempCombined = Matrix4f()
            val buffer = malloc(4 * 4 * 4 * 6).asMutable()

            fun pack() {
                tempCombined.set(projectionMatrix).mul(viewMatrix)
                tempCombined.copyToMutableArr(buffer)
            }

            viewMatrix.setLookAt(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f)
            pack()
            viewMatrix.setLookAt(0.0f, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f)
            pack()

            viewMatrix.setLookAt(0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f)
            pack()
            viewMatrix.setLookAt(0.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f, -1.0f)
            pack()

            viewMatrix.setLookAt(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, -1.0f, 0.0f)
            pack()
            viewMatrix.setLookAt(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f, -1.0f, 0.0f)
            pack()

            glProgramUniformMatrix4fv(
                skyboxShader.id,
                skyboxShader.locateUniform("uBoxMatries"),
                6,
                false,
                buffer.basePtr
            )
        }
    }

    val texture = register(TextureObject.TextureCubemap())
    private val cubeMapFbo = register(FramebufferObject())

    init {
        texture.allocate(1, ImageFormat.R16_G16_B16_F, 4096, 4096)
        texture.setFilter(GL_LINEAR, GL_LINEAR)
        cubeMapFbo.attachLayerAll(texture, GL_COLOR_ATTACHMENT0)
        cubeMapFbo.check()
    }

    private val vao = register(VertexArrayObject()).apply {
        val vbo = BufferObject.Immutable()
        MemoryStack {
            val buffer = malloc(4 * 6 * 6)
            buffer.ptr.setByteInc(1).setByteInc(-1).setByteInc(-1).setByteInc(0)
                .setByteInc(1).setByteInc(-1).setByteInc(1).setByteInc(0)
                .setByteInc(1).setByteInc(1).setByteInc(-1).setByteInc(0)
                .setByteInc(1).setByteInc(1).setByteInc(1).setByteInc(0)
                .setByteInc(1).setByteInc(-1).setByteInc(1).setByteInc(0)
                .setByteInc(1).setByteInc(1).setByteInc(-1).setByteInc(0)

                .setByteInc(-1).setByteInc(-1).setByteInc(-1).setByteInc(1)
                .setByteInc(-1).setByteInc(1).setByteInc(-1).setByteInc(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(1).setByteInc(1)
                .setByteInc(-1).setByteInc(1).setByteInc(1).setByteInc(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(1).setByteInc(1)
                .setByteInc(-1).setByteInc(1).setByteInc(-1).setByteInc(1)

                .setByteInc(-1).setByteInc(1).setByteInc(-1).setByteInc(2)
                .setByteInc(1).setByteInc(1).setByteInc(-1).setByteInc(2)
                .setByteInc(-1).setByteInc(1).setByteInc(1).setByteInc(2)
                .setByteInc(1).setByteInc(1).setByteInc(1).setByteInc(2)
                .setByteInc(-1).setByteInc(1).setByteInc(1).setByteInc(2)
                .setByteInc(1).setByteInc(1).setByteInc(-1).setByteInc(2)

                .setByteInc(-1).setByteInc(-1).setByteInc(-1).setByteInc(3)
                .setByteInc(1).setByteInc(-1).setByteInc(-1).setByteInc(3)
                .setByteInc(-1).setByteInc(-1).setByteInc(1).setByteInc(3)
                .setByteInc(1).setByteInc(-1).setByteInc(1).setByteInc(3)
                .setByteInc(-1).setByteInc(-1).setByteInc(1).setByteInc(3)
                .setByteInc(1).setByteInc(-1).setByteInc(-1).setByteInc(3)

                .setByteInc(-1).setByteInc(-1).setByteInc(1).setByteInc(4)
                .setByteInc(-1).setByteInc(1).setByteInc(1).setByteInc(4)
                .setByteInc(1).setByteInc(-1).setByteInc(1).setByteInc(4)
                .setByteInc(1).setByteInc(1).setByteInc(1).setByteInc(4)
                .setByteInc(1).setByteInc(-1).setByteInc(1).setByteInc(4)
                .setByteInc(-1).setByteInc(1).setByteInc(1).setByteInc(4)

                .setByteInc(-1).setByteInc(-1).setByteInc(-1).setByteInc(5)
                .setByteInc(-1).setByteInc(1).setByteInc(-1).setByteInc(5)
                .setByteInc(1).setByteInc(-1).setByteInc(-1).setByteInc(5)
                .setByteInc(1).setByteInc(1).setByteInc(-1).setByteInc(5)
                .setByteInc(1).setByteInc(-1).setByteInc(-1).setByteInc(5)
                .setByteInc(-1).setByteInc(1).setByteInc(-1).setByteInc(5)

            vbo.allocate(buffer.len, buffer.ptr, 0)
        }
        attachVbo(vbo, buildAttribute(4) {
            float(0, 4, GLDataType.GL_BYTE, false)
        })
    }

    private val updateTimer = TickTimer()

    fun render(fboGroup: FboGroup) {
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        if (updateTimer.tickAndReset(500)) {
            glDisable(GL_CULL_FACE)

            cubeMapFbo.bind()
            glViewport(0, 0, 4096, 4096)

            skyboxShader.bind()
            skyboxShader.applyBinding {
                buffer(scene.camera.uboBinding)
                buffer(scene.rtwsm.uboBinding)
            }
            skyboxShader.setOriginOffset(
                0.0,
                MathUtil.clamp(scene.camera.pos.y - 64.0, 1.0, 512.0) * 96.0,
                0.0
            )
            skyboxShader.uniform1f("uTime", GLFW.glfwGetTime().toFloat())

            vao.bind()
            glDrawArrays(GL_TRIANGLES, 0, 6 * 6)

            glEnable(GL_CULL_FACE)

            fboGroup.output.fbo.bind()
            glViewport(0, 0, fboGroup.output.fbo.width, fboGroup.output.fbo.height)
        } else {
            fboGroup.output.fbo.bind()
        }

        drawShader.bind()
        skyboxShader.applyBinding {
            buffer(scene.camera.uboBinding)
            buffer(scene.rtwsm.uboBinding)
        }

        texture.bindTextureUnit(0)

        scene.basic.drawQuad()
    }
}