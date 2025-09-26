package dev.luna5ama.gltest

import dev.fastmc.common.TickTimer
import dev.fastmc.common.wrapDirectByteBuffer
import dev.luna5ama.gltest.cirrus.CirrusCloudScene
import dev.luna5ama.gltest.noisetex.*
import dev.luna5ama.gltest.pc.PointCloudScene
import dev.luna5ama.gltest.structs.Vec2i32
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.kmogus.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.joml.Vector2d
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43C.glDebugMessageCallback
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengl.GLDebugMessageCallback
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.pow

class GameInstance(val window: Long) {
    val backgroundTaskPool: ThreadPoolExecutor

    init {
        val nThreads = Runtime.getRuntime().availableProcessors()
        val threadCounter = AtomicInteger()
        backgroundTaskPool = ThreadPoolExecutor(nThreads, nThreads, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, LinkedBlockingQueue(), ThreadFactory {
            Thread(it, "Background Task Pool #${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
                priority = 1
            }
        })
    }

    val backgroundTaskContext = backgroundTaskPool.asCoroutineDispatcher()
    val backgroundTaskScope = CoroutineScope(backgroundTaskContext)

    companion object {
//        private const val SWAP_INTERVAL = 1
        private const val SWAP_INTERVAL = 0
        private const val PRINT_FPS = false
    }

    var cinematicCamera = false
    var fullScreen = false

    var rerun = false; private set
    val toggles = BooleanArray(10)

    lateinit var mainContext: CoroutineContext
    lateinit var glCapabilities: GLCapabilities

    val prevMousePos = Vector2d()
    val mousePos = Vector2d()
    val mouseDelta = Vector2d()
    private val mousePosBuffer = Arr.malloc(8L * 2)
    private val mouseX = mousePosBuffer.ptr
    private val mouseY = mouseX + 8L

    private val posAndSize = Arr.malloc(sizeof(Vec2i32) * 4L)
    private val prevPosX = posAndSize.ptr
    private val prevPosY = prevPosX + 4L
    private val prevSizeX = prevPosY + 4L
    private val prevSizeY = prevSizeX + 4L
    private val prevPos = Vec2i32(prevPosX)
    private val prevSize = Vec2i32(prevSizeX)

    fun run() {
        glfwMakeContextCurrent(window)
        glfwSwapInterval(SWAP_INTERVAL)

        runCatching { GL.create() }
        glCapabilities = GL.createCapabilities()

        glEnable(GL_DEBUG_OUTPUT)
        glDebugMessageCallback(GLDebugMessageCallback.create { source, type, id, severity, length, message, userParam ->

            val wrappedBuffer = wrapDirectByteBuffer(message, length)
            val byteArray = ByteArray(length)
            wrappedBuffer.get(byteArray)
            val sourceStr = when (source) {
                GL_DEBUG_SOURCE_API -> "API"
                GL_DEBUG_SOURCE_WINDOW_SYSTEM -> "WINDOW_SYSTEM"
                GL_DEBUG_SOURCE_SHADER_COMPILER -> "SHADER_COMPILER"
                GL_DEBUG_SOURCE_THIRD_PARTY -> "THIRD_PARTY"
                GL_DEBUG_SOURCE_APPLICATION -> "APPLICATION"
                GL_DEBUG_SOURCE_OTHER -> "OTHER"
                else -> "UNKNOWN"
            }
            val typeStr = when (type) {
                GL_DEBUG_TYPE_ERROR -> "ERROR"
                GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR -> "DEPRECATED_BEHAVIOR"
                GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR -> "UNDEFINED_BEHAVIOR"
                GL_DEBUG_TYPE_PORTABILITY -> "PORTABILITY"
                GL_DEBUG_TYPE_PERFORMANCE -> "PERFORMANCE"
                GL_DEBUG_TYPE_MARKER -> "MARKER"
                GL_DEBUG_TYPE_PUSH_GROUP -> "PUSH_GROUP"
                GL_DEBUG_TYPE_POP_GROUP -> "POP_GROUP"
                GL_DEBUG_TYPE_OTHER -> "OTHER"
                else -> "UNKNOWN"
            }
            val severityStr = when (severity) {
                GL_DEBUG_SEVERITY_HIGH -> "HIGH"
                GL_DEBUG_SEVERITY_MEDIUM -> "MEDIUM"
                GL_DEBUG_SEVERITY_LOW -> "LOW"
                GL_DEBUG_SEVERITY_NOTIFICATION -> "NOTIFICATION"
                else -> "UNKNOWN"
            }

            if (type == GL_DEBUG_TYPE_PUSH_GROUP) return@create
            if (type == GL_DEBUG_TYPE_POP_GROUP) return@create
            if (severity == GL_DEBUG_SEVERITY_NOTIFICATION) return@create
            if (severity == GL_DEBUG_SEVERITY_LOW) return@create
            if (severity == GL_DEBUG_SEVERITY_MEDIUM) return@create

            println("[OpenGL/$sourceStr] type = $typeStr, severity = $severityStr, message = ${byteArray.decodeToString()}")
            run {} // Dummy line for breakpoint
        }, 0)
        glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS)
        glEnable(GL_DITHER)

