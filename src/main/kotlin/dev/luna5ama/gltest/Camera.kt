package dev.luna5ama.gltest

import dev.fastmc.common.MathUtil
import dev.fastmc.common.toRadians
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.api.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.*
import org.joml.*
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFW.glfwGetWindowSize
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class Camera(val scene: AbstractScene) {
    private var frameIndex = 0
    var speedMultiplier = 1.0

    val screenSize = Vector2i()
    var screenWidth = 0
    var screenHeight = 0

    var fov = 90.0f
    var viewDistance = 512.0f

    val pos = Vector3d()
    val rotation = Vector2f()

    var aspectRatio = 0.0f; private set
    var farPlane = 0.0f; private set
    var nearPlane = 0.0f; private set
    val planeZ = Vector2f()

    val noJitterProjection = Matrix4f()
    val noJitterModelView = Matrix4f()
    val noJitterCombined = Matrix4f()

    val noJitterInvProjection = Matrix4f()
    val noJitterInvModelView = Matrix4f()
    val noJitterInvCombined = Matrix4f()

    var noJitterFrustum = Frustum(noJitterInvCombined)

    val projection = Matrix4f()
    val modelView = Matrix4f()
    val combined = Matrix4f()
    val viewDir = Vector3f()

    val invProjection = Matrix4f()
    val invModelView = Matrix4f()
    val invCombined = Matrix4f()

    var frustum = Frustum(invCombined); private set

    private val ubo = BufferObject.Immutable().apply {
        allocate(UBO_SIZE, GL_DYNAMIC_STORAGE_BIT)
        label("GlobalCameraUBO")
    }
    private val noJitterUbo = BufferObject.Immutable().apply {
        allocate(UBO_SIZE, GL_DYNAMIC_STORAGE_BIT)
        label("GlobalCameraNoJitterUBO")
    }
    private val prevUBO = BufferObject.Immutable().apply {
        allocate(UBO_SIZE, 0)
        label("GlobalCameraPrevUBO")
    }
    private val prevNoJitterUBO = BufferObject.Immutable().apply {
        allocate(UBO_SIZE, 0)
        label("GlobalCameraPrevNoJitterUBO")
    }
    val noJitterUboBinding = listOf(
        ShaderBindingSpecs.Buffer("GlobalCameraDefault", noJitterUbo, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("GlobalCameraJitter", ubo, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("GlobalCameraNoJitter", noJitterUbo, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("PrevGlobalCameraDefault", prevNoJitterUBO, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("PrevGlobalCameraJitter", prevUBO, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("PrevGlobalCameraNoJitter", prevNoJitterUBO, BufferTarget.Uniform)
    )
    val uboBinding = listOf(
        ShaderBindingSpecs.Buffer("GlobalCameraDefault", ubo, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("GlobalCameraJitter", ubo, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("GlobalCameraNoJitter", noJitterUbo, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("PrevGlobalCameraDefault", prevUBO, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("PrevGlobalCameraJitter", prevUBO, BufferTarget.Uniform),
        ShaderBindingSpecs.Buffer("PrevGlobalCameraNoJitter", prevNoJitterUBO, BufferTarget.Uniform)
    )

    val jitter = Vector2f()

    private val mouseMotion = Vector2d()

    fun updateScreenSize() {
        MemoryStack {
            val arr = calloc(4 * 2)
            val x = arr.ptr
            val y = x + 4
            glfwGetWindowSize(scene.gameInstance.window, x.asIntBuffer(1), y.asIntBuffer(1))
            screenWidth = x.getInt()
            screenHeight = y.getInt()
            screenSize.set(screenWidth, screenHeight)
        }
    }

    fun update(offsetX: Float, offsetY: Float) {
        jitter.set(offsetX, offsetY)
        nearPlane = 0.05f
        farPlane = viewDistance * 1.5f
        planeZ.set(nearPlane, farPlane)
        aspectRatio = screenWidth.toFloat() / screenHeight.toFloat()

        noJitterProjection.identity()
            .perspective(fov.toRadians(), aspectRatio, nearPlane, farPlane)
        noJitterModelView.identity()
            .rotate(rotation.y.toRadians(), 1.0f, 0.0f, 0.0f)
            .rotate((rotation.x + 180.0f).toRadians(), 0.0f, 1.0f, 0.0f)

        noJitterUbo.copyTo(prevNoJitterUBO)
        updateUBO(
            noJitterUbo,
            noJitterProjection,
            noJitterModelView,
            noJitterCombined,
            noJitterInvProjection,
            noJitterInvModelView,
            noJitterInvCombined
        )
        noJitterFrustum = Frustum(noJitterInvCombined)

        projection.identity()
            .translate((offsetX * 2.0f) / screenWidth, (offsetY * 2.0f) / screenHeight, 0.0f)
            .perspective(fov.toRadians(), screenWidth.toFloat() / screenHeight.toFloat(), nearPlane, farPlane)
        modelView.identity()
            .rotate(rotation.y.toRadians(), 1.0f, 0.0f, 0.0f)
            .rotate((rotation.x + 180.0f).toRadians(), 0.0f, 1.0f, 0.0f)

        ubo.copyTo(prevUBO)
        updateUBO(ubo, projection, modelView, combined, invProjection, invModelView, invCombined)
        frustum = Frustum(invCombined)

        frameIndex++
    }

    private fun updateUBO(
        buffer: BufferObject.Immutable,
        projection: Matrix4f,
        modelView: Matrix4f,
        combined: Matrix4f,
        invProjection: Matrix4f,
        invModelView: Matrix4f,
        invCombined: Matrix4f
    ) {
        combined.set(projection).mul(modelView)
        invProjection.set(projection).invert()
        invModelView.set(modelView).invert()
        invCombined.set(combined).invert()

        viewDir.set(0.0f, 0.0f, -1.0f).mulDirection(invModelView)

        MemoryStack {
            val arr = calloc(UBO_SIZE).asMutable()
            projection.copyToMutableArr(arr)
            modelView.copyToMutableArr(arr)
            combined.copyToMutableArr(arr)
            invProjection.copyToMutableArr(arr)
            invModelView.copyToMutableArr(arr)
            invCombined.copyToMutableArr(arr)
            arr.usePtr {
                setFloatInc(screenWidth.toFloat())
                    .setFloatInc(screenHeight.toFloat())
                    .setFloatInc(1.0f / screenWidth)
                    .setFloatInc(1.0f / screenHeight)
            }
            rotation.copyToMutableArr(arr)
            arr.usePtr {
                setFloatInc(viewDir.x)
                    .setFloatInc(viewDir.y)
                    .setFloatInc(viewDir.z)
                    .inc()
                    .setFloatInc(0.05f)
                    .setFloatInc(viewDistance * 1.5f)
                    .setIntInc(frameIndex)
            }

            buffer.invalidate()
            buffer.upload(arr.basePtr)
        }
    }

    private val motion = Vector3d()

    fun rotate(mouseDelta: Vector2d, timeDelta: Double) {
        if (scene.gameInstance.cinematicCamera) {
            val sensitivity = 0.0002
            val newDelta = Vector2d(mouseDelta).mul(-sensitivity)

            mouseMotion.mul(0.99.pow(timeDelta))
            mouseMotion.add(newDelta)
        } else {
            val sensitivity = 0.1
            mouseMotion.set(mouseDelta).mul(-sensitivity)
        }

        this.rotation.add(mouseMotion.x.toFloat(), mouseMotion.y.toFloat())
        this.rotation.y = MathUtil.clamp(this.rotation.y, -90.0f, 90.0f)
    }

    fun tick(keyboard: Keyboard, timeDelta: Double) {
        if (scene.gameInstance.cinematicCamera) {
            var speed = 0.001 * speedMultiplier
            speed *= timeDelta

            motion.mul(0.99.pow(timeDelta))
            val yawRad = rotation.x.toRadians()
            val sinYaw = sin(yawRad)
            val cosYaw = cos(yawRad)

            val forward = keyboard.pressing(GLFW.GLFW_KEY_W)
            val backward = keyboard.pressing(GLFW.GLFW_KEY_S)
            val left = keyboard.pressing(GLFW.GLFW_KEY_A)
            val right = keyboard.pressing(GLFW.GLFW_KEY_D)

            val newMotionXZ = Vector3d()

            if (keyboard.pressing(GLFW.GLFW_KEY_SPACE)) {
                newMotionXZ.y += 1.0
            }

            if (keyboard.pressing(GLFW.GLFW_KEY_LEFT_SHIFT)) {
                newMotionXZ.y -= 1.0
            }

            if (forward) {
                newMotionXZ.x -= sinYaw
                newMotionXZ.z += cosYaw
            }

            if (backward) {
                newMotionXZ.x += sinYaw
                newMotionXZ.z -= cosYaw
            }

            if (left) {
                newMotionXZ.x += cosYaw
                newMotionXZ.z += sinYaw
            }

            if (right) {
                newMotionXZ.x -= cosYaw
                newMotionXZ.z -= sinYaw
            }

            if (newMotionXZ.lengthSquared() > 0.0) {
                newMotionXZ.normalize().mul(speed)
            }

            motion.add(newMotionXZ)
            pos.add(motion)
        } else {
            var hSpeed = 0.3 * speedMultiplier
            var vSpeed = 0.2 * speedMultiplier

            hSpeed *= timeDelta
            vSpeed *= timeDelta

            if (keyboard.pressing(GLFW.GLFW_KEY_SPACE)) {
                pos.y += vSpeed
            } else if (keyboard.pressing(GLFW.GLFW_KEY_LEFT_SHIFT)) {
                pos.y -= vSpeed
            }

            val yawRad = rotation.x.toRadians()

            val velocity = Vector2d()

            if (keyboard.pressing(GLFW.GLFW_KEY_W)) {
                velocity.x += -sin(yawRad)
                velocity.y += cos(yawRad)
            }

            if (keyboard.pressing(GLFW.GLFW_KEY_S)) {
                velocity.x -= -sin(yawRad)
                velocity.y -= cos(yawRad)
            }

            if (keyboard.pressing(GLFW.GLFW_KEY_A)) {
                velocity.x += cos(yawRad)
                velocity.y += sin(yawRad)
            }

            if (keyboard.pressing(GLFW.GLFW_KEY_D)) {
                velocity.x -= cos(yawRad)
                velocity.y -= sin(yawRad)
            }

            if (velocity.lengthSquared() > 0.0) {
                velocity.normalize().mul(hSpeed)
                pos.x += velocity.x
                pos.z += velocity.y
            }
        }
    }

    class Snapshot(camera: Camera) {
        val screenWidth = camera.screenWidth
        val screenHeight = camera.screenHeight

        val fov = camera.fov
        val viewDistance = camera.viewDistance

        val pos = Vector3d(camera.pos)
        val rotation = Vector2f(camera.rotation)

        val aspectRatio = camera.aspectRatio
    }

    companion object {
        const val UBO_SIZE = 436L
    }
}