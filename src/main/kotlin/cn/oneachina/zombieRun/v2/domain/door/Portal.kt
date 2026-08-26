package cn.oneachina.zombierun.v2.domain.door

enum class PortalAxis { X, Z, Y }

/**
 * 门前方方向：正方向即坐标增大的方向。
 */
enum class PortalFront { POSITIVE, NEGATIVE }

enum class Side {
    /** 门后侧（玩家应从这一侧进门） */
    BEHIND,

    /** 门前侧（穿过门后的区域） */
    FRONT,
}

/**
 * 门洞平面（纯领域值对象，不含 Bukkit 类型）。
 *
 * 只描述玩家“从哪边进、从哪边出、能从哪里穿过”，
 * 与方块区域 [BlockRegion] 完全解耦。
 *
 * 轴语义：
 * - X：竖直门墙，x=plane，横向范围 transverse=z，另一维 vertical=y
 * - Z：竖直门墙，z=plane，横向范围 transverse=x，另一维 vertical=y
 * - Y：水平地板/天花板门（向上/向下穿），y=plane，横向范围 transverse=x，另一维 vertical=z
 */
data class Portal(
    val axis: PortalAxis,
    val front: PortalFront,
    val planeCoordinate: Double,
    val transverseMin: Double,
    val transverseMax: Double,
    val yMin: Double,
    val yMax: Double,
) {
    init {
        require(transverseMax >= transverseMin) { "portal transverse range is invalid" }
        require(yMax >= yMin) { "portal y range is invalid" }
    }

    fun side(position: Vec3): Side {
        val value = axisValue(position)
        return when (front) {
            PortalFront.POSITIVE -> if (value >= planeCoordinate) Side.FRONT else Side.BEHIND
            PortalFront.NEGATIVE -> if (value <= planeCoordinate) Side.FRONT else Side.BEHIND
        }
    }

    fun axisValue(position: Vec3): Double = when (axis) {
        PortalAxis.X -> position.x
        PortalAxis.Z -> position.z
        PortalAxis.Y -> position.y
    }

    /** 门洞平面内的横向坐标（X/Z 门 = 水平横向，Y 门 = X 轴）。 */
    fun transverseValue(position: Vec3): Double = when (axis) {
        PortalAxis.X -> position.z
        PortalAxis.Z -> position.x
        PortalAxis.Y -> position.x
    }

    /** 门洞平面内的另一维坐标（X/Z 门 = Y 高度，Y 门 = Z 轴）。 */
    fun verticalValue(position: Vec3): Double = when (axis) {
        PortalAxis.X -> position.y
        PortalAxis.Z -> position.y
        PortalAxis.Y -> position.z
    }
}
