package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Respawn
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RespawnManager(private val plugin: ZombieRun) {

    private val respawns: ConcurrentHashMap<String, Respawn> = ConcurrentHashMap()

    private val waitRespawns: ConcurrentHashMap<String, CopyOnWriteArrayList<Respawn>> = ConcurrentHashMap()
    private val playerRespawns: ConcurrentHashMap<String, CopyOnWriteArrayList<Respawn>> = ConcurrentHashMap()
    private val zombieRespawns: ConcurrentHashMap<String, CopyOnWriteArrayList<Respawn>> = ConcurrentHashMap()
    private val zombieMainRespawns: ConcurrentHashMap<String, CopyOnWriteArrayList<Respawn>> = ConcurrentHashMap()
    private val doorPlayerRespawns: ConcurrentHashMap<String, ConcurrentHashMap<Int, CopyOnWriteArrayList<Respawn>>> = ConcurrentHashMap()
    private val doorZombieRespawns: ConcurrentHashMap<String, ConcurrentHashMap<Int, CopyOnWriteArrayList<Respawn>>> = ConcurrentHashMap()
    private val roomPlayerRespawns: ConcurrentHashMap<String, ConcurrentHashMap<Int, CopyOnWriteArrayList<Respawn>>> = ConcurrentHashMap()

    fun loadRespawns() {
        respawns.clear()
        waitRespawns.clear()
        playerRespawns.clear()
        zombieRespawns.clear()
        zombieMainRespawns.clear()
        doorPlayerRespawns.clear()
        doorZombieRespawns.clear()
        roomPlayerRespawns.clear()

        val respawnList = plugin.configManager.loadRespawns()

        respawnList.forEach { respawn ->
            respawns[respawn.name] = respawn
            addToIndexes(respawn)

            plugin.logger.info("重生点 '${respawn.name}' 加载成功，类型: ${respawn.type}，世界: ${respawn.world}")
        }

        plugin.logger.info("共加载 ${respawns.size} 个重生点")
    }

    private fun addToIndexes(respawn: Respawn) {
        when (respawn.type) {
            Respawn.RespawnType.WAIT -> waitRespawns.computeIfAbsent(respawn.world) { CopyOnWriteArrayList() }.add(respawn)
            Respawn.RespawnType.PLAYER -> playerRespawns.computeIfAbsent(respawn.world) { CopyOnWriteArrayList() }.add(respawn)
            Respawn.RespawnType.ZOMBIE -> zombieRespawns.computeIfAbsent(respawn.world) { CopyOnWriteArrayList() }.add(respawn)
            Respawn.RespawnType.ZOMBIE_MAIN -> zombieMainRespawns.computeIfAbsent(respawn.world) { CopyOnWriteArrayList() }.add(respawn)
            Respawn.RespawnType.DOOR_PLAYER -> {
                if (respawn.doorNumber != null) {
                    doorPlayerRespawns.computeIfAbsent(respawn.world) { ConcurrentHashMap() }
                        .computeIfAbsent(respawn.doorNumber) { CopyOnWriteArrayList() }.add(respawn)
                }
                if (respawn.roomNumber != null) {
                    roomPlayerRespawns.computeIfAbsent(respawn.world) { ConcurrentHashMap() }
                        .computeIfAbsent(respawn.roomNumber) { CopyOnWriteArrayList() }.add(respawn)
                }
            }
            Respawn.RespawnType.DOOR_ZOMBIE -> {
                if (respawn.doorNumber != null) {
                    doorZombieRespawns.computeIfAbsent(respawn.world) { ConcurrentHashMap() }
                        .computeIfAbsent(respawn.doorNumber) { CopyOnWriteArrayList() }.add(respawn)
                }
            }
        }
    }

    private fun removeFromIndexes(respawn: Respawn) {
        when (respawn.type) {
            Respawn.RespawnType.WAIT -> waitRespawns[respawn.world]?.remove(respawn)
            Respawn.RespawnType.PLAYER -> playerRespawns[respawn.world]?.remove(respawn)
            Respawn.RespawnType.ZOMBIE -> zombieRespawns[respawn.world]?.remove(respawn)
            Respawn.RespawnType.ZOMBIE_MAIN -> zombieMainRespawns[respawn.world]?.remove(respawn)
            Respawn.RespawnType.DOOR_PLAYER -> {
                if (respawn.doorNumber != null) {
                    doorPlayerRespawns[respawn.world]?.get(respawn.doorNumber)?.remove(respawn)
                }
                if (respawn.roomNumber != null) {
                    roomPlayerRespawns[respawn.world]?.get(respawn.roomNumber)?.remove(respawn)
                }
            }
            Respawn.RespawnType.DOOR_ZOMBIE -> {
                if (respawn.doorNumber != null) {
                    doorZombieRespawns[respawn.world]?.get(respawn.doorNumber)?.remove(respawn)
                }
            }
        }
    }

    fun getRespawn(name: String): Respawn? {
        return respawns[name]
    }

    fun getAllRespawns(): Collection<Respawn> {
        return respawns.values
    }

    fun addRespawn(respawn: Respawn) {
        respawns[respawn.name] = respawn
        addToIndexes(respawn)
    }

    fun removeRespawn(name: String) {
        val respawn = respawns.remove(name)
        if (respawn != null) {
            removeFromIndexes(respawn)
        }
    }

    /** 重生点所在的世界（MV 或 Bukkit 查找，回退第一个世界） */
    fun getRespawnWorld(respawn: Respawn): World {
        return plugin.worldService.getWorldOrFirst(respawn.world)
    }

    fun selectRespawn(player: Player): Respawn {
        val world = player.world.name
        return when (plugin.gameManager.getPlayerTeam(player)) {
            GameManager.Team.HUMAN -> getPlayerInitialRespawn(world) ?: getDefaultRespawn()
            GameManager.Team.ZOMBIE -> getZombieRespawn(world) ?: getDefaultRespawn()
            GameManager.Team.ZOMBIE_MAIN -> getZombieMainRespawn(world) ?: getDefaultRespawn()
            else -> getWaitRespawn(world) ?: getDefaultRespawn()
        }
    }

    fun getDefaultRespawn(): Respawn {
        val world = Bukkit.getWorlds().firstOrNull()
        return if (world != null) {
            val loc = world.spawnLocation
            Respawn(
                "default",
                loc.blockX,
                loc.blockY,
                loc.blockZ,
                loc.yaw.toDouble(),
                loc.pitch.toDouble(),
                world = world.name
            )
        } else {
            Respawn(
                "default",
                plugin.configManager.getSpawnX(),
                plugin.configManager.getSpawnY(),
                plugin.configManager.getSpawnZ(),
                plugin.configManager.getSpawnYaw(),
                plugin.configManager.getSpawnPitch()
            )
        }
    }

    fun respawnPlayer(player: Player) {
        val respawn = getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 重生至默认点")
    }

    fun selectNearestRespawn(location: Location): Respawn {
        var nearestRespawn: Respawn? = null
        var minDistance = Double.MAX_VALUE

        respawns.values.filter { it.world == location.world.name }.forEach { respawn ->
            val distance = respawn.getDistance(location)
            if (distance < minDistance) {
                minDistance = distance
                nearestRespawn = respawn
            }
        }

        return nearestRespawn ?: getDefaultRespawn()
    }

    fun getWaitRespawn(world: String): Respawn? {
        return waitRespawns[world]?.randomOrNull()
    }

    fun getPlayerInitialRespawn(world: String): Respawn? {
        return playerRespawns[world]?.randomOrNull()
    }

    fun getZombieRespawn(world: String): Respawn? {
        return zombieRespawns[world]?.randomOrNull()
    }

    fun getZombieMainRespawn(world: String): Respawn? {
        return zombieMainRespawns[world]?.randomOrNull() ?: zombieRespawns[world]?.randomOrNull()
    }

    fun getDoorPlayerRespawn(world: String, doorNumber: Int): Respawn? {
        return doorPlayerRespawns[world]?.get(doorNumber)?.randomOrNull()
    }

    fun getRoomPlayerRespawn(world: String, roomNumber: Int): Respawn? {
        return roomPlayerRespawns[world]?.get(roomNumber)?.randomOrNull()
    }

    fun getDoorZombieRespawn(world: String, doorNumber: Int): Respawn? {
        return doorZombieRespawns[world]?.get(doorNumber)?.randomOrNull()
    }

    fun getSpecialZombieRespawn(world: String, doorNumber: Int): Respawn? {
        if (doorNumber == 6 || doorNumber == 7) {
            return doorZombieRespawns[world]?.get(doorNumber)?.randomOrNull()
        }
        return null
    }

    fun teleportToWaitRespawn(player: Player) {
        val world = player.world.name
        val respawn = getWaitRespawn(world) ?: getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 传送到等待出生点 ${respawn.name}")
    }

    fun teleportToPlayerInitialRespawn(player: Player) {
        val world = player.world.name
        val respawn = getPlayerInitialRespawn(world) ?: getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 传送到初始出生点 ${respawn.name}")
    }

    fun teleportToZombieRespawn(zombie: Player) {
        val world = zombie.world.name
        val respawn = getZombieRespawn(world) ?: getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        zombie.teleportAsync(location)
        plugin.logger.info("僵尸传送到出生点 ${respawn.name}")
    }

    /**
     * 按人类推进进度选择僵尸复活点。
     * @param progress 人类当前推进到的最高门/房间号
     * @param ahead    是否向"人类前方更远"布防：true 优先选门号 > progress 的最近门点；
     *                 false 优先选门号 == progress 的就近门点（无则回退到最近的更前门点）
     */
    fun getProgressZombieRespawn(world: String, progress: Int, ahead: Boolean): Respawn? {
        val worldDoorZombie = doorZombieRespawns[world] ?: return null
        val nextDoors = worldDoorZombie.keys.filter { it > progress }
        val aheadPoint = nextDoors.minOrNull()?.let { worldDoorZombie[it]?.randomOrNull() }
        val currentPoint = worldDoorZombie[progress]?.randomOrNull()
        return if (ahead) {
            // 布防：更远门点优先；已到最后阶段则退回当前进度门点
            aheadPoint ?: currentPoint
        } else {
            // 就近：当前进度门点优先；人类刚开局（progress=0）则用最近的更前门点
            currentPoint ?: aheadPoint
        }
    }

    fun teleportZombieByProgress(zombie: Player, progress: Int, ahead: Boolean) {
        val world = zombie.world.name
        val respawn = getProgressZombieRespawn(world, progress, ahead)
            ?: getZombieRespawn(world)
            ?: getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        zombie.teleportAsync(location)
        plugin.logger.info("僵尸按进度($progress, ahead=$ahead)传送到 ${respawn.name}")
    }

    fun teleportToZombieMainRespawn(zombie: Player) {
        val world = zombie.world.name
        val respawn = getZombieMainRespawn(world) ?: getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        zombie.teleportAsync(location)
        plugin.logger.info("母体僵尸传送到出生点 ${respawn.name}")
    }

    fun teleportPlayerByDoorClose(player: Player, doorNumber: Int) {
        val world = player.world.name
        val respawn = getDoorPlayerRespawn(world, doorNumber) ?: getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 因门关闭传送到门${doorNumber}传送点 ${respawn.name}")
    }

    fun teleportPlayerByRoom(player: Player, roomNumber: Int) {
        val world = player.world.name
        val respawn = getRoomPlayerRespawn(world, roomNumber) ?: getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 传送到房间${roomNumber}传送点 ${respawn.name}")
    }

    fun teleportZombieByDoorClose(zombie: Player, doorNumber: Int) {
        val world = zombie.world.name
        val respawn = getDoorZombieRespawn(world, doorNumber) ?: getZombieRespawn(world) ?: getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        zombie.teleportAsync(location)
        plugin.logger.info("僵尸传送到门${doorNumber}传送点 ${respawn.name}")
    }

    fun teleportZombieSpecial(zombie: Player, doorNumber: Int) {
        val world = zombie.world.name
        val respawn = getSpecialZombieRespawn(world, doorNumber)
            ?: getDoorZombieRespawn(world, doorNumber)
            ?: getZombieRespawn(world)
            ?: getDefaultRespawn()
        val location = respawn.getLocation(getRespawnWorld(respawn))
        zombie.teleportAsync(location)
        plugin.logger.info("僵尸特殊传送到门${doorNumber}传送点 ${respawn.name}")
    }

    fun clear() {
        respawns.clear()
        waitRespawns.clear()
        playerRespawns.clear()
        zombieRespawns.clear()
        zombieMainRespawns.clear()
        doorPlayerRespawns.clear()
        doorZombieRespawns.clear()
        roomPlayerRespawns.clear()
    }
}
