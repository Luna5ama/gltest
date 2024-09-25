package dev.luna5ama.gltest.terrain

/*
import dev.luna5ama.gltest.Frustum
import dev.fastmc.common.*
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.*
import dev.luna5ama.glwrapper.enums.*
import dev.luna5ama.glwrapper.objects.*
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.asMutable
import dev.luna5ama.kmogus.copyToMutableArr
import dev.luna5ama.kmogus.usePtr
import org.joml.*
import org.lwjgl.glfw.GLFW.glfwGetTime
import kotlin.math.*

class CascadedShadowMap(
    private val scene: TerrainScene,
    private val texSize: Int,
    private val logFactor: Double,
) : IGLObjContainer by IGLObjContainer.Impl() {
    private val CSM_SPLITS = 4

    private val csmGen = register(
        TerrainDrawShader(
            ShaderSource.Vert("terrain/shadow/CsmGen.vert.glsl"),
            ShaderSource.Frag("terrain/shadow/CsmGen.frag.glsl")
        )
    )

    private val csmGenCutout = register(
        TerrainDrawShader(
            ShaderSource.Vert("terrain/shadow/CsmGen.vert.glsl"),
            ShaderSource.Frag("terrain/shadow/CsmGen.frag.glsl") {
                define("CUTOUT")
            }
        )
    )

    val texture = register(TextureObject.Texture3D(GL_TEXTURE_2D_ARRAY))
    private val fbo = register(FramebufferObject())

    init {
        texture.allocate(1, GL_DEPTH_COMPONENT32F, texSize, texSize, CSM_SPLITS)

        texture.setFilter(GL_LINEAR, GL_LINEAR)
            .setWrap(GL_CLAMP_TO_BORDER, GL_CLAMP_TO_BORDER, GL_CLAMP_TO_BORDER)
            .setBorderColor(0.0f, 0.0f, 0.0f, 0.0f)
            .setCompare(GL_COMPARE_REF_TO_TEXTURE, GL_LESS)
    }

    val ubo = register(
        BufferObject.Immutable().allocate(
            (64L * CSM_SPLITS) +
                    (16L * CSM_SPLITS) * 3L +
                    16L * 2L,
            GL_DYNAMIC_STORAGE_BIT
        )
    )

//    val lightDir = Vector3f(1.0f, 0.4f, 0.3f).normalize()
//    val lightDir = Vector3f(0.0f, 0.4f, 0.3f).normalize()
//    val lightDir = Vector3f(0.1f, 1.0f, 0.9f).normalize()

    val lightDir = Vector3f(0.5f, 1.0f, 0.5f).normalize()

    private val layers = Array(CSM_SPLITS) { Layer(it) }

    private val c = sqrt(logFactor)

    private var updateIndex = 0


    suspend fun update() {
//        lightDir.set(1.0f, 0.4f, 0.3f).normalize()
        lightDir.set(1.0f, 1.0f, 0.3f).normalize()

//        val time = glfwGetTime() / 15.0
//
//        lightDir.set(cos(time), sin(time), sin(time) * 0.5).normalize()

        updateMap()
        updateOffset()
    }

    private suspend fun updateMap() {
        layers.forEach {
            it.updateSplitFrustum()
        }

        for (i in 1..CSM_SPLITS) {
            updateIndex = (updateIndex + 1) % CSM_SPLITS
            if (layers[updateIndex].isOutside()) break
            if (i == CSM_SPLITS) return
        }

        val layerToUpdate = layers[updateIndex]

        layerToUpdate.setupCamera()
        fbo.attachLayer(texture, GL_DEPTH_ATTACHMENT, layerToUpdate.index)
        fbo.setDrawBufferAll()
        fbo.check()
        glClearNamedFramebufferf(fbo.id, GL_DEPTH, 0, 1.0f)

        updateUBO()

//        val frustumCullMain = FrustumIntersection(scene.camera.combined, false)
        val frustumCull = FrustumIntersection(layerToUpdate.matrix, false)

        val intCamPosX = scene.camera.pos.x.floorToInt()
        val intCamPosY = scene.camera.pos.y.floorToInt()
        val intCamPosZ = scene.camera.pos.z.floorToInt()

        scene.setupFrustumCull("CSM", 0..1) {
            distanceSq(
                MathUtil.clamp(intCamPosX, it.minX, it.maxX),
                MathUtil.clamp(intCamPosY, it.minY, it.maxY),
                MathUtil.clamp(intCamPosZ, it.minZ, it.maxZ),
                intCamPosX,
                intCamPosY,
                intCamPosZ
            ) < (128).sq || it.frustumCull(frustumCull, scene.camera.pos)
        }

        scene.textures.blocks.bindTextureUnit(0)
        fbo.bind()

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)
        glViewport(0, 0, texSize, texSize)

        glDisable(GL_CULL_FACE)
        glCullFace(GL_BACK)

        csmGen.bind()
        csmGen.setOriginOffset(
            scene.originX - scene.camera.pos.x,
            scene.originY - scene.camera.pos.y,
            scene.originZ - scene.camera.pos.z
        )
        csmGen.uniform1i("uUpdateLayer", layerToUpdate.index)
        csmGen.bindBuffer(GL_UNIFORM_BUFFER, ubo, "ShadowMap")

        scene.terrainMesh.layers[0].draw("CSM")

//        if (layerToUpdate.index <= 0) {
            csmGenCutout.bind()
            csmGenCutout.setOriginOffset(
                scene.originX - scene.camera.pos.x,
                scene.originY - scene.camera.pos.y,
                scene.originZ - scene.camera.pos.z
            )
            csmGenCutout.uniform1i("uUpdateLayer", layerToUpdate.index)
            csmGenCutout.bindBuffer(GL_UNIFORM_BUFFER, ubo, "ShadowMap")
//        }

        scene.terrainMesh.layers[1].draw("CSM")
    }

    private fun updateUBO() {
        MemoryStack {
            val arr = calloc(ubo.size).asMutable()

            layers.forEach {
                it.matrix.copyToMutableArr(arr)
            }

            layers.forEach {
                arr.usePtr {
                    setFloatInc(it.bounds.x)
                        .setFloatInc(it.bounds.y)
                        .setFloatInc(1.0f / it.bounds.x)
                        .setFloatInc(1.0f / it.bounds.y)
                }
            }

            layers.forEach {
                arr.usePtr {
                    setFloatInc(it.nearZ)
                        .setFloatInc(it.farZ)
                        .setFloatInc(1.0f / it.nearZ)
                        .setFloatInc(1.0f / it.farZ)
                }
            }

            layers.forEach {
                arr.usePtr {
                    setFloatInc((scene.camera.pos.x - it.lastUpdateCamPos.x).toFloat())
                        .setFloatInc((scene.camera.pos.y - it.lastUpdateCamPos.y).toFloat())
                        .setFloatInc((scene.camera.pos.z - it.lastUpdateCamPos.z).toFloat())
                        .setFloatInc(0.0f)
                }
            }

            val a = scene.camera.nearPlane
            val b = scene.camera.farPlane

            val d = b * (1.0 - c) + a * c

            val u = c / d
            val v = (b * (1.0 - c)) / d
            val w = CSM_SPLITS * log2(2.0) / log2(b / d)

            arr.usePtr {
                setFloatInc(lightDir.x)
                    .setFloatInc(lightDir.y)
                    .setFloatInc(lightDir.z)
                    .plus(4L)
                    .setFloatInc(u.toFloat())
                    .setFloatInc(v.toFloat())
                    .setFloatInc(w.toFloat())
            }

            ubo.invalidate()
            ubo.upload(arr.basePtr)
        }
    }

    private fun updateOffset() {
        MemoryStack {
            val arr = malloc(16L * CSM_SPLITS).asMutable()
            layers.forEach {
                arr.usePtr {
                    setFloatInc((scene.camera.pos.x - it.lastUpdateCamPos.x).toFloat())
                        .setFloatInc((scene.camera.pos.y - it.lastUpdateCamPos.y).toFloat())
                        .setFloatInc((scene.camera.pos.z - it.lastUpdateCamPos.z).toFloat())
                        .setFloatInc(0.0f)
                }
            }

            val offset = (64L * CSM_SPLITS) + (16L * CSM_SPLITS) * 2L
            ubo.invalidate(offset, arr.baseLen)
            ubo.upload(offset, arr.baseLen, arr.basePtr)
        }
    }

    private inner class Layer(val index: Int) {
        val matrix = Matrix4f()
        val bounds = Vector2f(128.0f, 128.0f)

        var nearZ = 0.0f
        var farZ = 0.0f

        val lastUpdateCamPos = Vector3d()

        private val updateTimer = TickTimer()
        private val splitMatrix = Matrix4f()
        private val frustum = Frustum(scene.camera.invCombined)
        private val lastSizeResetPos = Vector3d()

        init {
            updateTimer.reset(-69420L)
        }

        fun updateSplitFrustum() {
            val a = scene.camera.nearPlane.toDouble()
            val b = scene.camera.farPlane.toDouble()
            val x = index.toDouble()

            nearZ = calc(x, a, b, c, CSM_SPLITS).toFloat()
            farZ = calc(x + 1.0, a, b, c, CSM_SPLITS).toFloat()

            splitMatrix.identity()
                .perspective(scene.camera.fov.toRadians(), scene.camera.aspectRatio, nearZ, farZ)
                .mul(scene.camera.noJitterModelView)
                .invert()

            frustum.update(splitMatrix)
        }

        fun setupCamera() {
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

            for (vec in frustum.planes) {
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

            val prevWidth = bounds.x
            val prevHeight = bounds.y

            var width = (maxLightX - minLightX)
            var height = (maxLightY - minLightY)

            val movedDist = lastSizeResetPos.distance(scene.camera.pos)

            width = if (prevWidth - width in 0.0f..width * 2.0f && movedDist < 8.0) {
                prevWidth
            } else {
                lastSizeResetPos.set(scene.camera.pos)
                ceil(width * 1.25f)
            }

            height = if (prevHeight - height in 0.0f..height * 2.0f && movedDist < 8.0) {
                prevHeight
            } else {
                lastSizeResetPos.set(scene.camera.pos)
                ceil(height * 1.25f)
            }

            minLightX = (minLightX + maxLightX) / 2.0f - width / 2.0f
            minLightY = (minLightY + maxLightY) / 2.0f - height / 2.0f

            if (width == prevWidth) {
                val xUnitPerTexel = prevWidth / texSize
                minLightX = floor(minLightX / xUnitPerTexel) * xUnitPerTexel
            }

            if (height == prevHeight) {
                val yUnitPerTexel = prevHeight / texSize
                minLightY = floor(minLightY / yUnitPerTexel) * yUnitPerTexel
            }

            maxLightX = minLightX + width
            maxLightY = minLightY + height

            matrix.identity()
            matrix.scale(1.0f, 1.0f, 0.9f)
            matrix.ortho(minLightX, maxLightX, minLightY, maxLightY, -maxLightZ, -minLightZ)
            matrix.mul(modelView)

            bounds.set(width, height)

            lastUpdateCamPos.set(scene.camera.pos)
            updateTimer.reset()
        }

        fun isOutside(): Boolean {
            if (!updateTimer.tick(50L)) return false

            val temp = Vector4f()
            val offsetX = (scene.camera.pos.x - lastUpdateCamPos.x).toFloat()
            val offsetY = (scene.camera.pos.y - lastUpdateCamPos.y).toFloat()
            val offsetZ = (scene.camera.pos.z - lastUpdateCamPos.z).toFloat()

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

            for (vec in frustum.planes) {
                temp.set(vec).add(offsetX, offsetY, offsetZ, 0.0f)
                temp.x = MathUtil.clamp(temp.x, minSceneX, maxSceneX)
                temp.y = MathUtil.clamp(temp.y, minSceneY, maxSceneY)
                temp.z = MathUtil.clamp(temp.z, minSceneZ, maxSceneZ)

                matrix.transform(temp)
                if (temp.w <= 0.0f) return true
                if (abs(temp.x) > 1.0f) return true
                if (abs(temp.y) > 1.0f) return true
                if (abs(temp.z) > 1.0f) return true
            }

            return false
        }

        private inner class UpdateTask {
            val matrix = this@Layer.matrix
            val bounds = this@Layer.bounds

            var nearZ = 0.0f
            var farZ = 0.0f
        }
    }

    companion object {
        fun calc(x: Double, a: Double, b: Double, c: Double, splits: Int): Double {
            return (b - a - (b / c)) * (1.0 - (b / ((a - b) * c + b)).pow(x / splits)) + a
        }

        fun calcInv(z: Double, a: Double, b: Double, c: Double, splits: Int): Double {
            val d = b * (1.0 - c) + a * c

            val u = c / d
            val v = (b * (1.0 - c)) / d
            val w = splits * log2(2.0) / log2(b / d)

            return log2(z * u + v) * w
        }
    }
}*/
