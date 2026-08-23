package cn.oneachina.zombierun.v2.domain.arena

import cn.oneachina.zombierun.v2.domain.door.BlockRegion
import cn.oneachina.zombierun.v2.domain.door.DoorDefinition
import cn.oneachina.zombierun.v2.domain.game.MapFlowDefinition

data class ArenaDefinition(
    val name: String,
    val world: String,
    val doors: List<DoorDefinition> = emptyList(),
    val buttons: List<ButtonDefinition> = emptyList(),
    val respawns: List<RespawnDefinition> = emptyList(),
    val mapFlow: MapFlowDefinition? = null,
) {
    fun doorByNumber(number: Int): DoorDefinition? =
        doors.firstOrNull { it.number == number }

    fun doorById(id: String): DoorDefinition? =
        doors.firstOrNull { it.id == id }
}

enum class ButtonMode { NORMAL, ESCAPE }

data class ButtonDefinition(
    val id: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val mode: ButtonMode,
    val doorNumbers: List<Int>,
)

enum class RespawnType { WAIT, PLAYER, ZOMBIE, ZOMBIE_MAIN, DOOR_PLAYER, DOOR_ZOMBIE }

data class RespawnDefinition(
    val id: String,
    val world: String,
    val type: RespawnType,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val doorNumber: Int?,
)

fun BlockRegion.contains(x: Int, y: Int, z: Int): Boolean =
    x in minX..maxX && y in minY..maxY && z in minZ..maxZ
