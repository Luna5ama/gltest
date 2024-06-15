package dev.luna5ama.gltest

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.GLObjectType
import dev.luna5ama.glwrapper.objects.IGLObject
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongArrayList

class GPUTimer(private val runtimeLength: Int) : IGLObjContainer {
    private val queryQueue = IntArrayFIFOQueue()
    private val times = LongArrayList()

    fun begin() {
        val query = glCreateQueries(GL_TIME_ELAPSED)
        glBeginQuery(GL_TIME_ELAPSED, query)
        queryQueue.enqueue(query)
    }

    fun end() {
        glEndQuery(GL_TIME_ELAPSED)
        while (!queryQueue.isEmpty) {
            val available = glGetQueryObjecti(queryQueue.firstInt(), GL_QUERY_RESULT_AVAILABLE)
            if (available == GL_TRUE) {
                val id = queryQueue.dequeueInt()
                val time = glGetQueryObjectui64(id, GL_QUERY_RESULT)
                glDeleteQueries(id)
                times.add(time)
            } else {
                break
            }
        }

        while (times.size > runtimeLength) {
            times.removeLong(0)
        }
    }

    fun averageMS(): Double {
        if (times.isEmpty) return 0.0
        return times.average() / 1_000_000.0
    }

    override fun <T : IGLObject> register0(obj: T): T {
        return obj
    }

    override fun <T : IGLObjContainer> register0(container: T): T {
        return container
    }

    override fun destroy() {
        while (!queryQueue.isEmpty) {
            glDeleteQueries(queryQueue.dequeueInt())
        }
    }

    override fun collectObjs(output: MutableMap<GLObjectType, MutableSet<IGLObject>>) {}

    override fun clearObjs() {}
}