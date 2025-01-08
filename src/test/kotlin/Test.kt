import dev.luna5ama.gltest.concurrent.LWWSP
import dev.luna5ama.gltest.concurrent.ParallelIntIntrosort
import it.unimi.dsi.fastutil.ints.IntArrays
import kotlinx.coroutines.*
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.RecursiveAction
import kotlin.system.measureNanoTime

val lwwsp = LWWSP(32) {
    it.isDaemon = true
}
val dispatcherC = newFixedThreadPoolContext(32, "ktCoroutines")
val data = IntArray(1024 * 1024 * 32)
val copyData = IntArray(data.size)

fun benchmark(dispatcher: CoroutineDispatcher, task: CoroutineScope.(IntArray) -> Unit) {
    val times = LongArray(10) {
        data.copyInto(copyData)
        val time =      measureNanoTime {
            runBlocking {
                launch(dispatcher) {
                    task(copyData)
                }
            }
        }
        Thread.sleep(100)
        time
    }
    println("%s\n%s\n%.2f ms\n".format(dispatcher, task, times.average() / 1e6))
}

fun benchmark(task: () -> ForkJoinTask<*>) {
    val times = LongArray(10) {
        data.copyInto(copyData)
        val time = measureNanoTime {
            task().fork().join()
        }
        Thread.sleep(100)
        time
    }
    println("ForkJoinPool\n%s\n%.2f ms\n".format(task, times.average() / 1e6))
}

fun warmup(dispatcher: CoroutineDispatcher, task: CoroutineScope.(IntArray) -> Unit) {
    repeat(10) {
        data.copyInto(copyData)
        runBlocking {
            launch(dispatcher) {
                task(copyData)
            }
        }
        Thread.sleep(100)
    }
}
fun warmup(task: () -> ForkJoinTask<*>) {
    repeat(10) {
        data.copyInto(copyData)
        task().fork().join()
        Thread.sleep(100)
    }
}

fun CoroutineScope.parallelIntroSort(data: IntArray) {
    ParallelIntIntrosort(this).iSort(data)
}

fun CoroutineScope.parallelQuickSort(data: IntArray) {
    IntArrays.parallelQuickSort(data)
}

fun CoroutineScope.parallelRadixSort(data: IntArray) {
    IntArrays.parallelRadixSort(data)
}

fun CoroutineScope.stuffRecursive(data: IntArray, start: Int, end: Int) {
    val n = end - start
    if (n <= 1024) {
        for (i in start until end) {
            for (j in start until end) {
                data[i] = data[i] xor data[j]
            }
        }
        return
    }
    val mid = ((start + end).toLong() / 2).toInt()
    launch {
        stuffRecursive(data, start, mid)
    }
    launch {
        stuffRecursive(data, mid, end)
    }
}

fun CoroutineScope.stuff(data: IntArray) {
    stuffRecursive(data, 0, data.size)
}

class Stuff(private val data0: IntArray, private val start: Int, private val end: Int) : RecursiveAction() {
    override fun compute() {
        val n = end - start
        if (n <= 1024) {
            for (i in start until end) {
                for (j in start until end) {
                    data0[i] = data0[i] xor data0[j]
                }
            }
            return
        }
        val mid = ((start + end).toLong() / 2).toInt()
        val left = Stuff(data0, start, mid)
        val right = Stuff(data0, mid, end)
        left.fork()
        right.compute()
        left.join()
    }
}

fun stuff() = Stuff(copyData, 0, data.size)

fun main() {
    val random = java.util.Random()
    for (i in data.indices) {
        data[i] = random.nextInt()
    }

//    test()
//    println("Test Passed")

    println("Warming up")
    warmup(lwwsp, CoroutineScope::stuff)
    warmup(Dispatchers.Default, CoroutineScope::stuff)
    warmup(dispatcherC, CoroutineScope::stuff)
    warmup(::stuff)
    println("Warmup Complete")

    println("Benchmarking")
    benchmark(lwwsp, CoroutineScope::stuff)
    benchmark(Dispatchers.Default, CoroutineScope::stuff)
    benchmark(dispatcherC, CoroutineScope::stuff)
    benchmark(::stuff)
}