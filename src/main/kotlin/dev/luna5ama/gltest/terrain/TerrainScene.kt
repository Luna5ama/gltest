package dev.luna5ama.gltest.terrain

import dev.fastmc.common.MathUtil
import dev.fastmc.common.ParallelUtils
import dev.fastmc.common.sort.IntIntrosort
import dev.fastmc.common.sq
import dev.luna5ama.gltest.*
import dev.luna5ama.gltest.structs.DrawElementsIndirectCommand
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.post.*
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.FilterMode
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.enums.WrapMode
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.joml.FrustumIntersection
import org.joml.Vector3d
import org.joml.Vector4f
import org.lwjgl.glfw.GLFW
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

class TerrainScene(gameInstance: GameInstance) : AbstractScene(gameInstance) {
//    val terrainMesh = register(TerrainMesh(this, File("dumpMountain")))
    val terrainMesh = register(TerrainMesh(this, File("dumpMountainV")))
//    val terrainMesh = register(TerrainMesh(this, File("dumpBadlands")))
//    val terrainMesh = register(TerrainMesh(this, File("dumpForest")))
//    val terrainMesh = register(TerrainMesh(this, File("dumpTest")))
//    val terrainMesh = register(TerrainMesh(this, File("dumpJungle")))
//        val terrainMesh = register(TerrainMesh(this,File("dumpTaiga")))
//    val terrainMesh = register(TerrainMesh(this, File("dumpDesert")))

    val solidLighting = register(SolidLighting(this))

    val old = register(PostShaders(this))
    val deferredShaders = register(DeferredShaders(this))
    val textures = register(Textures(this))

    private val swapFbo = register(MainFbo("Swap"))
    private val mainFbo = register(MainFbo("Main"))
    private val fboGroup = register(FboGroup())

    val basic = register(Basic())
    val samplers = register(Samplers())
    val samplerManager = register(SamplerManager(this))
    val hizGen = register(HiZBufferGen(this))

    private val skybox = register(Skybox(this))
//    private val atmosphere = register(Atmosphere(this))

    val rtwsm = register(RTWSM(this, 2048))


    val solidGBuffer = register(GBuffer(this, "Solid"))

    val waterGBuffer = register(GBuffer(this, "Water"))
    private val waterReflection = register(WaterRef(this))

//    private val sun = Sun(this)

    val solidVelocity = register(VelocityBuffer(this))
    val combinedVelocity = register(VelocityBuffer(this))

    //    val voLight = VolumetricLight(this, 64, 8)
    val txaa = TXAA(this, 8, 0.5f)
    val autoExposure = register(AutoExposure(this))
    private val toneMapping = register(ToneMapping(this))
    private val bloom = register(Bloom(this))
    private val gammaCorrection = register(GammaCorrection(this))
    private val sobel = register(Sobel(this))

    val gaussianBlur = register(GaussianBlur(this))

    val skyColor = Vector4f()

