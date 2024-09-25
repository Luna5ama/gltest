package dev.luna5ama.gltest

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.BufferView
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject

class GPUScan : IGLObjContainer by IGLObjContainer.Impl() {
    val workGroupSizePow = 8
    val workGroupSize = 1 shl workGroupSizePow

    private val indirectBuffer = register(BufferObject.Immutable()).apply {
        label("GPUScan.IndirectBuffer")
        allocate(16L * 6L, 0)
    }

    private val indirectSSBOBinding =
        ShaderBindingSpecs.Buffer("IndirectBuffer", indirectBuffer, BufferTarget.ShaderStorage)

    private val indirectUBOBinding = ShaderBindingSpecs.Buffer("PassIndirect", indirectBuffer, BufferTarget.Uniform)

//    private val scanIndirectUBOBinding = List(3) {
//        ShaderBindingSpecs.Buffer("PassIndirect", indirectBuffer.createView(16L * it, 16L), BufferTarget.Uniform)
//    }
//
//    private val addIndirectUBOBinding = List(3) {
//        ShaderBindingSpecs.Buffer("PassIndirect", indirectBuffer.createView(16L * (it + 3), 16L), BufferTarget.Uniform)
//    }

    private val setupShader = register(ShaderProgram(
        ShaderSource.Comp("scan/Setup.comp") {
            define("WORK_GROUP_SIZE", workGroupSize)
            define("WORK_GROUP_SIZE_POW", workGroupSizePow)
        }
    )).apply {
        label("GPUScan.SetupShader")
    }

    private val singleWgShader = register(ShaderProgram(ShaderSource.Comp("scan/SingleWg.comp"))).apply {
        label("GPUScan.SingleWgShader")
    }

    private val scanShader = register(ShaderProgram(
        ShaderSource.Comp("scan/Scan.comp") {
            define("WORK_GROUP_SIZE", workGroupSize)
            define("WORK_GROUP_SIZE_POW", workGroupSizePow)
        }
    )).apply {
        label("GPUScan.ScanShader")
    }

    private val addShader = register(ShaderProgram(
        ShaderSource.Comp("scan/Add.comp") {
            define("WORK_GROUP_SIZE", workGroupSize)
            define("WORK_GROUP_SIZE_POW", workGroupSizePow)
        }
    )).apply {
        label("GPUScan.AddShader")
    }

    private val auxSumBuffer1 = register(BufferObject.Immutable()).apply {
        label("GPUScan.AuxSumBuffer1")
    }

    private val auxSumBuffer2 = register(BufferObject.Immutable()).apply {
        label("GPUScan.AuxSumBuffer2")
    }

    private val auxSumBuffer3 = register(BufferObject.Immutable()).apply {
        label("GPUScan.AuxSumBuffer2")
    }

    fun process(
        inputBuffer: BufferView,
        countBuffer: BufferView,
        outputBuffer: BufferView,
        maxCount: Int
    ) {
        if (maxCount <= 1) return

        glDebugGroup("GPUScan") {
            if (maxCount < 2048) {
                processSingleWg(inputBuffer, countBuffer, outputBuffer, maxCount)
            } else {
                processMultiWg(inputBuffer, countBuffer, outputBuffer, maxCount)
            }
        }
    }

