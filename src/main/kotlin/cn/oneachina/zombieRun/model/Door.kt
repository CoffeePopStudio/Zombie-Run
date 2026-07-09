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
    val delay: Int = 30,
    val duration: Int = 10,
    val doorNumber: Int = 0,
    val openTime: Int = 10,
    val closeTime: Int = 15,
    val material: String = "STONE",
    val teleportRegion: String? = null,
    val hasZombieTeleport: Boolean = false,
    val specialTeleport: Boolean = false,
    val mode: DoorMode = DoorMode.NORMAL,
    val useScanData: Boolean = false,
    val blocks: Map<String, String> = emptyMap()
) {

    enum class DoorMode {
        NORMAL, PLAYER, ZOMBIE, START;

        companion object {
            fun fromString(s: String): DoorMode {
                return entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NORMAL
            }
        }
    }

    var isOpen: Boolean = false
    var openTimestamp: Long = 0
    var closeTaskId: Int? = null

    fun getMinLocation(world: World): Location {
        return Location(world, minX.toDouble(), minY.toDouble(), minZ.toDouble())
    }

    fun getMaxLocation(world: World): Location {
        return Location(world, maxX.toDouble(), maxY.toDouble(), maxZ.toDouble())
    }

    fun getCenterLocation(world: World): Location {
        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0
        val centerZ = (minZ + maxZ) / 2.0
        return Location(world, centerX, centerY, centerZ)
    }

    fun containsLocation(location: Location): Boolean {
        val x = location.x.toInt()
        val y = location.y.toInt()
        val z = location.z.toInt()
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    fun getBlocks(world: World): List<Block> {
        val blocks = mutableListOf<Block>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    blocks.add(world.getBlockAt(x, y, z))
                }
            }
        }
        return blocks
    }

    fun open(world: World) {
        isOpen = true
        openTimestamp = System.currentTimeMillis()
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
    }

    fun closeBlocks(world: World) {
        if (useScanData && blocks.isNotEmpty()) {
            blocks.forEach { (posStr, materialName) ->
                val parts = posStr.split(',').map { it.toInt() }
                if (parts.size == 3) {
                    val (x, y, z) = parts
                    val block = world.getBlockAt(x, y, z)
                    try {
                        block.type = Material.valueOf(materialName.uppercase())
                    } catch (_: IllegalArgumentException) {
                    }
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

    fun shouldClose(): Boolean {
        if (!isOpen) return false
        val elapsedTime = System.currentTimeMillis() - openTimestamp
        return elapsedTime >= duration * 1000L
    }

    fun needsSpecialTeleport(): Boolean {
        return specialTeleport
    }

    override fun toString(): String {
        return "Door(name='$name', doorNumber=$doorNumber, mode='$mode', delay=$delay, useScanData=$useScanData, blocks=${blocks.size})"
    }
}
