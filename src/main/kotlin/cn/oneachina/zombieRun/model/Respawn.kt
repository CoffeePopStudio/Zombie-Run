package cn.oneachina.zombieRun.model

import org.bukkit.Location
import kotlin.math.sqrt

class Respawn(
    val name: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val type: RespawnType = RespawnType.PLAYER,
    val doorNumber: Int? = null,
    val roomNumber: Int? = null,
    val world: String = "world"
) {
    
    enum class RespawnType {
        WAIT,
        PLAYER,
        ZOMBIE,
        ZOMBIE_MAIN,
        DOOR_PLAYER,
        DOOR_ZOMBIE
    }

    fun getLocation(world: org.bukkit.World): Location {
        return Location(world, x.toDouble(), y.toDouble(), z.toDouble(), yaw.toFloat(), pitch.toFloat())
    }

    fun isForDoor(doorNum: Int): Boolean {
        return doorNumber == doorNum
    }

    fun isForRoom(roomNum: Int): Boolean {
        return roomNumber == roomNum
    }

    fun getDistance(location: Location): Double {
        val dx = x - location.x
        val dy = y - location.y
        val dz = z - location.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    override fun toString(): String {
        return "Respawn(name='$name', type=$type, location=($x, $y, $z), yaw=$yaw, pitch=$pitch, doorNumber=$doorNumber, roomNumber=$roomNumber)"
    }
}