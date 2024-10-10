import org.joml.Vector3f

fun stuff(a: Vector3f, b: Vector3f): Vector3f {
    val bDotA = b.dot(a)
    println(bDotA)
    val projAB = a.mul(bDotA, Vector3f())
    println(projAB)
    val tanB = b.sub(projAB, Vector3f()).normalize()
    return tanB
}

fun main() {
    System.setProperty("joml.format", "false")
    val a = Vector3f(0f, 1f, 1f).normalize()
    val b = Vector3f(1f, 1f, 0f).normalize()
    println(a)
    println(b)
    println()
    val sss = stuff(a, b)
    println()
    println(sss)
}