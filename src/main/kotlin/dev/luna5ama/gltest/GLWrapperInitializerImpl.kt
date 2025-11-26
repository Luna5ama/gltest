package dev.luna5ama.gltest

import dev.luna5ama.glwrapper.base.GLWrapperInitializer
import dev.luna5ama.glwrapper.base.GLWrapperInitializerLWJGL3
import dev.luna5ama.glwrapper.base.ShaderPathResolver
import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.Path

class GLWrapperInitializerImpl : GLWrapperInitializer by GLWrapperInitializerLWJGL3() {
    override val priority: Int = 999

    override fun createPathResolver(): ShaderPathResolver {
        return if (System.getProperty("gltest.devenv").toBoolean()) {
            PathResolverImpl()
        } else {
            super.createPathResolver()
        }
    }

    class PathResolverImpl : ShaderPathResolver() {
        private val root = Path("I:\\code\\gltest\\src\\main\\resources")

        override fun resolve0(path: String): Path {
            return root.resolve(path)
        }
    }
}