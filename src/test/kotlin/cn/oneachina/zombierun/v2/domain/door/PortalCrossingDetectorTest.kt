package cn.oneachina.zombierun.v2.domain.door

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals

/**
 * 门洞：X 轴穿越，plane=100.0，横向 Z ∈ [10, 20]，Y ∈ [64, 66]。
 * front=positive 表示玩家从 X<100 一侧走向 X>100 一侧。
 */
class PortalCrossingDetectorTest {

    private fun portal() = Portal(
        axis = PortalAxis.X,
        front = PortalFront.POSITIVE,
        planeCoordinate = 100.0,
        transverseMin = 10.0,
        transverseMax = 20.0,
        yMin = 64.0,
        yMax = 66.0,
    )

    @Test
    fun `normal forward crossing is detected`() {
        val crossing = PortalCrossingDetector.crossing(
            from = Vec3(99.0, 65.0, 15.0),
            to = Vec3(101.0, 65.0, 15.0),
            portal = portal(),
        )
        assertNotNull(crossing)
        assertEquals(100.0, crossing.point.x, 1e-9)
        assertEquals(15.0, crossing.point.z, 1e-9)
    }

    @Test
    fun `large step that crosses plane is NOT rejected`() {
        // v1 会因 axisDelta > 2.5 放弃；v2 必须仍能检测
        val crossing = PortalCrossingDetector.crossing(
            from = Vec3(93.0, 65.0, 15.0),
            to = Vec3(106.0, 65.0, 15.0),
            portal = portal(),
        )
        assertNotNull(crossing)
    }

    @Test
    fun `diagonal crossing validates intersection point not endpoint`() {
        // 终点横向已经偏离门洞，但线段交点在门洞内
        val crossing = PortalCrossingDetector.crossing(
            from = Vec3(99.0, 65.0, 15.0),
            to = Vec3(103.0, 65.0, 26.0),
            portal = portal(),
        )
        assertNotNull(crossing)
        assertEquals(17.75, crossing.point.z, 1e-9)
    }

    @Test
    fun `backwards movement is rejected`() {
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(101.0, 65.0, 15.0),
                to = Vec3(99.0, 65.0, 15.0),
                portal = portal(),
            )
        )
    }

    @Test
    fun `movement fully behind or fully front is rejected`() {
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(98.0, 65.0, 15.0),
                to = Vec3(99.0, 65.0, 15.0),
                portal = portal(),
            )
        )
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(101.0, 65.0, 15.0),
                to = Vec3(102.0, 65.0, 15.0),
                portal = portal(),
            )
        )
    }

    @Test
    fun `crossing outside transverse range is rejected`() {
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(99.0, 65.0, 21.0),
                to = Vec3(101.0, 65.0, 21.0),
                portal = portal(),
            )
        )
    }

    @Test
    fun `crossing above vertical range is rejected`() {
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(99.0, 67.5, 15.0),
                to = Vec3(101.0, 67.5, 15.0),
                portal = portal(),
            )
        )
    }

    @Test
    fun `negative front portal uses negative axis direction`() {
        val negative = portal().copy(front = PortalFront.NEGATIVE)
        val crossing = PortalCrossingDetector.crossing(
            from = Vec3(101.0, 65.0, 15.0),
            to = Vec3(99.0, 65.0, 15.0),
            portal = negative,
        )
        assertNotNull(crossing)

        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(99.0, 65.0, 15.0),
                to = Vec3(101.0, 65.0, 15.0),
                portal = negative,
            )
        )
    }

    @Test
    fun `fallback projection is tight`() {
        val p = portal()
        // 正前方投影内：通过
        PortalCrossingDetector.isInFrontProjection(Vec3(101.0, 65.0, 15.0), p).let { kotlin.test.assertTrue(it) }
        // 偏离门洞 2 格：不通过（v1 ±5 会误判）
        PortalCrossingDetector.isInFrontProjection(Vec3(101.0, 65.0, 23.0), p).let { kotlin.test.assertFalse(it) }
        // 门前侧 2 格高：不通过
        PortalCrossingDetector.isInFrontProjection(Vec3(101.0, 69.0, 15.0), p).let { kotlin.test.assertFalse(it) }
    }
}
