package dev.luna5ama.gltest

import it.unimi.dsi.fastutil.longs.LongArrayList

class CPUTimer(private val runtimeLength: Int) {
    private val times = LongArrayList()
    private var startTime = 0L

    fun begin() {
        startTime = System.nanoTime()
    }

    fun end() {
        while (times.size > runtimeLength) {
            times.removeLong(0)
        }

        times.add(System.nanoTime() - startTime)
    }

    fun averageMS(): Double {
        if (times.isEmpty) return 0.0
        return times.average() / 1_000_000.0
    }
}