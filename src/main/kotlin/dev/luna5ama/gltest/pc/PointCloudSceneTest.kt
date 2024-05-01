package dev.luna5ama.gltest.pc

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.*
import dev.luna5ama.gltest.terrain.Textures
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.MemoryStack
import org.joml.Vector2i
import java.io.File
import java.util.*

class PointCloudSceneTest(gameInstance: GameInstance) : AbstractScene(gameInstance) {
        private val pcData = register(PointCloudDataTest(this, File("dumpMountainS64")))

    override suspend fun renderFrame() {

    }
}