        pollErrors("Pre Setup")

        val mouse = Mouse()
        glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE)

        val keyboard = Keyboard()

//    val scene = TestScene(this, mouse, keyboard)
//        val scene = TerrainScene(this, mouse, keyboard)
//        val scene = PointCloudScene(this, mouse, keyboard)
//        val scene = AtmosphereScatteringScene(this, mouse, keyboard)
//        val scene = CirrusCloudScene(this, mouse, keyboard)
        val scene = NoiseTexScene(this, mouse, keyboard)
//        val scene = Curl2DNoiseTexScene(this, mouse, keyboard)

        registerKeyCallbacks(scene, keyboard)

        pollErrors("Post Setup")

        println("Initialized!")

        fun updateMousePos() {
            glfwGetCursorPos(window, mouseX.asDoubleBuffer(1), mouseY.asDoubleBuffer(1))
            prevMousePos.set(mousePos)
            mousePos.set(mouseX.getDouble(), mouseY.getDouble())
            mouseDelta.set(mousePos).sub(prevMousePos)
        }

        var focus = true
        var hideCursor = false
        val focusCallback = glfwSetWindowFocusCallback(window) { window, focused ->
            focus = focused
        }
        glfwSetMouseButtonCallback(window, mouse.callback)
        glfwSetKeyCallback(window, keyboard.callback)
        glfwSetScrollCallback(window) { window, xOffset, yOffset ->
            scene.camera.zoom = max(scene.camera.zoom + yOffset * 0.5, 0.0)
        }

        println(glfwRawMouseMotionSupported())

        mouse.register(GLFW_MOUSE_BUTTON_MIDDLE) {
            if (it == GLFW_RELEASE) {
                glfwSetInputMode(window, GLFW_CURSOR, if (hideCursor) GLFW_CURSOR_NORMAL else GLFW_CURSOR_DISABLED)
                glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
                hideCursor = !hideCursor
            }
        }

        runBlocking {
            mainContext = this.coroutineContext

            val zeroDelta = Vector2d()

            fun handleInput(timeDelta: Double) {
                if (focus) {
                    if (mouseDelta.floor(Vector2d()) != mouseDelta) {
                        println(mouseDelta)
                    }
                    if (hideCursor || mouse.isPressed(GLFW_MOUSE_BUTTON_LEFT) || mouse.isPressed(GLFW_MOUSE_BUTTON_RIGHT)) {
                        scene.camera.rotate(mouseDelta, timeDelta)
                    } else {
                        scene.camera.rotate(zeroDelta, timeDelta)
                    }
                }
            }

            updateMousePos()

            val tickInterval = 1.0 / 60.0
            var lastFrame = glfwGetTime()
            var lastTick = lastFrame
            val frameTimes = mutableListOf<Pair<Double, Double>>()
            val timer = TickTimer()
            while (!glfwWindowShouldClose(window)) {
//                Thread.sleep(1000)
                MemoryStack.get().checkEmpty()
                glfwPollEvents()
                updateMousePos()

                var now = glfwGetTime()
                val timeDelta = (now - lastTick) / tickInterval
//                val timeDelta = 1.0

                handleInput(timeDelta)
                scene.camera.tick(keyboard, timeDelta)
                lastTick = now

                scene.update()
                scene.renderFrame()
                glBindFramebuffer(GL_FRAMEBUFFER, 0)
                scene.samplerManager.clearBinding()
                glfwSwapBuffers(window)

                now = glfwGetTime()
                val frameTime = now - lastFrame
                lastFrame = now

                val removeTime = now - 1.0
                frameTimes.removeIf {
                    it.first < removeTime
                }
                frameTimes.add(now to frameTime)

                if (PRINT_FPS && timer.tickAndReset(3000)) {
                    val averageFrameTime = frameTimes.sumOf { it.second } / frameTimes.size
                    println("FPS: ${(1.0 / averageFrameTime).toInt()} (${"%.2f ms".format(averageFrameTime * 1000.0)})")
//                    println(MemoryTracker.usedMemory)
                }
            }

            scene.destroy()
        }
    }

    private fun toggleFullScreen() {
        fullScreen = !fullScreen
        if (fullScreen) {
            glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_FALSE)
            glfwGetWindowPos(window, prevPosX.asIntBuffer(1), prevPosY.asIntBuffer(1))
            glfwGetWindowSize(window, prevSizeX.asIntBuffer(1), prevSizeY.asIntBuffer(1))

            MemoryStack {
                val arr = malloc(2L * 4L)
                val x = arr.ptr
                val y = x + 4
                val monitor = glfwGetPrimaryMonitor()
                glfwGetMonitorPos(monitor, x.asIntBuffer(1), y.asIntBuffer(1))
                val videoMode = glfwGetVideoMode(monitor)!!

                glfwSetWindowPos(window, 0, 0)
                glfwSetWindowSize(window, videoMode.width(), videoMode.height())
            }
        } else {
            glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_TRUE)
            glfwSetWindowPos(window, prevPos.x, prevPos.y)
            glfwSetWindowSize(window, prevSize.x, prevSize.y)
        }
    }

    private fun registerKeyCallbacks(scene: AbstractScene, keyboard: Keyboard) {
        keyboard.register(GLFW_KEY_F11) {
            if (it == GLFW_RELEASE) {
                toggleFullScreen()
            }
        }

        var border = true
        keyboard.register(GLFW_KEY_F12) {
            if (it == GLFW_RELEASE && keyboard.pressing(GLFW_KEY_LEFT_CONTROL)) {
                border = !border
                glfwSetWindowAttrib(window, GLFW_DECORATED, if (border) GLFW_TRUE else GLFW_FALSE)
            }
        }

        keyboard.register(GLFW_KEY_ESCAPE) {
            if (it == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true)
            }
        }

        keyboard.register(GLFW_KEY_F5) {
            if (it == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true)
                rerun = true
            }
        }

        keyboard.register(GLFW_KEY_F6) {
            if (it == GLFW_RELEASE) {
                scene.reloadShaders()
            }
        }

        keyboard.register(GLFW_KEY_F1) {
            if (it == GLFW_RELEASE) {
                cinematicCamera = !cinematicCamera
            }
        }

        repeat(10) { i ->
            keyboard.register(GLFW_KEY_KP_0 + i) {
                if (it == GLFW_RELEASE) {
                    scene.camera.speedMultiplier = 2.0.pow((i - 5) * 2)
                }
            }
        }

        repeat(10) { i ->
            keyboard.register(GLFW_KEY_0 + i) {
                if (it == GLFW_RELEASE) {
                    toggles[i] = !toggles[i]
                    println("Toggle $i: ${toggles[i]}")
                }
            }
        }
    }

    fun pollErrors(phase: String) {
        var err = glGetError()
        while (err != GL_NO_ERROR) {
            println("$phase OpenGL error: $err")
            err = glGetError()
        }
    }
}