package dev.luna5ama.gltest.concurrent

import dev.fastmc.common.sort.IntComparator
import dev.luna5ama.gltest.test.IntIntrosort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ParallelIntIntrosort(private val scope: CoroutineScope) : IntIntrosort() {
    override fun sortImpl(array: IntArray, from: Int, to: Int, depth: Int) {
        val n = to + 1 - from
        if (n <= PARALLEL_THRESHOLD) {
            super.sortImpl(array, from, to, depth)
        } else {
            scope.launch {
                super.sortImpl(array, from, to, depth)
            }
        }
    }

    override fun sortImpl(array: IntArray, from: Int, to: Int, depth: Int, keys: IntArray) {
        val n = to - from
        if (n <= PARALLEL_THRESHOLD) {
            super.sortImpl(array, from, to, depth, keys)
        } else {
            scope.launch {
                super.sortImpl(array, from, to, depth, keys)
            }
        }
    }

    override fun sortImpl(array: IntArray, from: Int, to: Int, depth: Int, comp: IntComparator) {
        val n = to - from
        if (n <= PARALLEL_THRESHOLD) {
            super.sortImpl(array, from, to, depth, comp)
        } else {
            scope.launch {
                super.sortImpl(array, from, to, depth, comp)
            }
        }
    }

    override fun sortImpl(array: IntArray, from: Int, to: Int, depth: Int, keys: FloatArray) {
        val n = to - from
        if (n <= PARALLEL_THRESHOLD) {
            super.sortImpl(array, from, to, depth, keys)
        } else {
            scope.launch {
                super.sortImpl(array, from, to, depth, keys)
            }
        }
    }

    companion object {
        const val PARALLEL_THRESHOLD = 1024
    }
}