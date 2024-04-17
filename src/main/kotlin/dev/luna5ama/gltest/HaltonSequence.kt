package dev.luna5ama.gltest

class HaltonSequence(val base: Int) {
    private var n = 0
    private var d = 1

    fun skip(n: Int): HaltonSequence {
        repeat(n) {
            nextDouble()
        }
        return this
    }

    fun nextDouble(): Double {
        val x = d - n
        if (x == 1) {
            n = 1
            d *= base
        } else {
            var y = d / base
            while (x <= y){
                y /= base
            }
            n = (base + 1) * y - x
        }
        return n / d.toDouble()
    }

    fun nextFloat(): Float {
        return nextDouble().toFloat()
    }
}