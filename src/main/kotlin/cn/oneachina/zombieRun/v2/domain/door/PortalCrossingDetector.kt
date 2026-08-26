package cn.oneachina.zombierun.v2.domain.door

import kotlin.math.abs

/**
 * 线段-平面相交式穿越检测（纯 Kotlin，可单元测试）。
 *
 * 与 v1 的关键区别：
 * 1. 不因单次位移超过 2.5 格而放弃检测
 * 2. 检查线段与门平面的交点是否落在门洞范围内，而不是只检查移动终点
 * 3. 传送由上层通过 PlayerTeleportEvent 显式排除，不在这里靠距离猜
 */
object PortalCrossingDetector {

    data class Crossing(
        val point: Vec3,
        val fromSide: Side,
        val toSide: Side,
    )

    /**
     * 判断 from→to 的有向移动是否以 portal.front 方向穿过门平面。
     *
     * @param transverseTolerance 交点在门洞横向范围外的允许误差
     * @param verticalTolerance 交点在门洞 Y 范围外的允许误差
     */
    fun crossing(
        from: Vec3,
        to: Vec3,
        portal: Portal,
        transverseTolerance: Double = DEFAULT_TRANSVERSE_TOLERANCE,
        verticalTolerance: Double = DEFAULT_VERTICAL_TOLERANCE,
    ): Crossing? {
        val fromSide = portal.side(from)
        val toSide = portal.side(to)
        if (fromSide != Side.BEHIND || toSide != Side.FRONT) return null

        val fromAxis = portal.axisValue(from)
        val toAxis = portal.axisValue(to)
        val delta = toAxis - fromAxis
        if (abs(delta) < EPSILON) return null

        val t = (portal.planeCoordinate - fromAxis) / delta
        if (t < 0.0 || t > 1.0) return null

        val point = from + ((to - from) * t)

        val transverse = portal.transverseValue(point)
        if (transverse < portal.transverseMin - transverseTolerance ||
            transverse > portal.transverseMax + transverseTolerance
        ) {
            return null
        }

        val vertical = portal.verticalValue(point)
        if (vertical < portal.yMin - verticalTolerance || vertical > portal.yMax + verticalTolerance) {
            return null
        }

        return Crossing(point, fromSide, toSide)
    }

    /**
     * 严格兜底：玩家当前位于门前侧，且位置在门洞投影范围内。
     * 默认容差收紧到 1.0（v1 为 5.0）。
     */
    fun isInFrontProjection(
        position: Vec3,
        portal: Portal,
        tolerance: Double = FALLBACK_TOLERANCE,
    ): Boolean {
        if (portal.side(position) != Side.FRONT) return false
        val transverse = portal.transverseValue(position)
        if (transverse < portal.transverseMin - tolerance || transverse > portal.transverseMax + tolerance) {
            return false
        }
        val vertical = portal.verticalValue(position)
        return vertical >= portal.yMin - tolerance && vertical <= portal.yMax + tolerance
    }

    const val DEFAULT_TRANSVERSE_TOLERANCE = 0.6
    const val DEFAULT_VERTICAL_TOLERANCE = 0.5
    const val FALLBACK_TOLERANCE = 1.0
    private const val EPSILON = 1e-7
}
