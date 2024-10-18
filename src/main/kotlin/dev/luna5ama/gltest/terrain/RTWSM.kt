package dev.luna5ama.gltest.terrain

import dev.fastmc.common.MathUtil
import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.*
import dev.luna5ama.gltest.Frustum
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.*
import dev.luna5ama.glwrapper.objects.*
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.asMutable
import dev.luna5ama.kmogus.copyToMutableArr
import dev.luna5ama.kmogus.usePtr
import org.joml.*
import org.lwjgl.glfw.GLFW.glfwGetTime
import kotlin.math.*

/**
 * Based on "Rectilinear Texture Warping for Fast Adaptive Shadow Mapping" by Paul Rosen from Unversity of Utah
 * https://dl.acm.org/doi/10.1145/2159616.2159641
 */
class RTWSM(private val scene: TerrainScene, private val texSize: Int) : IGLObjContainer by IGLObjContainer.Impl() {
    private val imapSize = 1024

    private val backwardCompShader = register(
        ShaderProgram(ShaderSource.Comp("terrain/rtwsm/Backward.comp"))
    ).apply {
        label = "RTWSM.Importance.BackwardComp"
    }

    private val backwardCopyShader = register(
        ShaderProgram(ShaderSource.Comp("terrain/rtwsm/BackwardCopy.comp"))
    ).apply {
        label = "RTWSM.Importance.BackwardCopy"
    }

    private val forwardShader = register(
        TerrainDrawShader(
            ShaderSource.Vert("terrain/rtwsm/Forward.vert.glsl") {
                define("IMPORTANCE_MAP_SIZE", imapSize)
                define("IMPORTANCE_MAP_SIZE_INV", 1.0f / imapSize)
            },
            ShaderSource.Frag("terrain/rtwsm/Importance.frag.glsl")
        )
    ).apply {
        label = "RTWSM.Importance.Forward"
    }

    private val collapseShader = register(
        ShaderProgram(
            ShaderSource.Comp("terrain/rtwsm/IMapCollapse.comp") {
                val groupSize = min(imapSize / 2, 512)
                val sampleCount = (imapSize / 2) / groupSize
                val warpSize = glGetInteger(GL_SUBGROUP_SIZE_KHR)
                define("GROUP_SIZE_X", warpSize)
                define("GROUP_SIZE_Y", groupSize / warpSize)
                define("SAMPLE_COUNT", sampleCount)
                define("TEXEL_SIZE", 1.0 / imapSize)
            }
        )
    ).apply {
        label = "RTWSM.IMapCollapse"
    }

    private val warpingShader = register(
        ShaderProgram(
            ShaderSource.Comp("terrain/rtwsm/GetWarp.comp") {
                define("IMAP_SIZE", imapSize)
                define("RTWSM_SIZE", texSize)
            }
        )
    ).apply {
        label = "RTWSM.GetWarp"
    }

    private val mapGenSolid = register(
        TerrainDrawShader(
            ShaderSource.Vert("terrain/rtwsm/MapGen.vert.glsl") {
                define("DATA_NAME", "fragData")
            },
            ShaderSource.Frag("terrain/rtwsm/MapGen.frag.glsl")
        )
    ).apply {
        label = "RTWSM.MapGen"
    }

    private val mapGenCutout = register(
        TerrainDrawShader(
            ShaderSource.Vert("terrain/rtwsm/MapGen.vert.glsl") {
                define("CUTOUT")
            },
            ShaderSource.Frag("terrain/rtwsm/MapGen.frag.glsl") {
                define("CUTOUT")
            }
        )
    ).apply {
        label = "RTWSM.MapGenCutout"
    }

    private val imap2D = register(TextureObject.Texture2D()).apply {
        allocate(1, ImageFormat.R32_F, imapSize, imapSize)
        linearClampEdge()
        label = "RTWSM.Importance2D"
    }

    private val imap2DUIImageBinding =
        ShaderBindingSpecs.Image("uimg_imap2D", register(imap2D.createView(ImageFormat.R32_UI)))

