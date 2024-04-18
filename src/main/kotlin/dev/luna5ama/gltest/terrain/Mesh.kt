package dev.luna5ama.gltest.terrain

import dev.fastmc.common.floorToInt
import dev.fastmc.common.sq
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.structs.DrawElementsIndirectCommand
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.buildAttribute
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.enums.GLDataType
import dev.luna5ama.glwrapper.enums.ShaderStage
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.VertexArrayObject
import dev.luna5ama.kmogus.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.joml.FrustumIntersection
import org.joml.Vector3d
import org.joml.Vector3i
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.min

class TerrainMesh(private val scene: TerrainScene, dir: File) : IGLObjContainer by IGLObjContainer.Impl() {
    val chunkOffsets = Array(TOTAL_CHUNKS) { Vector3i() }.apply {
        var i = 0
        for (z in 0 until SIZE_XZ) {
            for (x in 0 until SIZE_XZ) {
                for (y in 0 until SIZE_Y) {
                    this[i++].set(x, y, z)
                }
            }
        }
    }
    val chunkOffsetBuffer = register(BufferObject.Immutable().apply {
        MemoryStack {
            val arr = malloc(TOTAL_CHUNKS * 4L)
            var ptr = arr.ptr

            for (i in chunkOffsets.indices) {
                ptr = ptr.setByteInc(chunkOffsets[i].x.toByte())
                    .setByteInc(chunkOffsets[i].y.toByte())
                    .setByteInc(chunkOffsets[i].z.toByte())
                    .setByteInc(0)
            }

            allocate(arr.len, arr.ptr, 0)
        }
    })
    val layers = Array(4) { register(Layer(dir, it)) }

