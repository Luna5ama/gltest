package dev.luna5ama.gltest.pc

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

class LWWSP(val workerCount: Int, val threadConfigure: (Thread) -> Unit = {}) {
    private val workers = List(workerCount) { Worker(it).also(threadConfigure) }
    private val activeWorkerCount = AtomicInteger()

    private val isAccepting = AtomicBoolean(true)
    private val isRunning = AtomicBoolean(true)

    init {
        workers.forEach { it.start() }
    }

    fun shutdown() {
        isAccepting.set(false)
        while (activeWorkerCount.get() != 0) {
            workers.forEach { it.interrupt() }
        }
        workers.forEach { it.join() }
    }

    fun shutdownNow() {
        isRunning.set(false)
        shutdown()
    }

    fun iterativeTask(size: Int, batchSize: Int, action: IterativeAction): Task<Unit> {
        val task = IterativeTask(size, batchSize, action)
        submitTask(task)
        return task
    }

    inline fun <S> scopedIterativeTask(
        size: Int,
        batchSize: Int,
        crossinline newScope: () -> S,
        action: ScopedIterativeAction<S>
    ): Task<List<S>> {
        return scopedIterativeTask(size, batchSize, { _: Int -> newScope() }, action)
    }

    fun <S> scopedIterativeTask(
        size: Int,
        batchSize: Int,
        newScope: (Int) -> S,
        action: ScopedIterativeAction<S>
    ): Task<List<S>> {
        val task = ScopedIterativeTask(size, batchSize, newScope, action)
        submitTask(task)
        return task
    }

    private fun submitTask(task: AbstractTask<*>) {
        if (isAccepting.get()) {
            synchronized(workers) {
                workers.forEach {
                    it.taskQueue.offer(task)
                }
            }
        }
    }

    fun interface IterativeAction {
        fun run(start: Int, end: Int)
    }

    fun interface ScopedIterativeAction<S> {
        fun S.run(start: Int, end: Int)
    }

    sealed interface RecursiveScope<T, R> {
        suspend fun callRecursive(v: T): R
        suspend fun <U, S> DeepRecursiveFunction<U, S>.callRecursive(value: U): S
        operator fun DeepRecursiveFunction<*, *>.invoke(value: Any?) {
            throw UnsupportedOperationException("Should not be called from DeepRecursiveScope")
        }
    }

    interface RecursiveAction<T, R> {
        suspend fun run(): R
    }

    private inner class Worker(val id: Int) : Thread("LWWSP-$id") {
        val taskQueue = LinkedBlockingDeque<AbstractTask<*>>()
        val random = Random()

        override fun run() {
            activeWorkerCount.incrementAndGet()
            var waitTime = 100L
            while (isRunning.get()) {
                val cTask = taskQueue.poll(waitTime, TimeUnit.MICROSECONDS)
                if (cTask == null) {
                    waitTime = minOf(waitTime * 2L, 5_000_000)
                    continue
                }
                waitTime = 100L
                do {
                    cTask.run(this)
                    yield()
                } while (!cTask.isCompleted)
            }
            activeWorkerCount.decrementAndGet()
        }
    }

    sealed interface Task<T> {
        val isCompleted: Boolean
        fun awaitBlocking(): Result<T>
        suspend fun await(): Result<T>
    }

    private val unitResult = Result.success(Unit)

    private abstract inner class AbstractTask<T> : Task<T> {
        private val completeResult = AtomicReference<Result<T>>(null)
        private val lock = ReentrantLock()
        private val condition = lock.newCondition()
        private val continuations = ConcurrentLinkedQueue<Continuation<T>>()

        override val isCompleted get() = completeResult.get() != null

        protected fun complete(result: Result<T>) {
            var success = false
            while (completeResult.get() == null) {
                success = completeResult.compareAndSet(null, result)
            }
            if (!success) return
            lock.withLock {
                condition.signalAll()
            }
            continuations.forEach { it.resumeWith(result) }
        }

        protected fun completeFailure(result: Result<T>) {
            val oldResult = completeResult.getAndSet(result)
            if (oldResult != null) {
                oldResult.exceptionOrNull()?.printStackTrace()
                return
            }
            lock.withLock {
                condition.signalAll()
            }
            continuations.forEach { it.resumeWith(result) }
        }

        final override fun awaitBlocking(): Result<T> {
            var result = completeResult.get()
            while (result == null) {
                lock.withLock {
                    condition.await(1, TimeUnit.MILLISECONDS)
                }
                result = completeResult.get()
            }
            return result
        }

        final override suspend fun await(): Result<T> {
            var result = completeResult.get()
            while (result == null) {
                 withTimeout(1) {
                    suspendCancellableCoroutine {
                        continuations.add(it)
                    }
                }
                result = completeResult.get()
            }
            return result
        }

        abstract fun run(worker: Worker)
    }