    private val imap2DFbo = register(FramebufferObject()).apply {
        label = "RTWSM.Importance2DFBO"
        attach(imap2D, GL_COLOR_ATTACHMENT0)
        apply { check() }
    }

    private val imap1D = register(TextureObject.Texture1DArray()).apply {
        allocate(1, ImageFormat.R32_F, imapSize, 2)
        setFilter(GL_LINEAR, GL_LINEAR)
        setWrap(GL_CLAMP_TO_BORDER, GL_CLAMP_TO_BORDER)
        setBorderColor(0.0f, 0.0f, 0.0f, 0.0f)
        label = "RTWSM.Importance1DArray"
    }

    private val warpingMap = register(TextureObject.Texture1DArray()).apply {
        allocate(1, ImageFormat.R16_G16_SN, imapSize, 2)
        label = "RTWSM.WarpingMap"
        parameteri(GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        parameteri(GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        parameteri(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER)
        parameteri(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER)
        parameteri(GL_TEXTURE_WRAP_R, GL_CLAMP_TO_BORDER)
        parameterfv(GL_TEXTURE_BORDER_COLOR, 0.0f, 0.0f, 0.0f, 0.0f)
    }

    val shadowDepthTexture = register(TextureObject.Texture2D()).apply {
        allocate(8, ImageFormat.Depth32F, texSize, texSize)
        setMipLevels(0, 7)
        setMipLod(0.0f, 7.0f)
        setFilter(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR)
        setWrap(GL_CLAMP_TO_BORDER, GL_CLAMP_TO_BORDER)
        setBorderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private val renderFbo = register(FramebufferObject()).apply {
        attach(shadowDepthTexture, GL_DEPTH_ATTACHMENT, 0)
        check()
    }

    val shadowMapSamplerBindings = listOf(
        ShaderBindingSpecs.Sampler("usam_shadowDepthComp", shadowDepthTexture, scene.samplerManager.get {
            mipLevels(0, 7)
            filter(FilterMode.LinearMipmapLinear, FilterMode.Linear)
            compare(CompareMode.CompareRefToTexture, CompareFunc.Less)
            wrap(WrapMode.ClampToBorder, WrapMode.ClampToBorder)
            borderColor(1.0f, 1.0f, 1.0f, 1.0f)
        }),
        ShaderBindingSpecs.Sampler("usam_shadowDepth", shadowDepthTexture),
        ShaderBindingSpecs.Sampler("usam_warpingMap", warpingMap)
    )

    private val ubo = register(BufferObject.Immutable()).apply {
        allocate(64L * 2L + 16L * 6L, GL_DYNAMIC_STORAGE_BIT)
    }
    val uboBinding = ShaderBindingSpecs.Buffer("ShadowMap", ubo, BufferTarget.Uniform)

    val projection = ShadowMapProjection()
    val lightDir = Vector3f(0.5f, 1.0f, 0.5f).normalize()

    suspend fun render() {
//        lightDir.set(0.2f, 0.5f, 0.8f).normalize() // HAllway

//        lightDir.set(0.2f, 1.0f, 0.8f).normalize() // HAllway


        lightDir.set(0.1f, 0.5f, 0.8f).normalize() // brickhouse
//        lightDir.set(0.5f, 0.6f, 0.5f).normalize() // brickhousse

//        lightDir.set(0.9f, 0.8f, 0.1f).normalize() // woodhouse

//        lightDir.set(0.5f, 0.6f, 0.5f).normalize() // badlands

//        lightDir.set(1.0f, 1.5f, 0.3f).normalize() // badlands
//        lightDir.set(0.7f, 0.8f, 0.5f).normalize() // badlands
//            lightDir.set(-0.4f, 0.8f, -0.5f).normalize() // badlands

//        lightDir.set(0.5f, 0.8f, 0.7f).normalize() // mountains
//        lightDir.set(0.4f, 1.0f, 0.8f).normalize() // mountains
//        lightDir.set(0.1f, 1.2f, -0.6f).normalize() // mountains
//        lightDir.set(-0.4f, 1.5f, -0.4f).normalize() // mountains
//        lightDir.set(-0.4f, 2.0f, 0.2f).normalize() // mountains
//        lightDir.set(-0.4f, 1.0f, 0.2f).normalize() // mountains gi
//        lightDir.set(0.3f, 0.5f, -0.9f).normalize()  // mountains gi 2

//        lightDir.set(0.6f, 2.0f, -0.3f).normalize() // jungle


//        lightDir.set(0.6f, 1.0f, 0.4f).normalize() // romm
//        lightDir.set(-0.4f, 1.0f, 0.5f).normalize() // street
//        lightDir.set(0.5f, 1.8f, 0.4f).normalize() // street
//        lightDir.set(0.4f, 0.5f, -0.8f).normalize() // street

//        lightDir.set(0.6f, 1.2f, -0.8f).normalize() // Sponza
//        lightDir.set(0.1f, 1.2f, -0.6f).normalize() // Sponza
//        lightDir.set(0.2f, 1.2f, -0.85f).normalize() // Sponza
//        lightDir.set(0.15f, 1.5f, -0.6f).normalize() // Sponza
//        lightDir.set(0.4f, 1.2f, -0.5f).normalize() // Sponzsa
//        lightDir.set(0.0f, 1.2f, -0.9f).normalize() // Sponza.
//        lightDir.set(0.1f, 1.6f, -0.9f).normalize() // Sponza.

//        lightDir.set(0.5f, 0.8f, -0.5f).normalize() // Blocks

//        lightDir.set(0.6f, 0.8f, -0.4f).normalize() // HAllway

//        lightDir.set(-0.0f, 0.2f, -0.8f).normalize() // Glass dome
//        lightDir.set(-0.4f, 0.3f, -0.8f).normalize() // Glass dome
//        lightDir.set(0.8f, 0.8f, -0.8f).normalize() // Glass dome


//        lightDir.set(1.0f, 1.0f, 1.0f).normalize()

        val time = (maxOf(glfwGetTime() - 3.0, 0.0) * 0.01f + 0.5f) % PI
//        val time = 0.4 * PI
//        lightDir.set(
//            cos(time),
//            sin(time) * 0.8,
//            -sin(time)
//        ).normalize()

        glDebugGroup("setup") {
            setup()
            setParams()
        }

        glDebugGroup("Backward analysis") { backwardComputePass() }
        glDebugGroup("Forward analysis") { forwardPass() }
        glDebugGroup("IMAP blur") { imapBlur() }
        glDebugGroup("IMAP collapse") { imapCollapse() }
        glDebugGroup("Gen warp") { genWrap() }
        glDebugGroup("shadow map render") { shadowMapRender() }
        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
    }

    private suspend fun setup() {
        projection.setupCamera()
        MemoryStack {
            val arr = calloc(ubo.size).asMutable()
            projection.matrix.copyToMutableArr(arr)
            projection.invMatrix.copyToMutableArr(arr)
            arr.usePtr {
                setFloatInc(texSize.toFloat())
                    .setFloatInc(texSize.toFloat())
                    .setFloatInc(1.0f / texSize.toFloat())
                    .setFloatInc(1.0f / texSize.toFloat())
            }
            arr.usePtr {
                setFloatInc(projection.boundSize.x)
                    .setFloatInc(projection.boundSize.y)
                    .setFloatInc(1.0f / projection.boundSize.x)
                    .setFloatInc(1.0f / projection.boundSize.y)
            }
            arr.usePtr {
                setFloatInc(projection.planeZ.x)
                    .setFloatInc(projection.planeZ.y)
                    .setFloatInc(1.0f / projection.planeZ.x)
                    .setFloatInc(1.0f / projection.planeZ.y)
            }
            arr.usePtr {
                setFloatInc((scene.camera.pos.x - projection.lastUpdateCamPos.x).toFloat())
                    .setFloatInc((scene.camera.pos.y - projection.lastUpdateCamPos.y).toFloat())
                    .setFloatInc((scene.camera.pos.z - projection.lastUpdateCamPos.z).toFloat())
                    .setFloatInc(0.0f)
            }
            arr.usePtr {
                setFloatInc(lightDir.x)
                    .setFloatInc(lightDir.y)
                    .setFloatInc(lightDir.z)
                    .setFloatInc(0.0f)
            }
            arr.usePtr {
                setFloatInc(projection.boundSize.z)
                    .setFloatInc(1.0f / projection.boundSize.z)
            }

            ubo.invalidate()
            ubo.upload(arr.basePtr)
        }

        glClearNamedFramebufferf(imap2DFbo.id, GL_COLOR, 0, 0.0f, 0.0f, 0.0f, 0.0f)

        val normalMatrix = Matrix3f(scene.camera.modelView)
        normalMatrix.invert().transpose()

        scene.setupFrustumCullAdvanced("RTWSM", 0..1) {
            true
        }
    }

    private fun setParams() {
        val vPerGroupF = 0xFFFFFFFFL.toFloat()
        val forwardBaseImportance = 0.5f
        val backwardBaseImportance = 16.0f
        backwardCompShader.uniform1f("uvImportanceMul", vPerGroupF)
        backwardCopyShader.uniform1f("uvImportanceMul", backwardBaseImportance / vPerGroupF)
        forwardShader.uniform1f("uBaseImportance", forwardBaseImportance)
    }

    private fun backwardComputePass() {
        glDebugGroup("Compute") {
            val workGroupX = (scene.camera.screenWidth + 15) / 16
            val workGroupY = (scene.camera.screenHeight + 15) / 16

            imap2D.clearImage()

            backwardCompShader.bind()
            backwardCompShader.applyBinding {
                sampler(shadowMapSamplerBindings)
                sampler(scene.solidGBuffer.genericTexBindings)

                image(imap2DUIImageBinding)
                image("uimg_gData1", scene.solidGBuffer.tex1)

                buffer(scene.camera.uboBinding)
                buffer(uboBinding)
            }
            timer.begin()
            glDispatchCompute(workGroupX, workGroupY, 1)
            timer.end()
//            if (printTimer.tickAndReset(3000)) {
//                println("Backward compute time: %.4f ms".format(timer.averageMS()))
//            }
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        }

        glDebugGroup("Copy") {
            backwardCopyShader.bind()
            backwardCopyShader.applyBinding {
                image(imap2DUIImageBinding)
            }
            val copyWorkGroupXY = (imapSize + 15) / 16
            glDispatchCompute(copyWorkGroupXY, copyWorkGroupXY, 1)
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_FRAMEBUFFER_BARRIER_BIT)
        }
    }

    private fun forwardPass() {
        val normalMatrix = Matrix3f(scene.camera.modelView)
        normalMatrix.invert().transpose()

        imap2DFbo.bind()
        glViewport(0, 0, imapSize, imapSize)

        forwardShader.bind()
        forwardShader.setOriginOffset(
            scene.originX - scene.camera.pos.x,
            scene.originY - scene.camera.pos.y,
            scene.originZ - scene.camera.pos.z
        )
        forwardShader.applyBinding {
            sampler(shadowMapSamplerBindings)

            buffer(scene.camera.noJitterUboBinding)
            buffer(uboBinding)
        }

        glDisable(GL_CULL_FACE)
        glDisable(GL_DEPTH_CLAMP)

        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)

        scene.terrainMesh.layers[0].getDrawViewOC("View").draw()
        scene.terrainMesh.layers[1].getDrawViewOC("View").draw()

        glEnable(GL_CULL_FACE)
    }

    private val warpingPassImagesBinding1 = ShaderBindingSpecs.of {
        sampler("usam_imap2D", imap2D)
        image("uimg_imap1D", imap1D)
    }

    private val warpingPassImagesBinding2 = ShaderBindingSpecs.of {
        image("uimg_imap1D", imap1D)
        image("uimg_warpingMap", warpingMap)
        buffer(uboBinding)
    }

    private fun imapBlur() {
        repeat(4) {
            scene.gaussianBlur.process(
                GaussianBlur.Type.LINEAR,
                8, 4, it * 1.0f + 1.0f,
                imap2D,
                imap2D
            )
        }
    }

    private val timer = GPUTimer(100)
    private val printTimer = TickTimer()

    private fun imapCollapse() {
        collapseShader.bind()
        collapseShader.applyBinding(warpingPassImagesBinding1)
        glDispatchCompute(imapSize, 2, 1)

        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
    }

    private fun genWrap() {
        warpingShader.bind()
        warpingShader.applyBinding(warpingPassImagesBinding2)
        glDispatchCompute(2, 1, 1)

        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
    }

    private val rtwsmCullBoxDrawShader = TerrainDrawShader(
        ShaderSource.Vert("terrain/culling/CullBoxDrawShadow.vert.glsl"),
        ShaderSource.Frag("terrain/culling/CullBoxDraw.frag.glsl")
    )

    private fun shadowMapRender() {
        val drawView0 = scene.terrainMesh.layers[0].getDrawViewOC("RTWSM")
        val drawView1 = scene.terrainMesh.layers[1].getDrawViewOC("RTWSM")
        renderFbo.bind()
        renderFbo.check()
        glClearNamedFramebufferf(renderFbo.id, GL_DEPTH, 0, 1.0f)


        // Indirect compute last visible
        drawView0.setupIndirect(TerrainMesh.VisibleState.LAST_VISIBLE)
        drawView1.setupIndirect(TerrainMesh.VisibleState.LAST_VISIBLE)

        // Indirect draw last visible
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)
        glViewport(0, 0, texSize, texSize)
        glDisable(GL_BLEND)
        glEnable(GL_DEPTH_CLAMP)
        glDisable(GL_CULL_FACE)
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT)

