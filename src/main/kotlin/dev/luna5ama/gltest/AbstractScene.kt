package dev.luna5ama.gltest

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register

abstract class AbstractScene(val gameInstance: GameInstance) : IGLObjContainer by IGLObjContainer.Impl() {
    val camera = Camera(this)

    @Suppress("LeakingThis")
    val textureCache = register(TextureCache(this))

    val startTime = System.currentTimeMillis()
    var lastFrameTime = startTime
    var currentTime = System.currentTimeMillis()
    var frameTime = 0L
    var timeSinceStart = 0L

    lateinit var mouse: Mouse; private set
    lateinit var keyboard: Keyboard; private set

    open fun init(mouse: Mouse, keyboard: Keyboard) {
        this.mouse = mouse
        this.keyboard = keyboard
    }


    fun update() {
        currentTime = System.currentTimeMillis()
        timeSinceStart = currentTime - startTime
        frameTime = currentTime - lastFrameTime
        lastFrameTime = currentTime
    }

    abstract suspend fun renderFrame()
}