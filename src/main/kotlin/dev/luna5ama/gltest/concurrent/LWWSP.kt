package dev.luna5ama.gltest.concurrent

import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

class LWWSP(val workerCount: Int, val threadConfigure: (Thread) -> Unit = {}) : CoroutineDispatcher() {
    private val workerCountMinus1 = workerCount - 1

    private val workers = List(workerCount) { Worker(this, it).also(threadConfigure) }
    private val activeWorkerCount = AtomicInteger()

    private val isAccepting = AtomicBoolean(true)
    private val isRunning = AtomicBoolean(true)
    private val random = Random()

    init {
        workers.forEach { it.start() }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val task = RecursiveTask { block.run() }
        task.submit()
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

    private fun checkState() {
        check(isAccepting.get()) { "Pool is not accepting new tasks" }
    }

    private class TaskQueue {
        private val queueExclusive = ObjectArrayFIFOQueue<AbstractTask<*>>()
        private val queueShared = ObjectArrayFIFOQueue<AbstractTask<*>>()
        private val lock = ReentrantLock()
        private val condition = lock.newCondition()

        private fun ObjectArrayFIFOQueue<AbstractTask<*>>.tryDequeue(): AbstractTask<*>? {
            return if (isEmpty) null else dequeue()
        }

        val size get() = queueShared.size() + queueExclusive.size()

        fun enqueueShared(tasks: Collection<AbstractTask<*>>) {
            lock.withLock {
                tasks.forEach(queueShared::enqueue)
                condition.signalAll()
            }
        }

        fun enqueueShared(task: AbstractTask<*>) {
            lock.withLock {
                queueShared.enqueue(task)
                condition.signal()
            }
        }

        fun enqueueExclusive(task: AbstractTask<*>) {
            lock.withLock {
                queueExclusive.enqueue(task)
                condition.signal()
            }
        }

        fun dequeue(): AbstractTask<*> {
            var task: AbstractTask<*>? = null
            while (task == null) {
                lock.lock()
                try {
                    task = queueExclusive.tryDequeue()
                    if (task == null) {
                        task = queueShared.tryDequeue()
                    }
                    if (task == null) {
                        condition.await()
                    }
                } finally {
                    lock.unlock()
                }
            }
            return task
        }

        fun dequeue(time: Long, unit: TimeUnit): AbstractTask<*>? {
            var task: AbstractTask<*>?
            lock.lock()
            try {
                task = queueExclusive.tryDequeue()
                if (task == null) {
                    task = queueShared.tryDequeue()
                }
                if (task == null) {
                    condition.await(time, unit)
                }
            } finally {
                lock.unlock()
            }
            return task
        }

        fun drainToShared(dst: MutableCollection<AbstractTask<*>>, limit: Int = Int.MAX_VALUE) {
            lock.withLock {
                var count = 0
                while (count < limit) {
                    val task = queueShared.tryDequeue() ?: break
                    dst.add(task)
                    count++
                }
            }
        }
    }

    private class Worker(val pool: LWWSP, val id: Int) : Thread("LWWSP-$id") {
        val taskQueue = TaskQueue()
        private val drainTemp = ObjectArrayList<AbstractTask<*>>()
        private val random = Random()

        fun stealVictimID(): Int {
            val victimIDOffset = random.nextInt(pool.workerCountMinus1)
            val victimId = (id + 1 + victimIDOffset) % pool.workerCount
            assert(victimId != id)
            return victimId
        }

        private fun steal() {
            val victimIDBaseOffset = random.nextInt(pool.workerCountMinus1)

            for (i in 0..<pool.workerCountMinus1) {
                val victimIDOffset = (victimIDBaseOffset + i) % (pool.workerCountMinus1)
                val victimID = (id + 1 + victimIDOffset) % pool.workerCount
                val victim = pool.workers[victimID]
                val limit = victim.taskQueue.size / 2
                if (limit <= 0) continue
                drainTemp.ensureCapacity(limit)
                victim.taskQueue.drainToShared(drainTemp, limit)
                taskQueue.enqueueShared(drainTemp)
                drainTemp.clear()
                break
            }
        }

        override fun run() {
            pool.activeWorkerCount.incrementAndGet()
            while (pool.isRunning.get()) {
                val cTask = try {
                    taskQueue.dequeue()
                } catch (e: InterruptedException) {
                    null
                }
                if (taskQueue.size >= 1) {
                    pool.workers[stealVictimID()].interrupt()
                }
                if (cTask == null) {
                    steal()
                    continue
                }
                cTask.run(this)
            }
            pool.activeWorkerCount.decrementAndGet()
        }
    }

    fun interface IterativeAction {
        fun run(start: Int, end: Int)
    }

    fun interface ScopedIterativeAction<S> {
        fun S.run(start: Int, end: Int)
    }

    fun interface RecursiveAction<R> {
        fun run(): R
    }

    sealed interface Task<T> {
        val isCompleted: Boolean
        suspend fun await(pollingTimeout: Long = 1L): Result<T>
    }

    private sealed class AbstractTask<T> : Task<T> {
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

        final override suspend fun await(pollingTimeout: Long): Result<T> {
            var result = completeResult.get()
            while (result == null) {
                withTimeout(pollingTimeout) {
                    suspendCancellableCoroutine {
                        result = completeResult.get()
                        if (result != null) {
                            it.resumeWith(result)
                        } else {
                            continuations.add(it)
                        }
                    }
                }
            }
            return result
        }

        abstract fun run(worker: Worker)

        abstract fun submit()
    }

    private inner class RecursiveTask<R>(private val action: RecursiveAction<R>) : AbstractTask<R>() {
        override fun run(worker: Worker) {
            try {
                val result = action.run()
                complete(Result.success(result))
            } catch (e: Exception) {
                completeFailure(Result.failure(e))
            }
        }

        override fun submit() {
            checkState()
            val currentThread = Thread.currentThread()
            val worker = if (currentThread is Worker) {
                currentThread
            } else {
                val workerID = random.nextInt(workerCount)
                workers[workerID]
            }
            worker.taskQueue.enqueueShared(this)
        }
    }

    private inner class ScopedIterativeTask<S>(
        private val size: Int,
        protected val batchSize: Int,
        private val newScope: (Int) -> S,
        private val action: ScopedIterativeAction<S>
    ) : AbstractTask<List<S>>() {
        val threadRanges = AtomicLongArray(workerCount + 1)
        private val scopes = List(workerCount) { newScope(it) }

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

        private fun encodeRange(start: Int, end: Int): Long {
            return (end.toLong() shl 32) or start.toLong()
        }

        private fun decodeRangeStart(encoded: Long): Int {
            return (encoded and 0xFFFFFFFFL).toInt()
        }

        private fun decodeRangeEnd(encoded: Long): Int {
            return (encoded ushr 32).toInt()
        }

        private fun steal(worker: Worker) {
            val victimId = worker.stealVictimID()
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

        private fun runBatch(worker: Worker, start: Int, end: Int) {
            with(action) {
                scopes[worker.id].run(start, end)
            }
        }

        override fun run(worker: Worker) {
            try {
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

                if (threadRanges.get(workerCount) == 0L) {
                    complete(Result.success(scopes))
                }
            } catch (e: Exception) {
                completeFailure(Result.failure(e))
            }
        }

        override fun submit() {
            checkState()
            workers.forEach {
                it.taskQueue.enqueueExclusive(this)
            }
        }

        override fun toString(): String {
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
    }

    private class IterativeTask(private val scoped: ScopedIterativeTask<Unit>) : Task<Unit> {
        override val isCompleted by scoped::isCompleted

        override suspend fun await(pollingTimeout: Long): Result<Unit> {
            return scoped.await(pollingTimeout).map { }
        }
    }

    sealed class TaskBuilder<T> {
        private var submitted = false

        fun submit(): Task<T> {
            val currentThread = Thread.currentThread()
            check(currentThread is Worker) { "Task must be submitted from worker thread" }
            return submit(currentThread.pool)
        }

        fun submit(pool: LWWSP): Task<T> {
            return synchronized(this) {
                check(!submitted) { "Task already submitted" }
                submitted = true
                submitImpl(pool)
            }
        }

        protected abstract fun submitImpl(pool: LWWSP): Task<T>
    }

    companion object {
        private class ScopedIterativeTaskBuilder<S>(
            private val size: Int,
            private val batchSize: Int,
            private val newScope: (workerID: Int) -> S,
            private val action: ScopedIterativeAction<S>
        ) : TaskBuilder<List<S>>() {
            override fun submitImpl(pool: LWWSP): Task<List<S>> {
                val task = pool.ScopedIterativeTask(size, batchSize, newScope, action)
                task.submit()
                return task
            }
        }

        private class IterativeTaskBuilder(
            private val size: Int,
            private val batchSize: Int,
            private val action: IterativeAction
        ) : TaskBuilder<Unit>() {
            override fun submitImpl(pool: LWWSP): Task<Unit> {
                val task = pool.ScopedIterativeTask(size, batchSize, { }, { start, end -> action.run(start, end) })
                task.submit()
                return IterativeTask(task)
            }
        }

        private class RecursiveTaskBuilder<R>(
            private val action: RecursiveAction<R>
        ) : TaskBuilder<R>() {
            override fun submitImpl(pool: LWWSP): Task<R> {
                val task = pool.RecursiveTask(action)
                task.submit()
                return task
            }
        }

        inline fun <S> iterativeTask(
            size: Int,
            batchSize: Int,
            crossinline newScope: () -> S,
            action: ScopedIterativeAction<S>
        ): TaskBuilder<List<S>> {
            return iterativeTask(size, batchSize, { workerID: Int -> newScope() }, action)
        }

        fun <S> iterativeTask(
            size: Int,
            batchSize: Int,
            newScope: (workerID: Int) -> S,
            action: ScopedIterativeAction<S>
        ): TaskBuilder<List<S>> {
            return ScopedIterativeTaskBuilder(size, batchSize, newScope, action)
        }

        fun iterativeTask(
            size: Int,
            batchSize: Int,
            action: IterativeAction
        ): TaskBuilder<Unit> {
            return IterativeTaskBuilder(size, batchSize, action)
        }
    }
}