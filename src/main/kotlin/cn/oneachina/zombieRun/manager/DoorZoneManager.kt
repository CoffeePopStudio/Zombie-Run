package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.model.Door
import org.bukkit.Location
import java.util.concurrent.ConcurrentHashMap

class DoorZoneManager {

    private val zoneSize = 32 // 每个区域的大小
    private val doorsByZone = ConcurrentHashMap<String, MutableList<Door>>()

    /**
     * 初始化分区管理器，根据门的位置将其分配到相应的区域
     */
    fun initialize(doors: Collection<Door>) {
        doorsByZone.clear()
        doors.forEach { addDoor(it) }
    }

    /**
     * 添加门到分区管理器
     */
    fun addDoor(door: Door) {
        // 计算门占据的所有区域
        val zones = getZonesForDoor(door)
        zones.forEach { zoneKey ->
            doorsByZone.computeIfAbsent(zoneKey) { mutableListOf() }.add(door)
        }
    }
    /**
     * 从分区管理器中移除门
     */
    fun removeDoor(door: Door) {
        val zones = getZonesForDoor(door)
        zones.forEach { zoneKey ->
            doorsByZone[zoneKey]?.remove(door)
            if (doorsByZone[zoneKey]?.isEmpty() == true) {
                doorsByZone.remove(zoneKey)
            }
        }
    }

    /**
     * 获取指定位置所在区域的所有门
     */
    fun getDoorsInZone(location: Location): List<Door> {
        val zoneKey = getZoneKey(location.world.name, location.x.toInt(), location.z.toInt())
        return doorsByZone[zoneKey]?.toList() ?: emptyList()
    }

    /**
     * 获取指定区域范围内的所有门（限定世界）
     */
    fun getDoorsInArea(world: String, minX: Int, minZ: Int, maxX: Int, maxZ: Int): List<Door> {
        val result = mutableSetOf<Door>()

        // 计算覆盖的所有区域
        val startZoneX = minX / zoneSize
        val startZoneZ = minZ / zoneSize
        val endZoneX = maxX / zoneSize
        val endZoneZ = maxZ / zoneSize

        for (zoneX in startZoneX..endZoneX) {
            for (zoneZ in startZoneZ..endZoneZ) {
                val zoneKey = getZoneKey(world, zoneX, zoneZ)
                doorsByZone[zoneKey]?.forEach { result.add(it) }
            }
        }

        return result.toList()
    }

    /**
     * 获取门占据的所有区域
     */
    private fun getZonesForDoor(door: Door): Set<String> {
        val zones = mutableSetOf<String>()

        val startZoneX = door.minX / zoneSize
        val startZoneZ = door.minZ / zoneSize
        val endZoneX = door.maxX / zoneSize
        val endZoneZ = door.maxZ / zoneSize

        for (zoneX in startZoneX..endZoneX) {
            for (zoneZ in startZoneZ..endZoneZ) {
                zones.add(getZoneKey(door.world, zoneX, zoneZ))
            }
        }

        return zones
    }

    /**
     * 获取区域的唯一键（世界名 + 已除以 zoneSize 的区域索引）
     */
    private fun getZoneKey(world: String, x: Int, z: Int): String {
        return "$world:$x,$z"
    }

    /**
     * 获取所有区域的门数量
     */
    fun getZoneCount(): Int {
        return doorsByZone.size
    }

    /**
     * 清空所有区域的门
     */
    fun clear() {
        doorsByZone.clear()
    }
}
