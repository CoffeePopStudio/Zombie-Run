package cn.oneachina.zombierun.v2.domain.door

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        PortalCrossingDetector.isInFrontProjection(Vec3(101.0, 65.0, 15.0), p).let { assertTrue(it) }
        // 偏离门洞 2 格：不通过（v1 ±5 会误判）
        PortalCrossingDetector.isInFrontProjection(Vec3(101.0, 65.0, 23.0), p).let { assertFalse(it) }
        // 门前侧 2 格高：不通过
        PortalCrossingDetector.isInFrontProjection(Vec3(101.0, 69.0, 15.0), p).let { assertFalse(it) }
    }

    // ---------- Y 轴水平门（向上/向下穿过地板/天花板） ----------

    private fun yPortal(front: PortalFront = PortalFront.POSITIVE) = Portal(
        axis = PortalAxis.Y,
        front = front,
        planeCoordinate = 64.0,
        transverseMin = 10.0,
        transverseMax = 20.0,
        yMin = 30.0,
        yMax = 40.0,
    )

    @Test
    fun `y axis upward crossing is detected`() {
        // front=positive：Y 增大方向为前（向上）；从 y=63 到 y=65 穿过 y=64 平面
        val crossing = PortalCrossingDetector.crossing(
            from = Vec3(15.0, 63.0, 35.0),
            to = Vec3(15.0, 65.0, 35.0),
            portal = yPortal(),
        )
        assertNotNull(crossing)
        assertEquals(64.0, crossing.point.y, 1e-9)
        assertEquals(15.0, crossing.point.x, 1e-9)
        assertEquals(35.0, crossing.point.z, 1e-9)
    }

    @Test
    fun `y axis large vertical step is detected`() {
        val crossing = PortalCrossingDetector.crossing(
            from = Vec3(15.0, 50.0, 35.0),
            to = Vec3(15.0, 80.0, 35.0),
            portal = yPortal(),
        )
        assertNotNull(crossing)
    }

    @Test
    fun `y axis negative front means downward crossing`() {
        val down = yPortal(front = PortalFront.NEGATIVE)
        val crossing = PortalCrossingDetector.crossing(
            from = Vec3(15.0, 65.0, 35.0),
            to = Vec3(15.0, 63.0, 35.0),
            portal = down,
        )
        assertNotNull(crossing)
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(15.0, 63.0, 35.0),
                to = Vec3(15.0, 65.0, 35.0),
                portal = down,
            )
        )
    }

    @Test
    fun `y axis crossing outside horizontal hole is rejected`() {
        // x 超出 transverse（10..20），z 在 vertical（30..40）
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(5.0, 63.0, 35.0),
                to = Vec3(5.0, 65.0, 35.0),
                portal = yPortal(),
            )
        )
        // z 超出 vertical（30..40）
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(15.0, 63.0, 25.0),
                to = Vec3(15.0, 65.0, 25.0),
                portal = yPortal(),
            )
        )
    }

    @Test
    fun `y axis fallback projection accepts only inside horizontal hole`() {
        val p = yPortal()
        PortalCrossingDetector.isInFrontProjection(Vec3(15.0, 65.0, 35.0), p).let { assertTrue(it) }
        PortalCrossingDetector.isInFrontProjection(Vec3(15.0, 65.0, 45.0), p).let { assertFalse(it) }
        PortalCrossingDetector.isInFrontProjection(Vec3(15.0, 63.0, 35.0), p).let { assertFalse(it) }
    }

    // ---------- 极端情况 ----------

    @Test
    fun `crossing exactly on plane boundary is detected`() {
        val crossing = PortalCrossingDetector.crossing(
            from = Vec3(99.9, 65.0, 15.0),
            to = Vec3(100.0, 65.0, 15.0),
            portal = portal(),
        )
        assertNotNull(crossing)
    }

    @Test
    fun `crossing at tolerance boundary accepts inside and rejects outside`() {
        // transverse 上限 20 + 默认容差 0.6 = 20.6 仍算穿过
        assertNotNull(
            PortalCrossingDetector.crossing(
                from = Vec3(99.0, 65.0, 20.6),
                to = Vec3(101.0, 65.0, 20.6),
                portal = portal(),
            )
        )
        // 20.7 超出容差
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(99.0, 65.0, 20.7),
                to = Vec3(101.0, 65.0, 20.7),
                portal = portal(),
            )
        )
        // 垂直上限 66 + 0.5 = 66.5 仍算穿过
        assertNotNull(
            PortalCrossingDetector.crossing(
                from = Vec3(99.0, 66.5, 15.0),
                to = Vec3(101.0, 66.5, 15.0),
                portal = portal(),
            )
        )
    }

    @Test
    fun `extreme teleport-like movement across door is still detected`() {
        val crossing = PortalCrossingDetector.crossing(
            from = Vec3(-10000.0, 65.0, 15.0),
            to = Vec3(10000.0, 65.0, 15.0),
            portal = portal(),
        )
        assertNotNull(crossing)
    }

    @Test
    fun `zero axis delta movement is rejected even if sides differ by exact boundary`() {
        // from/to 都在 plane 上（side=FRONT），没有轴位移，不应算穿越
        assertNull(
            PortalCrossingDetector.crossing(
                from = Vec3(100.0, 65.0, 15.0),
                to = Vec3(100.0, 65.0, 15.0),
                portal = portal(),
            )
        )
    }
}
