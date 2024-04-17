package dev.luna5ama.gltest.terrain

import dev.luna5ama.gltest.linearClampEdge
import dev.luna5ama.glwrapper.objects.TextureObject

fun checkSizeAndCopy(src: TextureObject.Tex2D, dst: TextureObject.Tex2D) {
    if (dst.width != src.width || dst.height != src.height) {
        dst.destroy()
        dst.allocate(1, src.internalformat!!, src.width, src.height)
        dst.linearClampEdge()
    }

    src.copyTo(dst)
}