import dev.luna5ama.gltest.concurrent.LWWSP
import kotlinx.coroutines.*
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.RecursiveAction
import kotlin.system.measureNanoTime
import kotlin.math.exp

val lwwsp = LWWSP(32) {
    it.isDaemon = true
}
val dispatcherC = newFixedThreadPoolContext(32, "ktCoroutines")

// Gaussian kernel parameters
const val KERNEL_SIZE = 255
const val KERNEL_SIGMA = 40.0f
val gaussianKernel: FloatArray = generateGaussianKernel(KERNEL_SIZE, KERNEL_SIGMA)

fun generateGaussianKernel(size: Int, sigma: Float): FloatArray {
    val kernel = FloatArray(size)
    val center = size / 2.0f
    val sigmaSq = sigma * sigma
    var sum = 0.0f

    for (i in 0 until size) {
        val x = (i - center)
        val value = exp(-(x * x) / (2.0f * sigmaSq)).toFloat()
        kernel[i] = value
        sum += value
    }

    // Normalize
    for (i in 0 until size) {
        kernel[i] /= sum
    }

    return kernel
}

val inputData = FloatArray(1024 * 1024 * 64)
val outputData = FloatArray(inputData.size)

fun benchmark(dispatcher: CoroutineDispatcher, task: suspend CoroutineScope.() -> Unit) {
    val times = LongArray(10) {
        val time = measureNanoTime {
            runBlocking {
                launch(dispatcher) {
                    task()
                }
            }
        }
        Thread.sleep(100)
        time
    }
    println("%s\n%.2f ms\n".format(dispatcher, times.average() / 1e6))
}

fun benchmark(task: () -> ForkJoinTask<*>) {
    val times = LongArray(10) {
        val time = measureNanoTime {
            task().fork().join()
        }
        Thread.sleep(100)
        time
    }
    println("ForkJoinPool\n%.2f ms\n".format(times.average() / 1e6))
}

fun warmup(dispatcher: CoroutineDispatcher, task: suspend CoroutineScope.() -> Unit) {
    repeat(10) {
        runBlocking {
            launch(dispatcher) {
                task()
            }
        }
        Thread.sleep(100)
    }
}

fun warmup(task: () -> ForkJoinTask<*>) {
    repeat(10) {
        task().fork().join()
        Thread.sleep(100)
    }
}

fun applyGaussianKernelScalar(start: Int, end: Int) {
    for (i in start until end) {
        var sum = 0.0f
        val kernelOffset = KERNEL_SIZE / 2

        for (k in 0 until KERNEL_SIZE) {
            val srcIdx = i + k - kernelOffset
            if (srcIdx >= 0 && srcIdx < inputData.size) {
                sum += inputData[srcIdx] * gaussianKernel[k]
            }
        }

        outputData[i] = sum
    }
}

fun applyGaussianKernelCoroutine(start: Int, end: Int) {
    for (i in start until end) {
        var sum = 0.0f
        val kernelOffset = KERNEL_SIZE / 2

        for (k in 0 until KERNEL_SIZE) {
            val srcIdx = i + k - kernelOffset
            if (srcIdx >= 0 && srcIdx < inputData.size) {
                sum += inputData[srcIdx] * gaussianKernel[k]
            }
        }

        outputData[i] = sum
    }
}

fun CoroutineScope.stuffRecursive(start: Int, end: Int) {
    val n = end - start
    if (n <= 64 * 1024) {
        applyGaussianKernelCoroutine(start, end)
        return
    }
    val mid = ((start + end).toLong() / 2).toInt()
    launch {
        stuffRecursive(start, mid)
    }
    launch {
        stuffRecursive(mid, end)
    }
}

fun CoroutineScope.stuff() {
    stuffRecursive(0, inputData.size)
}

fun parallelIterativeTask() {
    runBlocking {
        val task = LWWSP.iterativeTask(
            inputData.size,
            batchSize = 4 * 1024,
            action = { start, end ->
                applyGaussianKernelScalar(start, end)
            }
        )
        task.submit(lwwsp).await()
    }
}

fun warmupIterative() {
    repeat(10) {
        parallelIterativeTask()
        Thread.sleep(100)
    }
}

fun benchmarkIterative() {
    val times = LongArray(10) {
        val time = measureNanoTime {
            parallelIterativeTask()
        }
        Thread.sleep(100)
        time
    }
    println("LWWSP Iterative Task\n%.2f ms\n".format(times.average() / 1e6))
}

class Stuff(private val start: Int, private val end: Int) : RecursiveAction() {
    override fun compute() {
        val n = end - start
        if (n <= 64 * 1024) {
            applyGaussianKernelScalar(start, end)
            return
        }
        val mid = ((start + end).toLong() / 2).toInt()
        val left = Stuff(start, mid)
        val right = Stuff(mid, end)
        left.fork()
        right.compute()
        left.join()
    }
}

fun stuff() = Stuff(0, inputData.size)

fun main() {
    val random = java.util.Random()
    for (i in inputData.indices) {
        inputData[i] = random.nextFloat()
    }

    println("Warming up")
    warmup(lwwsp, CoroutineScope::stuff)
    warmup(Dispatchers.Default, CoroutineScope::stuff)
    warmup(dispatcherC, CoroutineScope::stuff)
    warmup(::stuff)
    warmupIterative()
    println("Warmup Complete")

    println("Benchmarking")
    benchmark(lwwsp, CoroutineScope::stuff)
    benchmark(Dispatchers.Default, CoroutineScope::stuff)
    benchmark(dispatcherC, CoroutineScope::stuff)
    benchmark(::stuff)
    benchmarkIterative()
}