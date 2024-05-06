package dev.luna5ama.gltest.terrain

import dev.fastmc.common.MathUtil
import dev.luna5ama.gltest.AbstractScene
import dev.luna5ama.gltest.linearClampEdge
import dev.luna5ama.gltest.readImage
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.*
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.asByteBuffer
import dev.luna5ama.kmogus.ensureCapacity
import kotlinx.coroutines.*
import net.jpountz.lz4.LZ4Factory
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.io.File
import java.io.FileNotFoundException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.*

class Textures(val scene: AbstractScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val lightMapTex = register(TextureObject.Texture2D())
    private val blockTex = register(TextureObject.Texture2D())
    private val blockNormalTex = register(TextureObject.Texture2D())
    private val blockSpecularTex = register(TextureObject.Texture2D())
    private val noiseTextureTex = register(TextureObject.Texture2D())

    val lightMap = ShaderBindingSpecs.Sampler("usam_lightMap", lightMapTex)
    val blockAlbedo = ShaderBindingSpecs.Sampler("usam_blockAlbedoAtlas", blockTex)
    val blockNormal = ShaderBindingSpecs.Sampler("usam_blockNormalAtlas", blockNormalTex)
    val blockSpecular = ShaderBindingSpecs.Sampler("usam_blockSpecularAtlas", blockSpecularTex)
    val noiseTexture = ShaderBindingSpecs.Sampler("usam_noise4", noiseTextureTex)

    init {
        runBlocking {
            launch { initLightMap(File("tex")) }
            launch { initBlocks(File("tex")) }
//            launch { initBlockNormal(File("tex")) }
//            launch { initBlockSpecular(File("tex")) }
//            launch { initNoiseTexture() }
        }
    }

    private suspend fun initLightMap(dir: File) {
        val image = readImage(File(dir, "lightmap.png"))
        Arr.malloc(0L).use {
            lightMapTex.apply {
                allocate(1, ImageFormat.R8_G8_B8_UN, image.width, image.height)
                upload(0, 0, 0, image.width, image.height, GL_RGB, GL_UNSIGNED_BYTE, image.getRGB(it).ptr)
            }
        }

        lightMapTex.linearClampEdge()
    }

    private suspend fun initBlocks(dir: File) {
        Arr.malloc(0).use { buffer ->
            val image = readImage(File(dir, "blocks_0.png"))
            val data = image.getRGBA(buffer)
            blockTex.allocate(5, ImageFormat.R8_G8_B8_A8_UN, image.width, image.height)

            blockTex.setMipLevels(0, 4)
            blockTex.setMipLod(0.0f, 4.0f)
            blockTex.setFilter(GL_NEAREST_MIPMAP_LINEAR, GL_NEAREST)
            blockTex.setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)

            // WTF Intel
            blockTex.setAnisotropy(16.0f)

            generateMipmap(data, image.width, image.height, 4).forEachIndexed { index, arr ->
                blockTex.upload(
                    index,
                    0,
                    0,
                    image.width shr index,
                    image.height shr index,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    arr.ptr
                )
            }
        }
    }

    fun generateMipmap(input: Arr, width: Int, height: Int, levels: Int) : List<Arr> {
        var last = input
        val result = mutableListOf(last)
        var lastWidth = width
        repeat(levels) { level ->
            val mipWidth = width shr (level + 1)
            val mipHeight = height shr (level + 1)
            val mipSize = mipWidth * mipHeight * 4L
            val mipData = Arr.malloc(mipSize)
            val lastPtr = last.ptr

            for (y in 0 until mipHeight) {
                for (x in 0 until mipWidth) {
                    val lastX = x shl 1
                    val lastY = y shl 1

                    var sumR = 0
                    var sumG = 0
                    var sumB = 0
                    var count = 0

                    for (dy in 0 until 2) {
                        for (dx in 0 until 2) {
                            val lastX2 = lastX + dx
                            val lastY2 = lastY + dy
                            val index = (lastY2 * lastWidth + lastX2) * 4L
                            if ((lastPtr.getByte(index + 3).toInt() and 0xFF) <= 127) continue
                            sumR += lastPtr.getByte(index + 0).toInt() and 0xFF
                            sumG += lastPtr.getByte(index + 1).toInt() and 0xFF
                            sumB += lastPtr.getByte(index + 2).toInt() and 0xFF
                            count++
                        }
                    }

                    val writeIndex = (y * mipWidth + x) * 4L
                    if (count == 0) {
                        mipData.ptr.setByte(writeIndex + 0, 0)
                        mipData.ptr.setByte(writeIndex + 1, 0)
                        mipData.ptr.setByte(writeIndex + 2, 0)
                        mipData.ptr.setByte(writeIndex + 3, 0)
                    } else {
                        mipData.ptr.setByte(writeIndex + 0, MathUtil.clamp(sumR / count, 0, 255).toByte())
                        mipData.ptr.setByte(writeIndex + 1, MathUtil.clamp(sumG / count, 0, 255).toByte())
                        mipData.ptr.setByte(writeIndex + 2, MathUtil.clamp(sumB / count, 0, 255).toByte())
                        mipData.ptr.setByte(writeIndex + 3, 255.toByte())
                    }
                }
            }

            last = mipData
            lastWidth = mipWidth
            result.add(mipData)
        }

        return result
    }

    private suspend fun initBlockNormal(dir: File) {
        val image = readImageData(File(dir, "blocks_0_n.png"), 4)
        blockNormalTex.allocate(9, ImageFormat.R8_G8_B8_A8_UN, image.width, image.height)
        blockNormalTex.upload(
            0,
            0,
            0,
            image.width,
            image.height,
            image.format,
            image.type,
            image.data.ptr
        )
        image.free()

        blockNormalTex.setMipLevels(0, 8)
        blockNormalTex.setMipLod(0.0f, 8.0f)
        blockNormalTex.setFilter(GL_NEAREST_MIPMAP_LINEAR, GL_NEAREST)
        blockNormalTex.setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
        blockNormalTex.generateMipmap()
    }

    private suspend fun initBlockSpecular(dir: File) {
        val image = readImageData(File(dir, "blocks_0_s.png"), 4, false)
        blockSpecularTex.allocate(9, ImageFormat.R8_G8_B8_A8_UN, image.width, image.height)
        blockSpecularTex.upload(
            0,
            0,
            0,
            image.width,
            image.height,
            image.format,
            image.type,
            image.data.ptr
        )
        image.free()

        blockSpecularTex.setMipLevels(0, 8)
        blockSpecularTex.setMipLod(0.0f, 8.0f)
        blockSpecularTex.setFilter(GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST)
        blockSpecularTex.setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
        blockSpecularTex.generateMipmap()
    }

    private suspend fun initNoiseTexture() {
//        val image = readImageData(File("tex/blue_noise_placement_1024_0.png"), 4)
//        noiseTexture.allocate(8, ImageFormat.R8_G8_B8_A8_UN, image.width, image.height)
//        noiseTexture.upload(
//            0,
//            0,
//            0,
//            image.width,
//            image.height,
//            image.format,
//            image.type,
//            image.data.ptr
//        )
//        image.free()


        val size = 1024
        val noise = Arr.malloc(size * size * 4L * 2L)

        withContext(Dispatchers.Default) {
            val randomR = Random(23377957018841)
            val randomG = Random(39374515689937)
            val randomB = Random(127619713069)
            val randomA = Random(93643726565097)

            var ptr = noise.ptr

            repeat(size * size) {
                ptr = ptr.setShortInc(randomR.nextInt(65536).toShort())
                    .setShortInc(randomG.nextInt(65536).toShort())
                    .setShortInc(randomB.nextInt(65536).toShort())
                    .setShortInc(randomA.nextInt(65536).toShort())
            }
        }

        noiseTextureTex.allocate(8, ImageFormat.R16_G16_B16_A16_UN, size, size)
        noiseTextureTex.upload(0, 0, 0, size, size, GL_RGBA, GL_UNSIGNED_SHORT, noise.ptr)

        noiseTextureTex.setMipLevels(0, 7)
        noiseTextureTex.setMipLod(0.0f, 7.0f)

        noiseTextureTex.setFilter(GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST)
        noiseTextureTex.setWrap(GL_REPEAT, GL_REPEAT)

        noiseTextureTex.generateMipmap()
    }

    private suspend fun readImageData(file: File, channels: Int, cache: Boolean = true): NativeImage {
        return withContext(Dispatchers.Default) {
            val dir = file.parentFile
            val name = file.nameWithoutExtension
            val nativeImageFile = File(dir, "$name.dat.lz4")

            try {
                if (!cache) throw FileNotFoundException()
                NativeImage.read(nativeImageFile)
            } catch (e: FileNotFoundException) {
                val bufferedImage = readImage(file)
                val data = when (channels) {
                    1 -> bufferedImage.getR(Arr.malloc(bufferedImage.width * bufferedImage.height * 1L))
                    2 -> bufferedImage.getRG(Arr.malloc(bufferedImage.width * bufferedImage.height * 2L))
                    3 -> bufferedImage.getRGB(Arr.malloc(bufferedImage.width * bufferedImage.height * 3L))
                    4 -> bufferedImage.getRGBA(Arr.malloc(bufferedImage.width * bufferedImage.height * 4L))
                    else -> throw IllegalArgumentException("Invalid number of channels: $channels")
                }
                val format = when (channels) {
                    1 -> GL_RED
                    2 -> GL_RG
                    3 -> GL_RGB
                    4 -> GL_RGBA
                    else -> throw IllegalArgumentException("Invalid number of channels: $channels")
                }
                val nativeImage = NativeImage(data, bufferedImage.width, bufferedImage.height, format, GL_UNSIGNED_BYTE)
                nativeImage.save(nativeImageFile)
                nativeImage
            }
        }
    }

    private class NativeImage(val data: Arr, val width: Int, val height: Int, val format: Int, val type: Int) {
        suspend fun save(file: File) {
            val lz4Factory = LZ4Factory.fastestInstance()
            val compressor = lz4Factory.fastCompressor()
            val tempArr = Arr.malloc(20L + compressor.maxCompressedLength(data.len.toInt()))
            val tempBuffer = tempArr.ptr.asByteBuffer(tempArr.len.toInt())
            val compressedSize = withContext(Dispatchers.Default) {
                tempArr.ptr.setIntInc(data.len.toInt())
                    .setIntInc(width)
                    .setIntInc(height)
                    .setIntInc(format)
                    .setIntInc(type)

                val srcBuffer = data.ptr.asByteBuffer(data.len.toInt())
                compressor.compress(
                    srcBuffer, 0, data.len.toInt(),
                    tempBuffer, 20, (tempArr.len - 20L).toInt()
                )
            }

            withContext(Dispatchers.IO) {
                FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
                    tempBuffer.position(0)
                    tempBuffer.limit(20 + compressedSize)
                    channel.write(tempBuffer)
                }
            }

            tempArr.free()
        }

        fun free() {
            data.free()
        }

        companion object {
            suspend fun read(file: File): NativeImage {
                if (!file.exists()) throw FileNotFoundException(file.absolutePath)

                val lz4Factory = LZ4Factory.fastestInstance()
                val decompressor = lz4Factory.fastDecompressor()

                val tempArr = Arr.malloc(file.length())
                val tempBuffer = tempArr.ptr.asByteBuffer(tempArr.len.toInt())

                withContext(Dispatchers.IO) {
                    FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                        channel.read(tempBuffer)
                    }
                }

                tempBuffer.clear()
                val uncompLen = tempBuffer.getInt()
                val width = tempBuffer.getInt()
                val height = tempBuffer.getInt()
                val format = tempBuffer.getInt()
                val type = tempBuffer.getInt()
                tempBuffer.clear()

                val imageData = Arr.malloc(uncompLen.toLong())

                withContext(Dispatchers.Default) {
                    decompressor.decompress(
                        tempBuffer, 20,
                        imageData.ptr.asByteBuffer(uncompLen), 0, uncompLen
                    )
                }

                tempArr.free()

                return NativeImage(imageData, width, height, format, type)
            }
        }
    }
}

