package cn.oneachina.zombierun.v2.domain.door

import kotlin.test.Test
import kotlin.test.assertTrue

class PortalCrossingPerformanceTest {

    @Test
    fun `twenty players across one hundred doors movement stays fast`() {
        val doors = (0 until 100).map { i ->
            Portal(
                axis = PortalAxis.X,
                front = PortalFront.POSITIVE,
                planeCoordinate = i * 10.0,
                transverseMin = -2.0,
                transverseMax = 2.0,
                yMin = 62.0,
                yMax = 66.0,
            )
        }

        val start = System.nanoTime()
        var crossings = 0
        repeat(20) { player ->
            var from = Vec3(player * 0.5 - 1.0, 64.0, 0.0)
            doors.forEach { portal ->
                repeat(20) {
                    val to = from + Vec3(0.25, 0.0, 0.0)
                    if (PortalCrossingDetector.crossing(from, to, portal) != null) crossings++
                    from = to
                }
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        // 40,000 次线段检测应在亚秒内完成；宽松阈值避免 CI 抖动。
        assertTrue(crossings >= 0)
        assertTrue(elapsedMs < 2_000.0, "portal detection too slow: ${elapsedMs}ms")
    }
}