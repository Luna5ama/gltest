import it.unimi.dsi.fastutil.Hash
import it.unimi.dsi.fastutil.objects.Object2LongOpenCustomHashMap
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import java.io.File
import java.nio.channels.FileChannel

fun main() {
    val files = listOf(
        File("C:\\CodeShit\\gltest\\run\\dumpForest\\terrain_vbo_0.bin"),
        File("C:\\CodeShit\\gltest\\run\\dumpForest\\terrain_vbo_1.bin"),
        File("C:\\CodeShit\\gltest\\run\\dumpForest\\terrain_vbo_2.bin"),
        File("C:\\CodeShit\\gltest\\run\\dumpForest\\terrain_vbo_3.bin")
    )
    val bins = Object2LongOpenCustomHashMap<ByteArray>(object : Hash.Strategy<ByteArray> {
        override fun equals(p0: ByteArray, p1: ByteArray): Boolean {
            return p0.contentEquals(p1)
        }

        override fun hashCode(p0: ByteArray): Int {
            return p0.contentHashCode()
        }
    })

    files.map { file ->
        Thread {
            val fileLength = file.length()
            FileChannel.open(file.toPath()).use {
                var fileOffset = 0
                val mapped = it.map(FileChannel.MapMode.READ_ONLY, fileOffset.toLong(), fileLength)
                while (fileOffset < fileLength) {
                    val array = ByteArray(80)
                    mapped.get(fileOffset, array)

                    bins.compute(array) { _, v ->
                        if (v == null) {
                            1
                        } else {
                            v + 1
                        }
                    }
                    fileOffset += 80
                }
            }
        }
    }
}