        bindSolid()
        drawView0.draw()
        bindCutout()
        drawView1.draw()

        // Bounding box cull test
        drawView0.setupCullBox()
        drawView1.setupCullBox()

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        glDisable(GL_DEPTH_CLAMP)
        glEnable(GL_CULL_FACE)
        glDepthMask(false)
        glColorMask(false, false, false, false)

        drawView0.cullTestDraw(rtwsmCullBoxDrawShader) {
            sampler(shadowMapSamplerBindings)
            buffer(uboBinding)
        }
        drawView1.cullTestDraw(rtwsmCullBoxDrawShader) {
            sampler(shadowMapSamplerBindings)
            buffer(uboBinding)
        }

        glDisable(GL_CULL_FACE)
        glColorMask(true, true, true, true)
        glDepthMask(true)


        // Indirect compute new visible
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        drawView0.setupIndirect(TerrainMesh.VisibleState.NEWLY_VISIBLE)
        drawView1.setupIndirect(TerrainMesh.VisibleState.NEWLY_VISIBLE)

        // Indirect draw new visible
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT)
        glEnable(GL_DEPTH_CLAMP)
        glDepthFunc(GL_LEQUAL)

        bindSolid()
        drawView0.draw()
        bindCutout()
        drawView1.draw()

        // Indirect compute current visible
        drawView0.setupIndirect(TerrainMesh.VisibleState.CURRENT_VISIBLE)
        drawView1.setupIndirect(TerrainMesh.VisibleState.CURRENT_VISIBLE)
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT)

        scene.hizGen.processDepth(
            shadowDepthTexture,
            shadowDepthTexture,
            HiZBufferGen.KernelType.MIN,
            HiZBufferGen.DepthType.LINEAR,
            projection.planeZ
        )
    }

    private fun bindSolid() {
        mapGenSolid.bind()
        mapGenSolid.setOriginOffset(
            scene.originX - scene.camera.pos.x,
            scene.originY - scene.camera.pos.y,
            scene.originZ - scene.camera.pos.z
        )
        mapGenSolid.applyBinding {
            sampler("usam_warpingMap", warpingMap)

            buffer(scene.camera.noJitterUboBinding)
            buffer(uboBinding)
        }
    }

    private fun bindCutout() {
        mapGenCutout.bind()
        mapGenCutout.setOriginOffset(
            scene.originX - scene.camera.pos.x,
            scene.originY - scene.camera.pos.y,
            scene.originZ - scene.camera.pos.z
        )
        mapGenCutout.applyBinding {
            sampler(scene.textures.blockAlbedo)
            sampler(scene.textures.blockSpecular)
            sampler("usam_warpingMap", warpingMap)

            buffer(scene.camera.noJitterUboBinding)
            buffer(uboBinding)
        }
    }

    private val shadowDepthSampler = scene.samplerManager.get {
        filter(FilterMode.NearestMipmapNearest, FilterMode.Nearest)
        wrap(WrapMode.ClampToBorder, WrapMode.ClampToBorder)
        borderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    fun renderDebug(fboGroup: FboGroup) {
        fboGroup.input.fbo.bind()
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glDisable(GL_STENCIL_TEST)
        scene.basic.blitDepthShader.bind()
        scene.basic.blitDepthShader.uniform1f("uPower", 4.0f)
        scene.basic.blitDepthShader.uniform1f("uLod", 0.0f)
        scene.basic.blitDepthShader.applyBinding {
            sampler("usam_input", shadowDepthTexture, shadowDepthSampler)
            buffer(scene.camera.noJitterUboBinding)
        }

        val sizes = 512
        glViewport(0, 0, sizes, sizes)
        scene.basic.drawQuad()

        glViewport(0, sizes, sizes, sizes)
        scene.basic.blitDepthShader.uniform1f("uPower", 1.0f)
        scene.basic.blitDepthShader.uniform1f("uMul", 0.25f)
        imap2D.bindTextureUnit(0)
        scene.basic.drawQuad()

        scene.basic.blitDepthShader.uniform1f("uPower", 1.0f)
        scene.basic.blitDepthShader.uniform1f("uMul", 1.0f)

        glViewport(0, sizes + sizes, sizes, 16)
        scene.basic.blit1DArrayH.bind()
        scene.basic.blit1DArrayH.uniform1f("uLayer", 0.0f)
        scene.basic.blit1DArrayH.applyBinding {
            sampler("usam_input", imap1D)
            buffer(scene.camera.noJitterUboBinding)
        }
        scene.basic.drawQuad()

        glViewport(0, sizes + sizes + 16, sizes, 16)
        scene.basic.blit1DArrayH.applyBinding {
            sampler("usam_input", warpingMap)
            buffer(scene.camera.noJitterUboBinding)
        }
        scene.basic.drawQuad()

        glViewport(sizes, sizes, 16, sizes)
        scene.basic.blit1DArrayV.bind()
        scene.basic.blit1DArrayV.uniform1f("uLayer", 1.0f)
        scene.basic.blit1DArrayV.applyBinding {
            sampler("usam_input", imap1D)
            buffer(scene.camera.noJitterUboBinding)
        }
        scene.basic.drawQuad()

        glViewport(sizes + 16, sizes, 16, sizes)
        scene.basic.blit1DArrayV.applyBinding {
            sampler("usam_input", warpingMap)
            buffer(scene.camera.noJitterUboBinding)
        }
        scene.basic.drawQuad()

        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
    }

    inner class ShadowMapProjection {
        val matrix = Matrix4f()
        val invMatrix = Matrix4f()
        val boundSize = Vector3f(128.0f, 128.0f, 128.0f)
        val planeZ = Vector2f()

        val lastUpdateCamPos = Vector3d()

        private val updateTimer = TickTimer()
        private val frustum = Frustum(scene.camera.noJitterInvCombined)
        private val lastLightX = Vector2f()
        private val lastLightY = Vector2f()
        private val lastLightZ = Vector2f()

        init {
            updateTimer.reset(-69420L)
        }

        fun setupCamera() {
            frustum.update(scene.camera.noJitterInvCombined)

            val modelView = Matrix4f()
            modelView.identity()
            modelView.lookAt(
                0.0f, 0.0f, 0.0f,
                -lightDir.x, -lightDir.y, -lightDir.z,
                0.0f, 1.0f, 0.0f
            )

            val temp = Vector4f()

            var minLightX = Float.POSITIVE_INFINITY
            var maxLightX = Float.NEGATIVE_INFINITY

            var minLightY = Float.POSITIVE_INFINITY
            var maxLightY = Float.NEGATIVE_INFINITY

            var minLightZ = Float.POSITIVE_INFINITY
            var maxLightZ = Float.NEGATIVE_INFINITY

            val minSceneX = (min(
                scene.terrainMesh.layers[0].minChunkX,
                scene.terrainMesh.layers[1].minChunkX
            ) * 16.0f - scene.camera.pos.x).toFloat()
            val maxSceneX = (max(
                scene.terrainMesh.layers[0].maxChunkX,
                scene.terrainMesh.layers[1].maxChunkX
            ) * 16.0f - scene.camera.pos.x).toFloat()

            val minSceneY = (min(
                scene.terrainMesh.layers[0].minChunkY,
                scene.terrainMesh.layers[1].minChunkY
            ) * 16.0f - scene.camera.pos.y).toFloat()
            val maxSceneY = (max(
                scene.terrainMesh.layers[0].maxChunkY,
                scene.terrainMesh.layers[1].maxChunkY
            ) * 16.0f - scene.camera.pos.y).toFloat()

            val minSceneZ = (min(
                scene.terrainMesh.layers[0].minChunkZ,
                scene.terrainMesh.layers[1].minChunkZ
            ) * 16.0f - scene.camera.pos.z).toFloat()
            val maxSceneZ = (max(
                scene.terrainMesh.layers[0].maxChunkZ,
                scene.terrainMesh.layers[1].maxChunkZ
            ) * 16.0f - scene.camera.pos.z).toFloat()

            val sceneBound = arrayOf(
                Vector4f(minSceneX, minSceneY, minSceneZ, 1.0f),
                Vector4f(maxSceneX, minSceneY, minSceneZ, 1.0f),
                Vector4f(minSceneX, maxSceneY, minSceneZ, 1.0f),
                Vector4f(maxSceneX, maxSceneY, minSceneZ, 1.0f),
                Vector4f(minSceneX, minSceneY, maxSceneZ, 1.0f),
                Vector4f(maxSceneX, minSceneY, maxSceneZ, 1.0f),
                Vector4f(minSceneX, maxSceneY, maxSceneZ, 1.0f),
                Vector4f(maxSceneX, maxSceneY, maxSceneZ, 1.0f)
            )

            for (vec in sceneBound) {
                temp.set(vec)
                temp.x = MathUtil.clamp(temp.x, minSceneX, maxSceneX)
                temp.y = MathUtil.clamp(temp.y, minSceneY, maxSceneY)
                temp.z = MathUtil.clamp(temp.z, minSceneZ, maxSceneZ)

                modelView.transform(temp)
                minLightX = min(minLightX, temp.x)
                maxLightX = max(maxLightX, temp.x)
                minLightY = min(minLightY, temp.y)
                maxLightY = max(maxLightY, temp.y)
                minLightZ = min(minLightZ, temp.z)
                maxLightZ = max(maxLightZ, temp.z)
            }

            matrix.identity()
            matrix.scale(1.0f, 1.0f, 1.0f)
            matrix.ortho(minLightX, maxLightX, minLightY, maxLightY, -maxLightZ, -minLightZ)
            matrix.mul(modelView)

            matrix.invert(invMatrix)

            lastLightX.set(minLightX, maxLightX)
            lastLightY.set(minLightY, maxLightY)
            lastLightZ.set(minLightZ, maxLightZ)

            boundSize.set(maxLightX - minLightX, maxLightY - minLightY, maxLightZ - minLightZ)
            planeZ.set(minLightZ, maxLightZ)

            lastUpdateCamPos.set(scene.camera.pos)
            updateTimer.reset()
        }
    }
}