    init {
        camera.viewDistance = 8.0f * 16.0f
        camera.fov = 90.0f

        camera.rotation.x = 0.0f
        camera.rotation.y = 90.0f

        camera.pos.x = 0.0
        camera.pos.y = 120.0
        camera.pos.z = 0.0

//        camera.pos.x = 80.0
//        camera.pos.y = 80.0
//        camera.pos.z = 12.0
//
//        camera.rotation.x = 50.0f
//        camera.rotation.y = 0.0f

//        Waterside
//        camera.pos.x = 160.0
//        camera.pos.y = 70.0
//        camera.pos.z = 20.0
//
//        camera.rotation.x = 50.0f
//        camera.rotation.y = 10.0f

//        camera.pos.x = 118.0
//        camera.pos.y = 70.0
//        camera.pos.z = 38.0
//
//        camera.rotation.x = -15.0f
//        camera.rotation.y = 18.0f

//        camera.pos.x = 128.0
//        camera.pos.y = 67.0
//        camera.pos.z = 5.0
//
//        camera.rotation.x = -40.0f
//        camera.rotation.y = 0.0f

//        camera.pos.x = 62.0
//        camera.pos.y = 60.0
//        camera.pos.z = 13.5
//
//        camera.rotation.x = -75.0f
//        camera.rotation.y = -20.0f

        runCatching {
            val prop = Properties()
            prop.load(File("camera.properties").inputStream())
            camera.pos.x = prop["posX"].toString().toDouble()
            camera.pos.y = prop["posY"].toString().toDouble()
            camera.pos.z = prop["posZ"].toString().toDouble()
            camera.rotation.x = prop["yaw"].toString().toFloat()
            camera.rotation.y = prop["pitch"].toString().toFloat()
            camera.fov = prop["fov"].toString().toFloat()
            camera.viewDistance = prop["viewDistance"].toString().toFloat()
        }.onFailure {
            it.printStackTrace()
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            saveCameraPos()
        })
    }

    val originX get() = -32.0f * 16.0f
    val originY get() = TerrainMesh.MIN_CHUNK_Y * 16.0f
    val originZ get() = -32.0f * 16.0f

    init {
        println(glGetString(GL_VERSION))
        println(glGetString(GL_VENDOR))
        println(glGetString(GL_RENDERER))
    }

    override fun init(mouse: Mouse, keyboard: Keyboard) {
        super.init(mouse, keyboard)
    }

    override suspend fun renderFrame() {
//        println(camera.pos)
        val mul = 3.0f
        skyColor.set((0.42f * mul).pow(2.2f), (0.56f * mul).pow(2.2f), (0.85f * mul).pow(2.2f), 2.0f)

        if (!preDraw()) return

        glEnable(GL_DEPTH_CLAMP)

        glDebugGroup("Early pass") {
            earlyPass()
        }
        glDebugGroup("Solid geometry pass") {
            solidDeferredPass()
        }
        glDebugGroup("RTWSM") {
            rtwsm.render()
        }
        glDebugGroup("Solid lighting pass") {
            solidLighting.render(fboGroup)
        }
        glDebugGroup("waterDeferredPass") {
            waterDeferredPass()
        }
        glDebugGroup("waterLightingPass") {
            waterLightingPass()
        }
        glDebugGroup("Post pass") {
            postPass()
        }
        samplers.clearBinding()
    }

    override fun destroy() {
        saveCameraPos()
    }

    private fun saveCameraPos() {
        val props = Properties()
        props["posX"] = camera.pos.x.toString()
        props["posY"] = camera.pos.y.toString()
        props["posZ"] = camera.pos.z.toString()
        props["yaw"] = (camera.rotation.x % 360.0f).toString()
        props["pitch"] = (camera.rotation.y % 360.0f).toString()
        props["fov"] = camera.fov.toString()
        props["viewDistance"] = camera.viewDistance.toString()
        runCatching {
            props.store(
                File("camera.properties").outputStream(), null
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    private suspend fun earlyPass() {
        skybox.render(fboGroup)
        glBlitNamedFramebuffer(
            fboGroup.output.fbo.id,
            fboGroup.input.fbo.id,
            0, 0, mainFbo.width, mainFbo.height,
            0, 0, mainFbo.width, mainFbo.height,
            GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT,
            GL_NEAREST
        )
    }

    val cullBoxDrawShader = TerrainDrawShader(
        ShaderSource.Vert("/terrain/culling/CullBoxDraw.vert.glsl"),
        ShaderSource.Frag("/terrain/culling/CullBoxDraw.frag.glsl")
    )

    private suspend fun solidDeferredPass() {
        val drawView0 = terrainMesh.layers[0].getDrawViewOC("View")
        val drawView1 = terrainMesh.layers[1].getDrawViewOC("View")

        glDisable(GL_DITHER)
        solidGBuffer.clear()
        solidGBuffer.bindFbo()

        glEnable(GL_STENCIL_TEST)
        glStencilMask(0xFF)
        glStencilFunc(GL_ALWAYS, 1, 0xFF)
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)

        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)

        glDisable(GL_BLEND)

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)

        // Indirect compute last visible
        drawView0.setupIndirect(TerrainMesh.VisibleState.LAST_VISIBLE)
        drawView1.setupIndirect(TerrainMesh.VisibleState.LAST_VISIBLE)

        // Indirect draw last visible
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT)
        deferredShaders.solid.bind()
        deferredShaders.solid.applyBinding {
            sampler(textures.blockAlbedo)
            buffer(camera.uboBinding)
            buffer(rtwsm.uboBinding)
        }
        drawView0.draw()
        deferredShaders.cutout.bind()
        deferredShaders.cutout.applyBinding {
            sampler(textures.blockAlbedo)
            buffer(camera.uboBinding)
            buffer(rtwsm.uboBinding)
        }
        drawView1.draw()

        // Bounding box cull test
        drawView0.setupCullBox()
        drawView1.setupCullBox()

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        glDisable(GL_CULL_FACE)
        glDepthFunc(GL_LEQUAL)
        glDisable(GL_DEPTH_CLAMP)
        glDisable(GL_STENCIL_TEST)
        glDepthMask(false)
        glColorMask(false, false, false, false)

        drawView0.cullTestDraw(cullBoxDrawShader) {}
        drawView1.cullTestDraw(cullBoxDrawShader) {}

        glColorMask(true, true, true, true)
        glDepthMask(true)
        glEnable(GL_STENCIL_TEST)

        // Indirect compute new visible
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        drawView0.setupIndirect(TerrainMesh.VisibleState.NEWLY_VISIBLE)
        drawView1.setupIndirect(TerrainMesh.VisibleState.NEWLY_VISIBLE)

        // Indirect draw new visible
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT)
        deferredShaders.solid.bind()
        drawView0.draw()
        deferredShaders.cutout.bind()
        drawView1.draw()

        // Indirect compute current visible
        drawView0.setupIndirect(TerrainMesh.VisibleState.CURRENT_VISIBLE)
        drawView1.setupIndirect(TerrainMesh.VisibleState.CURRENT_VISIBLE)
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT)

        glDisable(GL_STENCIL_TEST)
        glEnable(GL_DITHER)

        glDebugGroup("Solid HI-Z") {
            hizGen.processDepth(solidGBuffer.depthStencilTex, solidGBuffer.depthStencilTex, HiZBufferGen.KernelType.AVG, HiZBufferGen.DepthType.LOG, camera.planeZ)
        }
    }

    private suspend fun waterDeferredPass() {
        glDisable(GL_DITHER)
        waterGBuffer.clear()
        waterGBuffer.bindFbo()

        val shader = deferredShaders.water
        shader.bind()
        shader.uniform1f("uTime", GLFW.glfwGetTime().toFloat())
        shader.applyBinding {
            sampler(textures.blockAlbedo)
            sampler(textures.noiseTexture)
            buffer(camera.uboBinding)
        }

        glEnable(GL_STENCIL_TEST)
        glStencilMask(0xFF)
        glStencilFunc(GL_ALWAYS, 1, 0xFF)
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)

        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)

        glDisable(GL_BLEND)
        glEnablei(GL_BLEND, 1)
        glBlendFuncSeparatei(1, GL_ONE, GL_ONE, GL_ONE, GL_ONE)
        glBlendEquationi(1, GL_MAX)

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)

        glBlitNamedFramebuffer(
            solidGBuffer.fbo.id,
            waterGBuffer.fbo.id,
            0, 0, solidGBuffer.width, solidGBuffer.height,
            0, 0, waterGBuffer.width, waterGBuffer.height,
            GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT, GL_NEAREST
        )

        waterGBuffer.setDrawBuffers()
        terrainMesh.layers[2].getDrawViewOrCreate("View").draw()

        glBlendEquation(GL_FUNC_ADD)

        glDisable(GL_STENCIL_TEST)
        glEnable(GL_DITHER)
    }

    private suspend fun waterLightingPass() {
        fboGroup.swap()
        glDebugGroup("SSR") {
            waterReflection.render(waterGBuffer, fboGroup)
        }
        fboGroup.swap()

        glDisable(GL_DITHER)
        glBlitNamedFramebuffer(
            waterGBuffer.fbo.id,
            mainFbo.fbo.id,
            0, 0, waterGBuffer.width, waterGBuffer.height,
            0, 0, mainFbo.width, mainFbo.height,
            GL_STENCIL_BUFFER_BIT or GL_DEPTH_BUFFER_BIT, GL_NEAREST
        )
        glEnable(GL_DITHER)

        val shader = old.water
        shader.bind()
        shader.uniform4f("skyColor", skyColor.x, skyColor.y, skyColor.z, skyColor.w)
        shader.applyBinding {
            sampler(solidGBuffer.texBindings)
            sampler(waterGBuffer.texBindings)
            sampler("usam_backSceneTex", swapFbo.colorTex, samplerManager.get {
                filter(FilterMode.Nearest, FilterMode.Nearest)
                wrap(WrapMode.ClampToEdge, WrapMode.ClampToEdge)
            })
            sampler(textures.lightMap)
            sampler(waterReflection.refUvTexBinding)
            sampler(skybox.textureBinding)
            sampler(rtwsm.shadowMapSamplerBindings)

            buffer(camera.uboBinding)
            buffer(rtwsm.uboBinding)
            buffer(old.fogParametersBinding)
        }
        mainFbo.fbo.bind()

        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)

        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        glDisable(GL_DEPTH_TEST)

        glEnable(GL_STENCIL_TEST)
        glStencilFunc(GL_EQUAL, 1, 0xFF)
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP)

        basic.drawQuad()

        glDisable(GL_STENCIL_TEST)
        samplers.clearBinding()
    }

    private suspend fun postPass() {
        fboGroup.swap()

        glDebugGroup("Blit depth stencil") {
            glDisable(GL_DITHER)
            glBlitNamedFramebuffer(
                waterGBuffer.fbo.id,
                fboGroup.input.fbo.id,
                0, 0, waterGBuffer.width, waterGBuffer.height,
                0, 0, mainFbo.width, mainFbo.height,
                GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT,
                GL_NEAREST
            )

            glBlitNamedFramebuffer(
                waterGBuffer.fbo.id,
                fboGroup.output.fbo.id,
                0, 0, waterGBuffer.width, waterGBuffer.height,
                0, 0, mainFbo.width, mainFbo.height,
                GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT,
                GL_NEAREST
            )
            glEnable(GL_DITHER)
        }

        glDebugGroup("CombinedVelocity") { combinedVelocity.render(waterGBuffer) }
        glDebugGroup("AutoExposure") { autoExposure.render(fboGroup) }
//        glDebugGroup("Bloom") { bloom.render(fboGroup) }
        glDebugGroup("ToneMapping") { toneMapping.render(fboGroup) }

//        samplers.clearBinding()
//        glViewport(0, 0, camera.screenWidth, camera.screenHeight)
//        solidLighting.hbao.finalTex.bindTextureUnit(0)
//        basic.blitShader.bind()
//        basic.blitShader.uniformSubroutines(ShaderStage.FragmentShader, "sampleRGBPA")
//        basic.blitShader.uniform1f("uMul", 1.0f)
//        basic.blitShader.uniform1f("uAdd", 0.0f)
//        basic.blitShader.uniform1f("uPower", 1.0f)
//        basic.blitShader.uniform1f("uLod", 0.0f)
//        basic.drawQuad()

        glDebugGroup("GammaCorrection") { gammaCorrection.render(fboGroup) }

        glDebugGroup("TXAA") { txaa.render(fboGroup) }

//        glDebugGroup("Atmosphere") { atmosphere.postPass(fboGroup) }
//        glDebugGroup("VolumetricLight") {
//            voLight.render(waterGBuffer, fboGroup)
//        }

//        lensFlare.render(fboGroup)
//        ssao.renderDebug(fboGroup)
//        rtwsm.renderDebug(fboGroup)

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        val ratio = 1
        val width = swapFbo.width / ratio
        val height = swapFbo.height / ratio
        val xEdge = (swapFbo.width - width) / 2
        val yEdge = (swapFbo.height - height) / 2
        glBlitNamedFramebuffer(
            fboGroup.input.fbo.id,
            0,
            xEdge, yEdge, xEdge + width, yEdge + height,
            0, 0, swapFbo.width, swapFbo.height,
            GL_COLOR_BUFFER_BIT,
            GL_NEAREST
        )
//        glBlitNamedFramebuffer(
//            solidVelocity.accumNewPixelFbo.id,
//            0,
//            0, 0, solidVelocity.accumNewPixelFbo.width, solidVelocity.accumNewPixelFbo.height,
//            0, 0, swapFbo.width, swapFbo.height,
//            GL_COLOR_BUFFER_BIT,
//            GL_NEAREST
//        )
        fboGroup.swap()
    }

    private suspend fun preDraw(): Boolean {
        glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS)

        camera.updateScreenSize()
        if (camera.screenWidth < 1 || camera.screenHeight < 1) return false
        glViewport(0, 0, camera.screenWidth, camera.screenHeight)

        camera.update(txaa.currentJitter.x, txaa.currentJitter.y)

        val f = MathUtil.clamp(camera.viewDistance / 10.0f, 4.0f, 64.0f)
        old.linearFog(
            PostShaders.FogShape.CYLINDER,
            camera.viewDistance - f,
            camera.viewDistance,
            0.75294f,
            0.84706f,
            1.00f
        )

        solidGBuffer.update()
        waterGBuffer.update()

        setupCameraCull()

        fboGroup.reset()

        return true
    }

    private suspend fun setupCameraCull() {
        val frustumCull = FrustumIntersection(camera.noJitterCombined, false)
        val viewDistSq = (camera.viewDistance + 8.0).sq
        coroutineScope {
            launch(Dispatchers.Default) {
                setupFrustumCullAdvanced("View", 0..1) {
                    it.distanceSq(camera.pos) <= viewDistSq
                            && it.frustumCull(frustumCull, camera.pos)
                }
            }
            launch(Dispatchers.Default) {
                setupFrustumCull("View", 2..2) {
                    it.distanceSq(camera.pos) <= viewDistSq
                            && it.frustumCull(frustumCull, camera.pos)
                }
            }
        }
    }

    suspend fun setupFrustumCullAdvanced(
        viewName: String,
        range: IntRange = terrainMesh.layers.indices,
        block: (TerrainMesh.ChunkInfo) -> Boolean
    ) {
        coroutineScope {
            for (i in range) {
                launch(Dispatchers.Default) {
                    frustumCullLayerAdvanced(viewName, terrainMesh.layers[i], block)
                }
            }
        }
    }

    private suspend fun frustumCullLayerAdvanced(
        viewName: String,
        layer: TerrainMesh.Layer,
        block: (TerrainMesh.ChunkInfo) -> Boolean
    ) {
        val cameraPos = Vector3d(camera.pos)
        val drawView = withContext(gameInstance.mainContext) { layer.getDrawViewOCOrCreate(viewName) }
        val indices = drawView.sortedChunkIndices.copyOf()
        val distances = FloatArray(indices.size) {
            val chunkInfo = layer.chunkInfos[it]
            -chunkInfo.distanceSq(cameraPos).toFloat()
        }
        IntIntrosort.sort(indices, 0, indices.size, distances)

        val counter = AtomicInteger(0)

        drawView.boxIndices.ensureCapacity(layer.chunkInfos.size * 8L, false)
        val arrMain = drawView.boxIndices.asMutable()

        coroutineScope {
            ParallelUtils.splitListIndex(
                layer.chunkInfos.size,
                blockForEach = { start, end ->
                    launch(Dispatchers.Default) {
                        val arrLocal = Arr.calloc((end - start) * 8L)
                        var ptrLocal = arrLocal.ptr
                        var counterLocal = 0

                        for (i in start until end) {
                            val chunkInfo = layer.chunkInfos[indices[i]]
                            if (!block(chunkInfo)) continue
                            val chunkOffset = terrainMesh.chunkOffsets[chunkInfo.chunkIndex]
                            ptrLocal = ptrLocal.setIntInc(chunkInfo.indirectIndex)
                                .setByteInc(chunkOffset.x.toByte())
                                .setByteInc(chunkOffset.y.toByte())
                                .setByteInc(chunkOffset.z.toByte())
                                .setByteInc(0)

                            counterLocal++
                        }

                        val mainIndex = counter.getAndAdd(counterLocal)
                        memcpy(
                            arrLocal.ptr, 0L,
                            arrMain.ptr, mainIndex * 8L,
                            (ptrLocal - arrLocal.ptr)
                        )
                        arrLocal.free()
                    }
                }
            )
        }

        drawView.drawCount = counter.get()
    }

    // TODO: Add to kmogus
    private operator fun Ptr.minus(ptr: Ptr): Long {
        return this.address - ptr.address
    }

    suspend fun setupFrustumCull(
        viewName: String,
        range: IntRange = terrainMesh.layers.indices,
        block: (TerrainMesh.ChunkInfo) -> Boolean
    ) {
        coroutineScope {
            for (i in range) {
                launch(Dispatchers.Default) {
                    frustumCullLayer(viewName, terrainMesh.layers[i], block)
                }
            }
        }
    }

    private suspend fun frustumCullLayer(
        viewName: String,
        layer: TerrainMesh.Layer,
        block: (TerrainMesh.ChunkInfo) -> Boolean
    ) {
        val cameraPos = Vector3d(camera.pos)
        val drawView = withContext(gameInstance.mainContext) { layer.getDrawViewOrCreate(viewName) }
        val indices = drawView.sortedChunkIndices.copyOf()
        val distances = FloatArray(indices.size) {
            val chunkInfo = layer.chunkInfos[it]
            chunkInfo.distanceSq(cameraPos).toFloat()
        }
        IntIntrosort.sort(indices, 0, indices.size, distances)

        val counter = AtomicInteger(0)

        val arrMain = Arr.calloc(layer.chunkInfos.size * sizeof(DrawElementsIndirectCommand)).asMutable()

        coroutineScope {
            ParallelUtils.splitListIndex(
                layer.chunkInfos.size,
                blockForEach = { start, end ->
                    launch(Dispatchers.Default) {
                        val arrLocal = Arr.calloc((end - start) * sizeof(DrawElementsIndirectCommand))
                        var counterLocal = 0
                        val structTo = DrawElementsIndirectCommand(arrLocal.ptr)
                        val structFrom = DrawElementsIndirectCommand(layer.rawIndirectClient.ptr)

                        for (i in start until end) {
                            val chunkInfo = layer.chunkInfos[indices[i]]
                            if (block(chunkInfo)) {
                                val index = counterLocal++
                                val src = structFrom[chunkInfo.indirectIndex]
                                structTo[index] = drawElementsIndirectCommand(src)
                            }
                        }

                        val mainIndex = counter.getAndAdd(counterLocal)
                        memcpy(
                            arrLocal.ptr, 0L,
                            arrMain.ptr, mainIndex * sizeof(DrawElementsIndirectCommand),
                            counterLocal * sizeof(DrawElementsIndirectCommand)
                        )
                        arrLocal.free()
                    }
                }
            )
        }

        arrMain.pos = counter.get() * sizeof(DrawElementsIndirectCommand)
        arrMain.flip()

        withContext(gameInstance.mainContext) {
            drawView.updateIndirect(arrMain)
            drawView.sortedChunkIndices = indices
            drawView.drawCount = counter.get()
            arrMain.free()
        }
    }

    private fun drawElementsIndirectCommand(src: DrawElementsIndirectCommand) = src

    inner class FboGroup : IGLObjContainer by IGLObjContainer.Impl() {
        private val inputBindingList = listOf(
            listOf(
                ShaderBindingSpecs.Sampler("usam_mainFbo_inputColor", swapFbo.colorTex),
                ShaderBindingSpecs.Sampler("usam_mainFbo_inputDepth", swapFbo.depthStencilTex)
            ),
            listOf(
                ShaderBindingSpecs.Sampler("usam_mainFbo_inputColor", mainFbo.colorTex),
                ShaderBindingSpecs.Sampler("usam_mainFbo_inputDepth", mainFbo.depthStencilTex)
            )
        )
        private val outputBindingList = listOf(listOf(
            ShaderBindingSpecs.Sampler("usam_mainFbo_outputColor", swapFbo.colorTex),
            ShaderBindingSpecs.Sampler("usam_mainFbo_outputDepth", swapFbo.depthStencilTex)
        ),
        listOf(
            ShaderBindingSpecs.Sampler("usam_mainFbo_outputColor", mainFbo.colorTex),
            ShaderBindingSpecs.Sampler("usam_mainFbo_outputDepth", mainFbo.depthStencilTex)
        ))

        private var swapIdx = 0
        private var fbos = listOf(swapFbo, mainFbo)

        val input get() = fbos[swapIdx]
        val output get() =  fbos[swapIdx xor 1]
        val combined = register(FramebufferObject())

        val inputBindings get() =  inputBindingList[swapIdx]
        val outputBindings get() =  outputBindingList[swapIdx xor 1]

        fun swap() {
            swapIdx = swapIdx xor 1
        }

        fun reset() {
            swapIdx = 0

            swapFbo.update()
            mainFbo.update()


            mainFbo.fbo.bind()
            glViewport(0, 0, camera.screenWidth, camera.screenHeight)
            glClearColor(3.0f, 3.0f, 3.0f, 2.0f)
            glClearDepth(1.0)
            glClearStencil(0)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT)

            swapFbo.fbo.bind()
            glClearColor(3.0f, 3.0f, 3.0f, 2.0f)
            glClearDepth(1.0)
            glClearStencil(0)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT)
