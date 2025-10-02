import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

fun main() {
    val path = Path("shaderasm")
    path.listDirectoryEntries("*.txt").forEach { file ->
        val parts = mutableListOf<MutableList<String>>()
        var currPart = mutableListOf<String>()
        val bytes = file.readBytes().filterNot { it == 0.toByte() }.toByteArray()
        bytes.decodeToString().lineSequence().forEach {
            if (it == "END") {
                parts.add(currPart)
                currPart = mutableListOf()
                return@forEach
            }
            currPart.add(it)
        }

        println("----------------------------------------------------")
        println("[${file.name}]")

        val excludedKeys = setOf(
            "OPTION",
            "GROUP_SIZE",
            "PARAM",
            "TEMP",
            "LONG",
            "SHORT",
        )

        val instCount =  parts.asSequence()
            .flatMap { part ->
                part.asSequence()
                    .dropWhile { !it.startsWith("OPTION") }
            }
            .map { it.removeSuffix(";") }
            .groupingBy { it.substringBefore(' ') }
            .eachCount()
            .filterKeys { it !in excludedKeys }
            .toList()
            .sortedBy { it.first }
            .forEach {
                println("${it.first}: ${it.second}")
            }
    }
}