package dev.luna5ama.gltest

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.GLWrapper
import dev.luna5ama.glwrapper.enums.GLObjectType
import dev.luna5ama.glwrapper.objects.IGLObject

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

    open fun reloadShaders() {
        GLWrapper.instance.shaderSrcProviders.clearCache()
        val objs = mutableMapOf<GLObjectType, MutableSet<IGLObject>>()
        collectObjs(objs)
        objs[GLObjectType.Program]?.forEach {
            if (it is ShaderProgram) {
                it.reload()
            }
        }
    }
}