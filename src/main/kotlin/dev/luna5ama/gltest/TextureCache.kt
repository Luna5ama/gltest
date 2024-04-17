package dev.luna5ama.gltest

import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.enums.ImageFormat

class TextureCache(private val scene: AbstractScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private var lastWidth = 0
    private var lastHeight = 0

    private val cacheMap2D = mutableMapOf<TextureKey, MutableList<Entry<TextureObject.Texture2D>>>()
    private val stacks = mutableListOf<UseStack>()

    fun update() {
        if (lastWidth != scene.camera.screenWidth || lastHeight != scene.camera.screenHeight) {
            lastWidth = scene.camera.screenWidth
            lastHeight = scene.camera.screenHeight
            destroy()
            clearObjs()
            cacheMap2D.clear()
        }
    }

    fun pushStack(): UseStack {
        return stacks.removeLastOrNull() ?: UseStack()
    }

    inline fun useStack(block: UseStack.() -> Unit) {
        pushStack().use(block)
    }

    inner class Entry<T: TextureObject> internal constructor(internal val key: TextureKey, val texture: T) {
        fun release() {
            when (texture) {
                is TextureObject.Texture2D -> {
                    @Suppress("UNCHECKED_CAST")
                    cacheMap2D.getOrPut(key, ::mutableListOf).add(this as Entry<TextureObject.Texture2D>)
                }
                else -> error("Unsupported texture type")
            }
        }
    }

    internal data class TextureKey(val width: Int, val height: Int, val depth: Int, val format: ImageFormat.Sized)

    inner class UseStack : AutoCloseable {
        private val entries = mutableListOf<Entry<*>>()

        fun getTexture2D(format: ImageFormat.Sized, width: Int, height: Int): TextureObject.Texture2D {
            val key = TextureKey(width, height, -1, format)
            val cacheList = cacheMap2D.getOrPut(key, ::mutableListOf)
            var entry = cacheList.removeLastOrNull()

            if (entry == null) {
                entry = Entry(key, TextureObject.Texture2D())
                entry.texture.allocate(1, format, width, height)
                register(entry.texture)
            }

            entries.add(entry)

            return entry.texture
        }

        override fun close() {
            entries.forEach { it.release() }
            entries.clear()
            stacks.add(this)
        }
    }
}