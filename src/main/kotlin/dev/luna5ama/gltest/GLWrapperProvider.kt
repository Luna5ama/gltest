package dev.luna5ama.gltest

import dev.luna5ama.gltest.GameInstance.PathResolverImpl
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.GLWrapper
import dev.luna5ama.glwrapper.api.GLWrapperProvider
import dev.luna5ama.glwrapper.api.GLWrapperProviderLWJGL3

class GLWrapperProviderImpl : GLWrapperProvider {
    override val priority: Int = 999

    private val lwjgl3 = GLWrapperProviderLWJGL3()

    override fun create(shaderSrcPathResolver: ShaderSource.PathResolver): GLWrapper {
        val delegate = lwjgl3.create(shaderSrcPathResolver)
        val pathResolver = if (System.getProperty("gltest.devenv").toBoolean()) {
            PathResolverImpl()
        } else {
            ShaderSource.PathResolver.Default
        }
        return GLWrapper(delegate, pathResolver,)
    }
}