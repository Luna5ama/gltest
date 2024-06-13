package dev.luna5ama.gltest.terrain

import dev.fastmc.common.EnumMap
import dev.fastmc.common.ceilToInt
import dev.fastmc.common.sq
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.api.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.ShaderSource.Companion.invoke
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.kmogus.MemoryStack
import java.util.*
import kotlin.math.ln
import kotlin.math.sqrt

class DeferredShaders(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    val solid = register(
        DeferredShader(
            ShaderSource.Vert("terrain/solid/Deferred.vert"),
            ShaderSource.Frag("terrain/solid/Deferred.frag")
        )
    )
    val cutout = register(
        DeferredShader(
            ShaderSource.Vert("terrain/solid/Deferred.vert"),
            ShaderSource.Frag("terrain/solid/Deferred.frag") {
                define("CUTOUT")
            }
        )
    )
    val water = register(
        DeferredShader(
            ShaderSource.Vert("terrain/water/Deferred.vert"),
            ShaderSource.Frag("terrain/water/Deferred.frag")
        )
    )

    inner class DeferredShader(
        vararg sources: ShaderSource
    ) : TerrainDrawShader(*sources) {
        override fun bind() {
            super.bind()
            setOriginOffset(
                scene.originX - scene.camera.pos.x,
                scene.originY - scene.camera.pos.y,
                scene.originZ - scene.camera.pos.z
            )
        }
    }
}

class PostShaders(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    var fogRangeSq = Int.MAX_VALUE; private set

    private val fogParametersUBO = register(BufferObject.Immutable()).apply {
        allocate(32, GL_DYNAMIC_STORAGE_BIT)
    }
    val fogParametersBinding = ShaderBindingSpecs.Buffer("FogParameters", fogParametersUBO, BufferTarget.Uniform)

    private var activeFogShape = FogShape.SPHERE
    private val waterShaders = EnumMap<FogShape, EnumMap<FogType, TerrainDrawShader>>()
    private var activeWater = getWaterShader(FogShape.SPHERE, FogType.LINEAR)

    val water get() = activeWater

    fun linearFog(fogShape: FogShape, start: Float, end: Float, red: Float, green: Float, blue: Float) {
        val m = -(1.0f / (end - start))
        val b = end * -m
        updateFogParametersUBO(m, b, red, green, blue)
        fogRangeSq = (end.toDouble() + 8.0).ceilToInt().sq
        updateActiveShader(fogShape, FogType.LINEAR)
    }

    fun expFog(fogShape: FogShape, density: Float, red: Float, green: Float, blue: Float) {
        updateFogParametersUBO(density, 420.69f, red, green, blue)
        fogRangeSq = (-ln(0.001) / density.toDouble() + 8.0).ceilToInt().sq
        updateActiveShader(fogShape, FogType.EXP)
    }

    fun exp2Fog(fogShape: FogShape, density: Float, red: Float, green: Float, blue: Float) {
        updateFogParametersUBO(density, 420.69f, red, green, blue)
        fogRangeSq = (sqrt(-ln(0.001)) / density.toDouble() + 8.0).ceilToInt().sq
        updateActiveShader(fogShape, FogType.EXP2)
    }

    fun updateActiveShader(fogShape: FogShape, fogType: FogType) {
        activeFogShape = fogShape
        activeWater = getWaterShader(fogShape, fogType)
    }

    private fun getWaterShader(fogShape: FogShape, fogType: FogType): TerrainDrawShader {
        return waterShaders.getOrPut(fogShape, ::EnumMap).getOrPut(fogType) {
            register(
                PostDrawShader(
                    fogShape,
                    fogType,
                    ShaderSource.Vert("terrain/Blit.vert.glsl"),
                    ShaderSource.Frag("terrain/water/Post.frag")
                )
            )
        }
    }

    private fun updateFogParametersUBO(
        densityOrM: Float,
        b: Float,
        red: Float,
        green: Float,
        blue: Float
    ) {
        MemoryStack {
            val arr = malloc(fogParametersUBO.size)
            val ptr = arr.ptr

            ptr.setFloatInc(red)
                .setFloatInc(green)
                .setFloatInc(blue)
                .setFloatInc(1.0f)
                .setFloatInc(densityOrM)
                .setFloatInc(b)

            fogParametersUBO.invalidate()
            fogParametersUBO.upload(ptr)
        }
    }

    inner class PostDrawShader(
        fogShape: FogShape,
        fogType: FogType,
        vert: ShaderSource.Vert,
        frag: ShaderSource.Frag
    ) : TerrainDrawShader(
        vert,
        frag {
            FogShape.values().forEach {
                define(it.toString(), it.ordinal)
            }
            FogType.values().forEach {
                define(it.toString(), it.ordinal)
            }
            define("FOG_SHAPE", fogShape)
            define("FOG_TYPE", fogType)
        }
    ) {
        override fun bind() {
            super.bind()
            setOriginOffset(
                scene.originX - scene.camera.pos.x,
                scene.originY - scene.camera.pos.y,
                scene.originZ - scene.camera.pos.z
            )
        }
    }

    enum class FogType {
        LINEAR,
        EXP,
        EXP2;

        val fullName = "FOG_TYPE_$name"

        override fun toString(): String {
            return fullName
        }
    }

    enum class FogShape {
        SPHERE {
            override fun distanceSq(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Int {
                return dev.fastmc.common.distanceSq(x1, y1, z1, x2, y2, z2)
            }
        },
        CYLINDER {
            override fun distanceSq(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Int {
                return dev.fastmc.common.distanceSq(x1, z1, x2, z2)
            }
        };

        val fullName = "FOG_SHAPE_$name"

        abstract fun distanceSq(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Int

        override fun toString(): String {
            return fullName
        }
    }
}

open class TerrainDrawShader(
    vararg srcs: ShaderSource
) : ShaderProgram(*srcs) {
    internal fun setOriginOffset(x: Double, y: Double, z: Double) {
        uniform3f(
            "originOffset",
            (x - 0.5).toFloat(),
            y.toFloat(),
            (z - 0.5).toFloat()
        )
    }
}