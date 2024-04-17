package dev.luna5ama.gltest

import org.joml.Matrix4f
import org.joml.Vector4f

class Frustum(invCombined: Matrix4f) {
    val nearTopLeft = Vector4f()
    val nearTopRight = Vector4f()
    val nearBottomLeft = Vector4f()
    val nearBottomRight = Vector4f()
    val farTopLeft = Vector4f()
    val farTopRight = Vector4f()
    val farBottomLeft = Vector4f()
    val farBottomRight = Vector4f()

    val planes = arrayOf(
        nearTopLeft,
        nearTopRight,
        nearBottomLeft,
        nearBottomRight,
        farTopLeft,
        farTopRight,
        farBottomLeft,
        farBottomRight
    )

    init {
        update(invCombined)
    }

    fun update(invCombined: Matrix4f) {
        nearTopLeft.set(-1.0f, 1.0f, -1.0f, 1.0f)
        nearTopRight.set(1.0f, 1.0f, -1.0f, 1.0f)
        nearBottomLeft.set(-1.0f, -1.0f, -1.0f, 1.0f)
        nearBottomRight.set(1.0f, -1.0f, -1.0f, 1.0f)
        farTopLeft.set(-1.0f, 1.0f, 1.0f, 1.0f)
        farTopRight.set(1.0f, 1.0f, 1.0f, 1.0f)
        farBottomLeft.set(-1.0f, -1.0f, 1.0f, 1.0f)
        farBottomRight.set(1.0f, -1.0f, 1.0f, 1.0f)

        for (plane in planes) {
            plane.mulProject(invCombined)
        }
    }
}