//            glClearNamedFramebufferf(mainFbo.fbo.id, GL_COLOR, 0, 3.0f, 3.0f, 3.0f, 2.0f)
//            glClearNamedFramebufferfi(mainFbo.fbo.id, GL_DEPTH_STENCIL, 0, 1.0f, 0)
//
//            glClearNamedFramebufferf(swapFbo.fbo.id, GL_COLOR, 0, 0.0f, 0.0f, 0.0f, 0.0f)
//            glClearNamedFramebufferfi(swapFbo.fbo.id, GL_DEPTH_STENCIL, 0, 1.0f, 0)

            if (mainFbo.fbo.sizeBit != combined.sizeBit) {
                combined.apply {
                    destroyFbo()
                    attachNonLayered(mainFbo.colorTex, GL_COLOR_ATTACHMENT0)
                    attachNonLayered(swapFbo.colorTex, GL_COLOR_ATTACHMENT1)
                    attachNonLayered(mainFbo.depthStencilTex, GL_DEPTH_STENCIL_ATTACHMENT)
                    setDrawBufferAll()
                }
            }
        }
    }

    inner class MainFbo(private val name: String) : IGLObjContainer by IGLObjContainer.Impl() {
        var width = -1
        var height = -1
        val colorTex = register(TextureObject.Texture2D())
        val depthStencilTex = register(TextureObject.Texture2D())
        val fbo = register(FramebufferObject())

        fun update() {
            val screenWidth = camera.screenWidth
            val screenHeight = camera.screenHeight
            if (width != screenWidth || height != screenHeight) {
                fbo.destroy()

                colorTex.apply {
                    label("MainFBO.$name.Color")
                    allocate(1, ImageFormat.R16_G16_B16_A16_F, screenWidth, screenHeight)
                    linearClampEdge()
                }
                depthStencilTex.apply {
                    label("MainFBO.$name.Depth")
                    allocate(1, ImageFormat.Depth32FStencil8, screenWidth, screenHeight)
                    linearClampEdge()
                }

                fbo.apply {
                    clearAttachments()
                    attachNonLayered(colorTex, GL_COLOR_ATTACHMENT0) // Regular color
                    attachNonLayered(depthStencilTex, GL_DEPTH_STENCIL_ATTACHMENT) // Depth & Stencil
                    check()
                    label("MainFBO.$name.FBO")
                }

                width = screenWidth
                height = screenHeight
            }
        }
    }
}
