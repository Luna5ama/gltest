//import org.junit.jupiter.api.Test
//import terrain.CascadedShadowMap
//import kotlin.math.sqrt
//import kotlin.test.assertEquals
//
//class CSMTest {
//    val epsilon = 0.0001
//
//    @Test
//    fun testCalc() {
//        var s = 0.5
//        var c = sqrt(s)
//        assertEquals(2.02754746876, CascadedShadowMap.calc(0.1, 0.05, 192.0, c, 5), epsilon)
//        assertEquals(10.4415245833, CascadedShadowMap.calc(0.5, 0.05, 192.0, c, 5), epsilon)
//    }
//
//    @Test
//    fun testCalcTwo() {
//        var s = 0.5
//        var c = sqrt(s)
//        for (i in 0..100) {
//            val x1 = i.toDouble() / 100.0
//            val z = CascadedShadowMap.calc(x1, 0.0, 1.0, c, 1)
//            val x2 = CascadedShadowMap.calcInv(z, 0.0, 1.0, c, 1)
//            assertEquals(x1, x2, epsilon)
//
//        }
//    }
//}