suspend fun BufferedImage.getRGBA(buffer: Arr): Arr {
    buffer.ensureCapacity(width * height * 4L, false)

    coroutineScope {
        var ptrM = buffer.ptr
        for (y in (0 until height).reversed()) {
            val ptrC = ptrM
            ptrM += width * 4L
            launch(Dispatchers.Default) {
                var ptr = ptrC

                val data = createDataArray()

                for (x in 0 until width) {
                    val dataElement = raster.getDataElements(x, y, data)

                    ptr = ptr.setByteInc(colorModel.getRed(dataElement).toByte())
                        .setByteInc(colorModel.getGreen(dataElement).toByte())
                        .setByteInc(colorModel.getBlue(dataElement).toByte())
                        .setByteInc(colorModel.getAlpha(dataElement).toByte())
                }
            }
        }
    }

    return buffer
}

suspend fun BufferedImage.getRG(buffer: Arr): Arr {
    buffer.ensureCapacity(width * height * 2L, false)

    coroutineScope {
        var ptrM = buffer.ptr
        for (y in (0 until height).reversed()) {
            val ptrC = ptrM
            ptrM += width * 2L
            launch(Dispatchers.Default) {
                val data = createDataArray()
                var ptr = ptrC
                for (x in 0 until width) {
                    val dataElement = raster.getDataElements(x, y, data)
                    ptr = ptr.setByteInc(colorModel.getRed(dataElement).toByte())
                        .setByteInc(colorModel.getGreen(dataElement).toByte())
                }
            }
        }
    }

    return buffer
}