    val cullBoxBuffer = register(BufferObject.Immutable()).apply {
        MemoryStack {
            val arr = calloc(4 * 6 * 6)
            var ptr = arr.ptr

            // Down
            ptr = ptr.setByteInc(-1).setByteInc(-1).setByteInc(-1).plus(1)
                .setByteInc(17).setByteInc(-1).setByteInc(17).plus(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(17).plus(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(-1).plus(1)
                .setByteInc(17).setByteInc(-1).setByteInc(-1).plus(1)
                .setByteInc(17).setByteInc(-1).setByteInc(17).plus(1)

            // Up
            ptr = ptr.setByteInc(-1).setByteInc(17).setByteInc(17).plus(1)
                .setByteInc(17).setByteInc(17).setByteInc(-1).plus(1)
                .setByteInc(-1).setByteInc(17).setByteInc(-1).plus(1)
                .setByteInc(-1).setByteInc(17).setByteInc(17).plus(1)
                .setByteInc(17).setByteInc(17).setByteInc(17).plus(1)
                .setByteInc(17).setByteInc(17).setByteInc(-1).plus(1)

            // West
            ptr = ptr.setByteInc(-1).setByteInc(17).setByteInc(17).plus(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(-1).plus(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(17).plus(1)
                .setByteInc(-1).setByteInc(17).setByteInc(17).plus(1)
                .setByteInc(-1).setByteInc(17).setByteInc(-1).plus(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(-1).plus(1)

            // East
            ptr = ptr.setByteInc(17).setByteInc(17).setByteInc(-1).plus(1)
                .setByteInc(17).setByteInc(-1).setByteInc(17).plus(1)
                .setByteInc(17).setByteInc(-1).setByteInc(-1).plus(1)
                .setByteInc(17).setByteInc(17).setByteInc(-1).plus(1)
                .setByteInc(17).setByteInc(17).setByteInc(17).plus(1)
                .setByteInc(17).setByteInc(-1).setByteInc(17).plus(1)

            // North
            ptr = ptr.setByteInc(-1).setByteInc(17).setByteInc(-1).plus(1)
                .setByteInc(17).setByteInc(-1).setByteInc(-1).plus(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(-1).plus(1)
                .setByteInc(-1).setByteInc(17).setByteInc(-1).plus(1)
                .setByteInc(17).setByteInc(17).setByteInc(-1).plus(1)
                .setByteInc(17).setByteInc(-1).setByteInc(-1).plus(1)

            // South
            ptr = ptr.setByteInc(17).setByteInc(17).setByteInc(17).plus(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(17).plus(1)
                .setByteInc(17).setByteInc(-1).setByteInc(17).plus(1)
                .setByteInc(17).setByteInc(17).setByteInc(17).plus(1)
                .setByteInc(-1).setByteInc(17).setByteInc(17).plus(1)
                .setByteInc(-1).setByteInc(-1).setByteInc(17).plus(1)

            allocate(4 * 6 * 6, arr.ptr, 0)
        }
    }

    inner class Layer(dir: File, val index: Int) : IGLObjContainer by IGLObjContainer.Impl() {
        val vao = register(VertexArrayObject()).apply {
            label("TerrainMesh.Layer#$index.VAO")
        }
        val vbo = register(BufferObject.Immutable()).apply {
            label("TerrainMesh.Layer#$index.VBO")
        }
        val ebo = register(BufferObject.Immutable()).apply {
            label("TerrainMesh.Layer#$index.EBO")
        }

        var minChunkX = Int.MAX_VALUE; private set
        var maxChunkX = Int.MIN_VALUE; private set

        var minChunkY = Int.MAX_VALUE; private set
        var maxChunkY = Int.MIN_VALUE; private set

        var minChunkZ = Int.MAX_VALUE; private set
        var maxChunkZ = Int.MIN_VALUE; private set

        val chunkInfos = mutableListOf<ChunkInfo>()
        val rawIndirectClient = Arr.malloc(sizeof(DrawElementsIndirectCommand))
        val rawIndirectBuffer = BufferObject.Immutable()

        private val drawViewNormal = mutableMapOf<String, DrawViewNormal>()
        private val drawViewOC = mutableMapOf<String, DrawViewOC>()

        init {
            runBlocking {
                readMesh(dir, index)

                vao.attachVbo(vbo, VERTEX_ATTRIBUTE_MAIN)
                vao.attachVbo(chunkOffsetBuffer, VERTEX_ATTRIBUTE_CHUNK_OFFSET)
                vao.attachEbo(ebo)
            }
        }

        private suspend fun readMesh(dir: File, layerIndex: Int) {
            readToBuffer(File(dir, "terrain_vbo_$layerIndex.bin"), vbo)

            readToBuffer(File(dir, "terrain_ebo_$layerIndex.bin"), ebo)

            val infoFile = File(dir, "terrain_info_$layerIndex.csv")
            var skipped = false

            withContext(Dispatchers.Default) {
                val arr = rawIndirectClient.asMutable()
                var indirectIndex = 0

                infoFile.forEachLine {
                    if (!skipped) {
                        skipped = true
                        return@forEachLine
                    }

                    val parts = it.split(",")

                    val chunkX = parts[2].toInt()
                    val chunkY = parts[3].toInt()
                    val chunkZ = parts[4].toInt()

                    val vertexOffset = parts[5].toLong()
                    val vertexLength = parts[6].toLong()
                    val indexOffset = parts[7].toLong()
                    val indexLength = parts[8].toLong()

                    if (vertexOffset == -1L || vertexLength == -1L || indexOffset == -1L || indexLength == -1L) {
                        return@forEachLine
                    }

                    val chunkIndex = (chunkY - MIN_CHUNK_Y) + ((chunkX + 32) + (chunkZ + 32) * SIZE_XZ) * SIZE_Y

                    val chunkInfo = ChunkInfo(chunkIndex, chunkX, chunkY, chunkZ, indirectIndex++)
                    chunkInfos.add(chunkInfo)

                    if (chunkX < minChunkX) minChunkX = chunkX
                    if (chunkX > maxChunkX) maxChunkX = chunkX

                    if (chunkY < minChunkY) minChunkY = chunkY
                    if (chunkY > maxChunkY) maxChunkY = chunkY

                    if (chunkZ < minChunkZ) minChunkZ = chunkZ
                    if (chunkZ > maxChunkZ) maxChunkZ = chunkZ

                    arr.ensureCapacity(arr.pos + 20L, false)
                    val struct = DrawElementsIndirectCommand(arr.ptr)
                    struct.count = (indexLength / 4).toInt()
                    struct.instanceCount = 1
                    struct.firstIndex = (indexOffset / 4).toInt()
                    struct.baseVertex = (vertexOffset / VERTEX_SIZE).toInt()
                    struct.baseInstance = chunkIndex

                    arr.pos += sizeof(DrawElementsIndirectCommand)
                }

                arr.flip()
                arr.realloc(arr.len, false)
            }

            if (rawIndirectClient.len != 0L) {
                rawIndirectBuffer.allocate(rawIndirectClient.len, rawIndirectClient.ptr, 0)
            }
        }

        private suspend fun readToBuffer(file: File, output: BufferObject) {
            val len = file.length()
            if (len == 0L) return
            output.allocate(len, 0)

            val copyBuffer = BufferObject.Immutable().apply {
                allocate(128 * 1024 * 1024, GL_MAP_WRITE_BIT)
            }

            @Suppress("BlockingMethodInNonBlockingContext")
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                var offset = 0L
                while (offset < len) {
                    copyBuffer.invalidate()
                    val mapped = copyBuffer.map(GL_MAP_WRITE_BIT)

                    val read = withContext(Dispatchers.IO) {
                        val buf = mapped.ptr.asByteBuffer(copyBuffer.size.toInt())
                        val read = channel.read(buf, offset)
                        if (read == -1) {
                            throw IllegalStateException("Unexpected EOF")
                        }
                        read
                    }

                    if (read == 0) break

                    copyBuffer.unmap()
                    copyBuffer.copyTo(output, 0, offset, read.toLong())

                    offset += read
                }
            }

            copyBuffer.destroy()
        }

        fun getDrawViewOrCreate(viewName: String): DrawViewNormal {
            return drawViewNormal.getOrPut(viewName) {
                DrawViewNormal(this, viewName, index)
            }
        }

        fun getDrawView(viewName: String): DrawViewNormal {
            return drawViewNormal[viewName]!!
        }

        fun getDrawViewOCOrCreate(viewName: String): DrawViewOC {
            return drawViewOC.getOrPut(viewName) {
                DrawViewOC(this, viewName, index)
            }
        }

        fun getDrawViewOC(viewName: String): DrawViewOC {
            return drawViewOC[viewName]!!
        }
    }

    sealed class DrawView(
        val layer: Layer,
        val viewName: String,
        val index: Int
    ) : IGLObjContainer by IGLObjContainer.Impl() {
        val indirect = register(BufferObject.Immutable())
        var drawCount = 0
        var sortedChunkIndices = IntArray(layer.chunkInfos.size) { it }

        protected fun debugName(stepName: String) = "Terrain \"$viewName\" #${this.index} $stepName"

        fun updateIndirect(arr: Arr) {
            if (arr.len > 0) {
                if (indirect.size >= arr.len) {
                    indirect.invalidate()
                    indirect.upload(0L, arr.len, arr.ptr)
                } else {
                    indirect.destroy()
                    indirect.allocate(arr.len, arr.ptr, GL_DYNAMIC_STORAGE_BIT)
                }
            }
        }

        abstract fun draw()
    }

    inner class DrawViewNormal(
        chunkLayer: Layer,
        viewName: String,
        index: Int
    ) : DrawView(chunkLayer, viewName, index) {
        override fun draw() {
            if (drawCount == 0) return
            glDebugGroup(debugName("draw")) {
                indirect.bind(GL_DRAW_INDIRECT_BUFFER)
                layer.vao.bind()
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0, drawCount, 0)
            }
        }
    }

    inner class DrawViewOC(
        chunkLayer: Layer,
        viewName: String,
        index: Int
    ) : DrawView(chunkLayer, viewName, index) {
        val cullBoxIndicesSrc = register(BufferObject.Immutable())
//        val cullBoxIndices = register(BufferObject.Immutable())

        val boxIndices = Arr.malloc(0L)
        val cullBoxVao = register(VertexArrayObject())

        private val visibleBuffer = register(BufferObject.Immutable())
        private val visibleBufferBinding = listOf(
            ShaderBindingSpecs.Buffer("VisibleBufferRW", visibleBuffer, BufferTarget.ShaderStorage),
            ShaderBindingSpecs.Buffer("VisibleBuffer", visibleBuffer, BufferTarget.ShaderStorage)
        )

        fun setupCullBox(): Boolean {
            if (drawCount == 0) return false
            if (boxIndices.len <= 0) return false

            glDebugGroup(debugName("cull box setup")) {
                if (cullBoxIndicesSrc.size < boxIndices.len) {
                    cullBoxIndicesSrc.destroy()
                    cullBoxIndicesSrc.allocate(boxIndices.len, boxIndices.ptr, GL_DYNAMIC_STORAGE_BIT)
//                    cullBoxIndices.destroy()
//                    cullBoxIndices.allocate(boxIndices.len, 0)

                    cullBoxVao.destroyVao()
                    cullBoxVao.attachVbo(cullBoxIndicesSrc, CULLBOX_ATTRIBUTE_CHUNK_OFFSET)
                    cullBoxVao.attachVbo(cullBoxBuffer, CULLBOX_ATTRIBUTE_MODEL)
                } else {
                    cullBoxIndicesSrc.invalidate()
                    cullBoxIndicesSrc.upload(0L, boxIndices.len, boxIndices.ptr)
                }
            }

            return true
        }

        fun cullTestDraw(
            cullBoxShader: TerrainDrawShader,
            block: ShaderBindingSpecs.Builder.() -> Unit
        ) {
            glDebugGroup(debugName("cull test draw")) {
                if (visibleBuffer.size == -1L) {
                    visibleBuffer.allocate(2L * layer.chunkInfos.size * 4L, 0)
                }

                cullBoxShader.bind()
                cullBoxShader.applyBinding {
                    buffer(scene.camera.uboBinding)
                    buffer(visibleBufferBinding)
                    block()
                }
                cullBoxShader.setOriginOffset(
                    scene.originX - scene.camera.pos.x,
                    scene.originY - scene.camera.pos.y,
                    scene.originZ - scene.camera.pos.z
                )
                cullBoxVao.bind()
                glDrawArraysInstanced(GL_TRIANGLES, 0, 6 * 6, drawCount)
            }
        }

        fun setupIndirect(visibleState: VisibleState) {
            glDebugGroup(debugName("setup indirect")) {
                val chunkInfoSize = layer.chunkInfos.size

                if (visibleBuffer.size == -1L) {
                    visibleBuffer.allocate(2L * chunkInfoSize * 4L, 0)
                }

                val outIndirectSize = 16L + chunkInfoSize * sizeof(DrawElementsIndirectCommand)
                if (indirect.size < outIndirectSize) {
                    indirect.destroy()
                    indirect.allocate(outIndirectSize, 0)
                }

                glClearNamedBufferData(indirect.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)

                val cameraChunkX = ((scene.camera.pos.x) / 16).floorToInt()
                val cameraChunkY = ((scene.camera.pos.y) / 16).floorToInt()
                val cameraChunkZ = ((scene.camera.pos.z) / 16).floorToInt()
                val cameraChunkIdx = layer.chunkInfos.indices.find {
                    val chunkInfo = layer.chunkInfos[it]
                    chunkInfo.chunkX == cameraChunkX && chunkInfo.chunkY == cameraChunkY && chunkInfo.chunkZ == cameraChunkZ
                } ?: -1

                val shader = indirectSetupCompShader
                shader.bind()
                shader.uniform1ui("uval_prevVisibleOffset", chunkInfoSize)
                shader.uniform3f("uval_originOffset",
                    (scene.originX - scene.camera.pos.x - 0.5).toFloat(),
                    (scene.originY - scene.camera.pos.y).toFloat(),
                    (scene.originZ - scene.camera.pos.z - 0.5).toFloat()
                )
                shader.applyBinding {
                    buffer(visibleBufferBinding)
                    buffer("indirectCounter", indirect, BufferTarget.AtomicCounter)
                    buffer("ChunkOffsetBuffer", chunkOffsetBuffer, BufferTarget.ShaderStorage)
                    buffer("RawIndirectBuffer", layer.rawIndirectBuffer, BufferTarget.ShaderStorage)
                    buffer(
                        "OutIndirectBuffer",
                        indirect,
                        BufferTarget.ShaderStorage,
                        16L,
                        chunkInfoSize * sizeof(DrawElementsIndirectCommand)
                    )
                    subroutine(visibleState.subroutineBinding)
                }

                glDispatchCompute((chunkInfoSize + 31) / 32, 1, 1)
            }
        }

        override fun draw() {
            if (drawCount == 0) return
            glDebugGroup(debugName("draw")) {
                indirect.bind(GL_DRAW_INDIRECT_BUFFER)
                indirect.bind(GL_PARAMETER_BUFFER)
                layer.vao.bind()
                glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_INT, 16L, 0L, layer.chunkInfos.size, 0)
            }
        }
    }

    private val indirectSetupCompShader = ShaderProgram(
        ShaderSource.Comp("/terrain/culling/IndirectSetup.comp.glsl")
    )

    class ChunkInfo(
        val chunkIndex: Int,
        val chunkX: Int,
        val chunkY: Int,
        val chunkZ: Int,
        val indirectIndex: Int
    ) {
        val minX = chunkX shl 4
        val minY = chunkY shl 4
        val minZ = chunkZ shl 4
        val maxX = minX + 16
        val maxY = minY + 16
        val maxZ = minZ + 16

        fun distanceSq(cameraPos: Vector3d): Double {
            val dx = min((cameraPos.x - minX).sq, (cameraPos.x - maxX).sq)
            val dy = min((cameraPos.y - minY).sq, (cameraPos.y - maxY).sq)
            val dz = min((cameraPos.z - minZ).sq, (cameraPos.z - maxZ).sq)
            return dx + dy + dz
        }

        fun frustumCull(cull: FrustumIntersection, cameraPos: Vector3d): Boolean {
            return cull.testAab(
                (minX - cameraPos.x).toFloat(), (minY - cameraPos.y).toFloat(), (minZ - cameraPos.z).toFloat(),
                (maxX - cameraPos.x).toFloat(), (maxY - cameraPos.y).toFloat(), (maxZ - cameraPos.z).toFloat()
            )
        }
    }

    enum class VisibleState(val subroutineBinding: ShaderBindingSpecs.Subroutine) {
        LAST_VISIBLE(ShaderBindingSpecs.Subroutine("usubroutine_VisibleStateCompute", ShaderStage.ComputeShader, "lastVisible")),
        NEWLY_VISIBLE(ShaderBindingSpecs.Subroutine("usubroutine_VisibleStateCompute", ShaderStage.ComputeShader, "newlyVisible")),
        CURRENT_VISIBLE(ShaderBindingSpecs.Subroutine("usubroutine_VisibleStateCompute", ShaderStage.ComputeShader, "currentVisible")),
    }

    companion object {
        const val VERTEX_SIZE = 20
        const val MIN_CHUNK_Y = -4
        const val SIZE_Y = 24
        const val SIZE_XZ = 127
        const val TOTAL_CHUNKS = SIZE_XZ * SIZE_XZ * SIZE_Y
        const val REGION_CHUNKS = 16 * 16 * SIZE_Y

        @JvmField
        val VERTEX_ATTRIBUTE_MAIN = buildAttribute(VERTEX_SIZE) {
            float(0, 3, GLDataType.GL_UNSIGNED_SHORT, false) // Pos
            float(1, 2, GLDataType.GL_UNSIGNED_SHORT, true) // Block texture uv
            float(2, 3, GLDataType.GL_BYTE, true) // Normal
            padding(1)
            float(3, 2, GLDataType.GL_UNSIGNED_BYTE, false) // Light map uv
            float(4, 3, GLDataType.GL_UNSIGNED_BYTE, true) // Color multiplier
            int(5, 1, GLDataType.GL_UNSIGNED_BYTE) // Attributes
        }

        @JvmField
        val VERTEX_ATTRIBUTE_CHUNK_OFFSET = buildAttribute(4, 999999999) {
            float(6, 3, GLDataType.GL_UNSIGNED_BYTE, false) // Chunk offset
        }

        @JvmField
        val CULLBOX_ATTRIBUTE_CHUNK_OFFSET = buildAttribute(8, 1) {
            int(0, 1, GLDataType.GL_UNSIGNED_INT)
            float(1, 3, GLDataType.GL_UNSIGNED_BYTE, false)
        }

        @JvmField
        val CULLBOX_ATTRIBUTE_MODEL = buildAttribute(4) {
            float(2, 3, GLDataType.GL_BYTE, false)
        }
    }
}