    private abstract inner class AbstractIterativeTask<S>(
        private val size: Int,
        protected val batchSize: Int
    ) : AbstractTask<S>() {
        val threadRanges = AtomicLongArray(workerCount + 1)

        init {
            threadRanges.set(workerCount, size.toLong())
            var start = 0
            val step = maxOf(size / workerCount, batchSize)
            for (i in 0 until workerCount) {
                var end = minOf(start + step, size)
                if (size - end < batchSize) {
                    end = size
                }
                threadRanges.set(i, encodeRange(start, end))
                start = end
            }
        }

        fun debugText(): String {
            val sb = StringBuilder()
            sb.append("Total: ${threadRanges.get(workerCount)}/${size}\n")
            for (i in 0 until workerCount) {
                val encoded = threadRanges.get(i)
                val start = decodeRangeStart(encoded)
                val end = decodeRangeEnd(encoded)
                sb.append("Worker $i: $start-$end\n")
            }
            return sb.toString()
        }

        fun encodeRange(start: Int, end: Int): Long {
            return (end.toLong() shl 32) or start.toLong()
        }

        fun decodeRangeStart(encoded: Long): Int {
            return (encoded and 0xFFFFFFFFL).toInt()
        }

        fun decodeRangeEnd(encoded: Long): Int {
            return (encoded ushr 32).toInt()
        }

        override fun run(worker: Worker) {
            var stealCount = 0
            var completeCount = 0
            while (true) {
                val encoded = threadRanges.get(worker.id)
                val start = decodeRangeStart(encoded)
                val end = decodeRangeEnd(encoded)
                assert(start <= end)

                if (start == end) {
                    val v = threadRanges.addAndGet(workerCount, -completeCount.toLong())
                    completeCount = 0
                    if (v < batchSize * 2 * workerCount) {
                        break
                    }
                    steal(worker)
                    if (++stealCount >= workerCount) {
                        Thread.yield()
                    }
                    continue
                }
                stealCount = 0

                var newStart = minOf(start + batchSize, end)
                if (end - newStart < batchSize) {
                    newStart = end
                }
                if (!threadRanges.compareAndSet(worker.id, encoded, encodeRange(newStart, end))) continue

                runBatch(worker, start, newStart)
                completeCount += newStart - start
            }
        }

        private fun steal(worker: Worker) {
            val victimId = worker.random.nextInt(workerCount)
            if (victimId == worker.id) return
            val encoded = threadRanges.get(victimId)
            val start = decodeRangeStart(encoded)
            val end = decodeRangeEnd(encoded)
            val remaining = end - start
            if (remaining < batchSize * 2) return
            val half = remaining / 2
            val newStart = start + half
            if (!threadRanges.compareAndSet(victimId, encoded, encodeRange(newStart, end))) return

            threadRanges.set(worker.id, encodeRange(start, newStart))
        }

        abstract fun runBatch(worker: Worker, start: Int, end: Int)
    }

    private inner class IterativeTask(
        size: Int,
        batchSize: Int,
        private val action: IterativeAction
    ) : AbstractIterativeTask<Unit>(size, batchSize) {
        override fun run(worker: Worker) {
            try {
                super.run(worker)
                if (threadRanges.get(workerCount) == 0L) {
                    complete(unitResult)
                }
            } catch (e: Exception) {
                completeFailure(Result.failure(e))
            }
        }

        override fun runBatch(worker: Worker, start: Int, end: Int) {
            action.run(start, end)
        }
    }

    private inner class ScopedIterativeTask<S>(
        size: Int,
        batchSize: Int,
        private val newScope: (Int) -> S,
        private val action: ScopedIterativeAction<S>
    ) : AbstractIterativeTask<List<S>>(size, batchSize) {
        private val scopes = List(workerCount) { newScope(it) }

        override fun run(worker: Worker) {
            try {
                super.run(worker)
                if (threadRanges.get(workerCount) == 0L) {
                    complete(Result.success(scopes))
                }
            } catch (e: Exception) {
                completeFailure(Result.failure(e))
            }
        }

        override fun runBatch(worker: Worker, start: Int, end: Int) {
            with(action) {
                scopes[worker.id].run(start, end)
            }
        }
    }

//    private inner class RecursiveTask<S, R>(
//        private val scope: S,
//        private val action: RecursiveAction<S, R>
//    ) : AbstractTask<R>() {
//        override fun run(worker: Worker) {
//            try {
//                val result = action.scope.run()
//                complete { Result.success(result) }
//            } catch (e: Exception) {
//                completeFailure(Result.failure(e))
//            }
//        }
//    }
}