suspend fun BufferedImage.getRGB(buffer: Arr): Arr {
    buffer.ensureCapacity(width * height * 3L, false)

    coroutineScope {
        var ptrM = buffer.ptr
        for (y in (0 until height).reversed()) {
            val ptrC = ptrM
            ptrM += width * 3L
            launch(Dispatchers.Default) {
                val data = createDataArray()
                var ptr = ptrC
                for (x in 0 until width) {
                    val dataElement = raster.getDataElements(x, y, data)
                    ptr = ptr.setByteInc(colorModel.getRed(dataElement).toByte())
                        .setByteInc(colorModel.getGreen(dataElement).toByte())
                        .setByteInc(colorModel.getBlue(dataElement).toByte())
                }
            }
        }
    }

    return buffer
}

suspend fun BufferedImage.getR(buffer: Arr): Arr {
    buffer.ensureCapacity(width * height * 1L, false)

    coroutineScope {
        var ptrM = buffer.ptr
        for (y in (0 until height).reversed()) {
            val ptrC = ptrM
            ptrM += width * 1L
            launch(Dispatchers.Default) {
                val data = createDataArray()
                var ptr = ptrC
                for (x in 0 until width) {
                    val dataElement = raster.getDataElements(x, y, data)
                    ptr = ptr.setByteInc(colorModel.getRed(dataElement).toByte())
                }
            }
        }
    }

    return buffer
}

private fun BufferedImage.createDataArray(): Any {
    val numBands = raster.numBands
    return when (val dataType = raster.dataBuffer.dataType) {
        DataBuffer.TYPE_BYTE -> ByteArray(numBands)
        DataBuffer.TYPE_USHORT -> ShortArray(numBands)
        DataBuffer.TYPE_INT -> IntArray(numBands)
        DataBuffer.TYPE_FLOAT -> FloatArray(numBands)
        DataBuffer.TYPE_DOUBLE -> DoubleArray(numBands)
        else -> throw IllegalArgumentException("Unknown data buffer type: $dataType")
    }
}