    private fun processSingleWg(
        inputBuffer: BufferView,
        countBuffer: BufferView,
        outputBuffer: BufferView,
        maxCount: Int
    ) {
        singleWgShader.bind()
        singleWgShader.applyBinding {
            buffer("InputBuffer", inputBuffer, BufferTarget.ShaderStorage)
            buffer("OutputBuffer", outputBuffer, BufferTarget.ShaderStorage)
            buffer("CountBuffer", countBuffer, BufferTarget.Uniform)
        }
        glDispatchCompute(1, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
    }

    private fun processMultiWg(
        inputBuffer: BufferView,
        countBuffer: BufferView,
        outputBuffer: BufferView,
        maxCount: Int
    ) {
        var maxPass = 0
        var count = maxCount
        while (count > 1) {
            count = (count + workGroupSize - 1) / workGroupSize
            maxPass++
        }

            val auxSumCount1 = (maxCount + workGroupSize - 1) / workGroupSize
            val auxSumSize1 = auxSumCount1 * 4L
            if (auxSumBuffer1.size < auxSumSize1) {
                auxSumBuffer1.destroy()
                auxSumBuffer1.allocate(auxSumSize1, 0)
            }

            val auxSumCount2 = (auxSumCount1 + workGroupSize - 1) / workGroupSize
            val auxSumSize2 = auxSumCount2 * 4L
            if (maxPass >= 2 && auxSumBuffer2.size < auxSumSize2) {
                auxSumBuffer2.destroy()
                auxSumBuffer2.allocate(auxSumSize2, 0)
            }

            val auxSumCount3 = (auxSumCount2 + workGroupSize - 1) / workGroupSize
            val auxSumSize3 = auxSumCount3 * 4L
            if (maxPass >= 3 && auxSumBuffer3.size < auxSumSize3) {
                auxSumBuffer3.destroy()
                auxSumBuffer3.allocate(auxSumSize3, 0)
            }

            setupShader.bind()
            setupShader.applyBinding {
                buffer("CountBuffer", countBuffer, BufferTarget.Uniform)
                buffer(indirectSSBOBinding)
            }
            glDispatchCompute(1, 1, 1)
            glMemoryBarrier(GL_DISPATCH_INDIRECT_BUFFER or GL_SHADER_STORAGE_BARRIER_BIT or GL_UNIFORM_BARRIER_BIT)

            indirectBuffer.bind(GL_DISPATCH_INDIRECT_BUFFER)

            scanShader.bind()
            scanShader.applyBinding {
                buffer("InputBuffer", inputBuffer, BufferTarget.ShaderStorage)
                buffer("OutputBuffer", outputBuffer, BufferTarget.ShaderStorage)
                buffer("AuxSumBuffer", auxSumBuffer1, BufferTarget.ShaderStorage)
                buffer(indirectUBOBinding)
            }
            scanShader.uniform1ui("uval_pass", 0)
            glDispatchComputeIndirect(0 * 16L)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

            if (maxPass >= 2) {
                scanShader.bind()
                scanShader.applyBinding {
                    buffer("InputBuffer", auxSumBuffer1, BufferTarget.ShaderStorage)
                    buffer("OutputBuffer", auxSumBuffer1, BufferTarget.ShaderStorage)
                    buffer(ShaderBindingSpecs.Buffer("AuxSumBuffer", auxSumBuffer2, BufferTarget.ShaderStorage))
                    buffer(indirectUBOBinding)
                }
                scanShader.uniform1ui("uval_pass", 1)
                glDispatchComputeIndirect(1 * 16L)
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
            }

            if (maxPass >= 3) {
                scanShader.bind()
                scanShader.applyBinding {
                    buffer("InputBuffer", auxSumBuffer2, BufferTarget.ShaderStorage)
                    buffer("OutputBuffer", auxSumBuffer2, BufferTarget.ShaderStorage)
                    buffer("AuxSumBuffer", auxSumBuffer3, BufferTarget.ShaderStorage)
                    buffer(indirectUBOBinding)
                }
                scanShader.uniform1ui("uval_pass", 2 )
                glDispatchComputeIndirect(2 * 16L)
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
            }

            if (maxPass >= 3) {
                addShader.bind()
                addShader.applyBinding {
                    buffer("InputBuffer", auxSumBuffer2, BufferTarget.ShaderStorage)
                    buffer("OutputBuffer", auxSumBuffer1, BufferTarget.ShaderStorage)
                    buffer(indirectUBOBinding)
                }
                addShader.uniform1ui("uval_pass", 1 + 3)
                glDispatchComputeIndirect((1 + 3) * 16L)
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
            }

            if (maxPass >= 2) {
                addShader.bind()
                addShader.applyBinding {
                    buffer("InputBuffer", auxSumBuffer1, BufferTarget.ShaderStorage)
                    buffer("OutputBuffer", outputBuffer, BufferTarget.ShaderStorage)
                    buffer(indirectUBOBinding)
                }
                addShader.uniform1ui("uval_pass", 0 + 3)
                glDispatchComputeIndirect((0 + 3) * 16L)
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
            }
    }
}