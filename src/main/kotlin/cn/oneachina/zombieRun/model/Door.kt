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
    val reverseDirection: Boolean = false,
    /** 所在世界名 */
    val world: String = "world"
) {

    enum class DoorMode {
        NORMAL, PLAYER, ZOMBIE, START;

        companion object {
            fun fromString(s: String): DoorMode {
                return entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NORMAL
            }
        }
    }

    // 区域线程写入（开门/穿越判定）、全局调度线程读取（reset），需 volatile
    @Volatile var isOpen: Boolean = false
    @Volatile var isActive: Boolean = false

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

    /** 穿越轴：'x' 或 'z'（短边方向） */
    fun crossingAxis(): Char {
        val xLen = maxX - minX
        val zLen = maxZ - minZ
        return if (xLen > zLen) 'z' else 'x'
    }

    /** 判断玩家是否从上一位置到当前位置穿过门平面 */
    fun crossedBy(from: Location, to: Location, axisTolerance: Double = 0.5, heightTolerance: Double = 1.0): Boolean {
        if (from.world != to.world) return false

        val axis = crossingAxis()
        val fromAxis = if (axis == 'z') from.z else from.x
        val toAxis = if (axis == 'z') to.z else to.x
        val center = if (axis == 'z') (minZ + maxZ) / 2.0 else (minX + maxX) / 2.0
        val axisDelta = kotlin.math.abs(toAxis - fromAxis)
        if (axisDelta > 2.5) return false

        val crossed = if (!reverseDirection) {
            fromAxis < center && toAxis >= center
        } else {
            fromAxis > center && toAxis <= center
        }
        if (!crossed) return false

        val transverse = if (axis == 'z') to.x else to.z
        val transverseMin = if (axis == 'z') minX else minZ
        val transverseMax = if (axis == 'z') maxX else maxZ
        if (transverse < transverseMin - axisTolerance || transverse > transverseMax + axisTolerance) return false
        return to.y >= minY - heightTolerance && to.y <= maxY + heightTolerance
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
        world: String = this.world
    ) = Door(name, minX, minY, minZ, maxX, maxY, maxZ, openTime, closeTime, doorNumber, material, specialBehavior, mode, useScanData, blocks, group, reverseDirection, world)

    override fun toString(): String {
        return "Door(name='$name', doorNumber=$doorNumber, mode='$mode', group=${group ?: "-"}, open=$openTime, close=$closeTime, blocks=${blocks.size})"
    }
}
