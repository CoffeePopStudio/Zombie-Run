package cn.oneachina.zombieRun.model

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.Material

class Door(
    val name: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    /** 开门前倒计时秒数 */
    val openTime: Int,
    /** 关门倒计时秒数 */
    val closeTime: Int,
    val doorNumber: Int = 0,
    val material: String = "STONE",
    var specialBehavior: SpecialDoorBehavior? = null,
    val mode: DoorMode = DoorMode.NORMAL,
    val useScanData: Boolean = false,
    val blocks: Map<String, String> = emptyMap(),
    val group: String? = null,
    /** 反转穿越方向（默认正方向为"前方"，设为 true 则负方向为"前方"） */
    val reverseDirection: Boolean = false
) {

    enum class DoorMode {
        NORMAL, PLAYER, ZOMBIE, START;

        companion object {
            fun fromString(s: String): DoorMode {
                return entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NORMAL
            }
        }
    }

    /** 玩家相对于门平面的侧边 */
    enum class Side { BEHIND, FRONT, ON_PLANE }

    var isOpen: Boolean = false
    var isActive: Boolean = false

    fun getMinLocation(world: World): Location =
        Location(world, minX.toDouble(), minY.toDouble(), minZ.toDouble())

    fun getMaxLocation(world: World): Location =
        Location(world, maxX.toDouble(), maxY.toDouble(), maxZ.toDouble())

    fun getCenterLocation(world: World): Location =
        Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0)

    fun containsLocation(location: Location): Boolean {
        val x = location.x.toInt()
        val y = location.y.toInt()
        val z = location.z.toInt()
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    fun getBlocks(world: World): List<Block> {
        val result = mutableListOf<Block>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    result.add(world.getBlockAt(x, y, z))
                }
            }
        }
        return result
    }

    fun open(world: World) {
        isOpen = true
    }

    fun openBlocks(world: World) {
        getBlocks(world).forEach { block ->
            if (!block.type.isAir) {
                block.type = Material.AIR
            }
        }
    }

    fun close(world: World) {
        isOpen = false
        isActive = false
    }

    fun closeBlocks(world: World) {
        if (useScanData && blocks.isNotEmpty()) {
            blocks.forEach { (posStr, materialName) ->
                val parts = posStr.split(',').map { it.toInt() }
                if (parts.size == 3) {
                    val (x, y, z) = parts
                    try {
                        world.getBlockAt(x, y, z).type = Material.valueOf(materialName.uppercase())
                    } catch (_: IllegalArgumentException) {}
                }
            }
        } else {
            getBlocks(world).forEach { block ->
                if (block.type.isAir) {
                    try {
                        block.type = Material.valueOf(material.uppercase())
                    } catch (_: IllegalArgumentException) {
                        block.type = Material.STONE
                    }
                }
            }
        }
    }

    // ==================== 门穿越检测（新方案） ====================

    /** 穿越轴：'x' 或 'z'（短边方向） */
    fun crossingAxis(): Char {
        val xLen = maxX - minX
        val zLen = maxZ - minZ
        return if (xLen > zLen) 'z' else 'x'
    }

    /** 门平面坐标（穿越轴的中点） */
    fun planeCoord(): Double {
        val axis = crossingAxis()
        return if (axis == 'z') (minZ + maxZ) / 2.0 else (minX + maxX) / 2.0
    }

    /**
     * 判断玩家相对于门平面的侧边。
     * @param hysteresis 避免平面附近抖动的最小距离
     */
    fun sideOf(location: Location, hysteresis: Double = 0.3): Side {
        val axis = crossingAxis()
        val v = if (axis == 'z') location.z else location.x
        val plane = planeCoord()
        val diff = v - plane
        val forwardPositive = !reverseDirection

        val front = if (forwardPositive) diff > hysteresis else diff < -hysteresis
        val behind = if (forwardPositive) diff < -hysteresis else diff > hysteresis

        return when {
            front -> Side.FRONT
            behind -> Side.BEHIND
            else -> Side.ON_PLANE
        }
    }

    /**
     * 玩家当前坐标是否在门洞附近（用于侧边翻转兜底判定）。
     */
    fun isNearOpening(
        location: Location,
        transverseTolerance: Double = 1.0,
        footTolerance: Double = 1.0,
        headTolerance: Double = 2.0
    ): Boolean {
        val axis = crossingAxis()
        val transCoord = if (axis == 'z') location.x else location.z
        val transMin = if (axis == 'z') minX.toDouble() else minZ.toDouble()
        val transMax = if (axis == 'z') maxX.toDouble() else maxZ.toDouble()
        return transCoord in (transMin - transverseTolerance)..(transMax + transverseTolerance) &&
               location.y in (minY - footTolerance)..(maxY + headTolerance)
    }

    /**
     * 判断玩家从 from 移动到 to 是否穿过门平面（线段与门洞矩形求交）。
     * 这是主要的几何判定方法，不受步长限制，对角线也能准确检测。
     *
     * @param transverseTolerance 横向容差（默认 0.6 ≈ 玩家半宽）
     * @param footTolerance       脚底容差（默认 1.0）
     * @param headTolerance       头顶容差（默认 2.0，覆盖玩家身高）
     */
    fun crossedBy(
        from: Location,
        to: Location,
        transverseTolerance: Double = 0.6,
        footTolerance: Double = 1.0,
        headTolerance: Double = 2.0
    ): Boolean {
        if (from.world != to.world) return false

        val axis = crossingAxis()
        val plane = planeCoord()
        val fromAxis = if (axis == 'z') from.z else from.x
        val toAxis = if (axis == 'z') to.z else to.x

        if (fromAxis == toAxis) return false

        // 方向判定：只认可从门后到门前
        val forwardPositive = !reverseDirection
        val fromBehind = if (forwardPositive) fromAxis < plane else fromAxis > plane
        val toFront = if (forwardPositive) toAxis >= plane else toAxis <= plane
        if (!fromBehind || !toFront) return false

        // 线段与平面相交参数 t
        val t = (plane - fromAxis) / (toAxis - fromAxis)
        if (t !in 0.0..1.0) return false

        // 交点在三维空间中的坐标
        val x = from.x + (to.x - from.x) * t
        val z = from.z + (to.z - from.z) * t
        val y = from.y + (to.y - from.y) * t

        // 横向是否在门洞内
        val transCoord = if (axis == 'z') x else z
        val transMin = if (axis == 'z') minX.toDouble() else minZ.toDouble()
        val transMax = if (axis == 'z') maxX.toDouble() else maxZ.toDouble()

        return transCoord in (transMin - transverseTolerance)..(transMax + transverseTolerance) &&
               y in (minY - footTolerance)..(maxY + headTolerance)
    }

    /** 关门时判断玩家是否已通过门（正坐标方向为"前方"，reverseDirection=true 则反转） */
    fun isPlayerPastDoor(location: Location, crossingTolerance: Double = 5.0): Boolean {
        // 高度对齐
        if (location.y < minY - crossingTolerance || location.y > maxY + crossingTolerance) return false

        val pastPositive = !reverseDirection
        return if (crossingAxis() == 'z') {
            // X 对齐
            if (location.x < minX - crossingTolerance || location.x > maxX + crossingTolerance) return false
            if (pastPositive) location.z > (minZ + maxZ) / 2.0
            else location.z < (minZ + maxZ) / 2.0
        } else {
            // Z 对齐
            if (location.z < minZ - crossingTolerance || location.z > maxZ + crossingTolerance) return false
            if (pastPositive) location.x > (minX + maxX) / 2.0
            else location.x < (minX + maxX) / 2.0
        }
    }

    fun hasSpecialBehavior(): Boolean = specialBehavior != null

    /** 返回一个修改了指定字段的新门（用于编辑命令） */
    fun with(
        openTime: Int = this.openTime,
        closeTime: Int = this.closeTime,
        doorNumber: Int = this.doorNumber,
        group: String? = this.group,
        reverseDirection: Boolean = this.reverseDirection
    ) = Door(name, minX, minY, minZ, maxX, maxY, maxZ, openTime, closeTime, doorNumber, material, specialBehavior, mode, useScanData, blocks, group, reverseDirection)

    override fun toString(): String {
        return "Door(name='$name', doorNumber=$doorNumber, mode='$mode', group=${group ?: "-"}, open=$openTime, close=$closeTime, blocks=${blocks.size})"
    }
}
