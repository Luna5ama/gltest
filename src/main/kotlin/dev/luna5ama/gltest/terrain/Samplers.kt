package dev.luna5ama.gltest.terrain

import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.objects.SamplerObject
import dev.luna5ama.glwrapper.objects.setBorderColor
import dev.luna5ama.glwrapper.objects.setFilter
import dev.luna5ama.glwrapper.objects.setWrap
import dev.luna5ama.kmogus.Ptr

class Samplers : IGLObjContainer by IGLObjContainer.Impl() {
    val nearestRepeat = register(SamplerObject()).apply {
        setFilter(GL_NEAREST, GL_NEAREST)
        setWrap(GL_REPEAT, GL_REPEAT)
    }
    val nearestClampEdge = register(SamplerObject()).apply {
        setFilter(GL_NEAREST, GL_NEAREST)
        setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
    }
    val nearestClampBorder0 = register(SamplerObject()).apply {
        setFilter(GL_NEAREST, GL_NEAREST)
        setWrap(GL_CLAMP_TO_BORDER, GL_CLAMP_TO_BORDER)
        setBorderColor(0.0f, 0.0f, 0.0f, 0.0f)
    }
    val nearestClampBorder1 = register(SamplerObject()).apply {
        setFilter(GL_NEAREST, GL_NEAREST)
        setWrap(GL_CLAMP_TO_BORDER, GL_CLAMP_TO_BORDER)
        setBorderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    val linearRepeat = register(SamplerObject()).apply {
        setFilter(GL_LINEAR, GL_LINEAR)
        setWrap(GL_REPEAT, GL_REPEAT)
    }
    val linearClampEdge = register(SamplerObject()).apply {
        setFilter(GL_LINEAR, GL_LINEAR)
        setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
    }
    val linearClampBorder0 = register(SamplerObject()).apply {
        setFilter(GL_LINEAR, GL_LINEAR)
        setWrap(GL_CLAMP_TO_BORDER, GL_CLAMP_TO_BORDER)
        setBorderColor(0.0f, 0.0f, 0.0f, 0.0f)
    }
    val linearClampBorder1 = register(SamplerObject()).apply {
        setFilter(GL_LINEAR, GL_LINEAR)
        setWrap(GL_CLAMP_TO_BORDER, GL_CLAMP_TO_BORDER)
        setBorderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private val maxUnit = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS)

    fun clearBinding() {
        glBindSamplers(0, maxUnit, Ptr.NULL)
    }
}