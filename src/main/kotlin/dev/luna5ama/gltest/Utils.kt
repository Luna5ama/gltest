@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.luna5ama.gltest

import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.*
import dev.luna5ama.glwrapper.enums.*
import dev.luna5ama.glwrapper.objects.*
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.glwrapper.objects.setFilter
import dev.luna5ama.glwrapper.objects.setWrap
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.byteLength
import dev.luna5ama.kmogus.memcpy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.joml.Vector2d
import org.joml.Vector2f
import org.joml.Vector3d
import org.joml.Vector3f
import org.joml.Vector4d
import org.joml.Vector4f
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.io.File
import javax.imageio.ImageIO


fun TextureObject.linearClampEdge() {
    setFilter(GL_LINEAR, GL_LINEAR)
    setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
}

fun TextureObject.nearestClampEdge() {
    setFilter(GL_NEAREST, GL_NEAREST)
    setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
}

fun Boolean.asByte() = if (this) 1.toByte() else 0.toByte()

suspend fun readImage(file: File): BufferedImage {
    return withContext(Dispatchers.IO) { ImageIO.read(file) }
}

fun BufferedImage.toRGBByteArray(): ByteArray {
    return toRBGByteArray(0, 0, width, height)
}

fun BufferedImage.toRBGByteArray(srcX: Int, srcY: Int, width: Int, height: Int): ByteArray {
    val result = ByteArray(width * height * 3)
    val data = createDataArray()
    var i = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val dataElement = raster.getDataElements(srcX + x, srcY + y, data)
            result[i++] = colorModel.getRed(dataElement).toByte()
            result[i++] = colorModel.getGreen(dataElement).toByte()
            result[i++] = colorModel.getBlue(dataElement).toByte()
        }
    }
    return result
}

fun BufferedImage.toRGBAByteArray(): UByteArray {
    return toRBGAByteArray(0, 0, width, height)
}

fun BufferedImage.toRBGAByteArray(srcX: Int, srcY: Int, width: Int, height: Int): UByteArray {
    val result = UByteArray(width * height * 4)
    val data = createDataArray()
    var i = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val dataElement = raster.getDataElements(srcX + x, srcY + y, data)
            result[i++] = colorModel.getRed(dataElement).toUByte()
            result[i++] = colorModel.getGreen(dataElement).toUByte()
            result[i++] = colorModel.getBlue(dataElement).toUByte()
            result[i++] = colorModel.getAlpha(dataElement).toUByte()
        }
    }
    return result
}

fun BufferedImage.createDataArray(): Any {
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

fun ShaderProgram.uniform2f(name: String, value: Vector2f) {
    uniform2f(name, value.x, value.y)
}

fun ShaderProgram.uniform3f(name: String, value: Vector3f) {
    uniform3f(name, value.x, value.y, value.z)
}

fun ShaderProgram.uniform4f(name: String, value: Vector4f) {
    uniform4f(name, value.x, value.y, value.z, value.w)
}

fun ShaderProgram.uniform2f(name: String, value: Vector2d) {
    uniform2f(name, value.x.toFloat(), value.y.toFloat())
}

fun ShaderProgram.uniform3f(name: String, value: Vector3d) {
    uniform3f(name, value.x.toFloat(), value.y.toFloat(), value.z.toFloat())
}

fun ShaderProgram.uniform4f(name: String, value: Vector4d) {
    uniform4f(name, value.x.toFloat(), value.y.toFloat(), value.z.toFloat(), value.w.toFloat())
}

inline fun glDebugGroup(name: String, block: () -> Unit) {
//    glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, name)
    block()
//    glPopDebugGroup()
}