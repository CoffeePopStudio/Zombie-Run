package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Respawn
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RespawnManager(private val plugin: ZombieRun) {

    private val respawns: ConcurrentHashMap<String, Respawn> = ConcurrentHashMap()

    private val waitRespawns: CopyOnWriteArrayList<Respawn> = CopyOnWriteArrayList()
    private val playerRespawns: CopyOnWriteArrayList<Respawn> = CopyOnWriteArrayList()
    private val zombieRespawns: CopyOnWriteArrayList<Respawn> = CopyOnWriteArrayList()
    private val zombieMainRespawns: CopyOnWriteArrayList<Respawn> = CopyOnWriteArrayList()
    private val doorPlayerRespawns: ConcurrentHashMap<Int, CopyOnWriteArrayList<Respawn>> = ConcurrentHashMap()
    private val doorZombieRespawns: ConcurrentHashMap<Int, CopyOnWriteArrayList<Respawn>> = ConcurrentHashMap()
    private val roomPlayerRespawns: ConcurrentHashMap<Int, CopyOnWriteArrayList<Respawn>> = ConcurrentHashMap()

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

            when (respawn.type) {
                Respawn.RespawnType.WAIT -> waitRespawns.add(respawn)
                Respawn.RespawnType.PLAYER -> playerRespawns.add(respawn)
                Respawn.RespawnType.ZOMBIE -> zombieRespawns.add(respawn)
                Respawn.RespawnType.ZOMBIE_MAIN -> zombieMainRespawns.add(respawn)
                Respawn.RespawnType.DOOR_PLAYER -> {
                    if (respawn.doorNumber != null) {
                        val list = doorPlayerRespawns.computeIfAbsent(respawn.doorNumber) { CopyOnWriteArrayList() }
                        list.add(respawn)
                    }
                    if (respawn.roomNumber != null) {
                        val list = roomPlayerRespawns.computeIfAbsent(respawn.roomNumber) { CopyOnWriteArrayList() }
                        list.add(respawn)
                    }
                }
                Respawn.RespawnType.DOOR_ZOMBIE -> {
                    if (respawn.doorNumber != null) {
                        val list = doorZombieRespawns.computeIfAbsent(respawn.doorNumber) { CopyOnWriteArrayList() }
                        list.add(respawn)
                    }
                }
            }

            plugin.logger.info("重生点 '${respawn.name}' 加载成功，类型: ${respawn.type}")
        }

        plugin.logger.info("共加载 ${respawns.size} 个重生点")
        plugin.logger.info("等待出生点: ${waitRespawns.size}")
        plugin.logger.info("玩家出生点: ${playerRespawns.size}")
        plugin.logger.info("僵尸出生点: ${zombieRespawns.size}")
        plugin.logger.info("母体僵尸出生点: ${zombieMainRespawns.size}")
        plugin.logger.info("门玩家传送点: ${doorPlayerRespawns.size} 个门")
        plugin.logger.info("门僵尸传送点: ${doorZombieRespawns.size} 个门")
    }

    fun getRespawn(name: String): Respawn? {
        return respawns[name]
    }

    fun getAllRespawns(): Collection<Respawn> {
        return respawns.values
    }

    fun addRespawn(respawn: Respawn) {
        respawns[respawn.name] = respawn

        when (respawn.type) {
            Respawn.RespawnType.WAIT -> waitRespawns.add(respawn)
            Respawn.RespawnType.PLAYER -> playerRespawns.add(respawn)
            Respawn.RespawnType.ZOMBIE -> zombieRespawns.add(respawn)
            Respawn.RespawnType.ZOMBIE_MAIN -> zombieMainRespawns.add(respawn)
            Respawn.RespawnType.DOOR_PLAYER -> {
                if (respawn.doorNumber != null) {
                    val list = doorPlayerRespawns.computeIfAbsent(respawn.doorNumber) { CopyOnWriteArrayList() }
                    list.add(respawn)
                }
                if (respawn.roomNumber != null) {
                    val list = roomPlayerRespawns.computeIfAbsent(respawn.roomNumber) { CopyOnWriteArrayList() }
                    list.add(respawn)
                }
            }
            Respawn.RespawnType.DOOR_ZOMBIE -> {
                if (respawn.doorNumber != null) {
                    val list = doorZombieRespawns.computeIfAbsent(respawn.doorNumber) { CopyOnWriteArrayList() }
                    list.add(respawn)
                }
            }
        }
    }

    fun removeRespawn(name: String) {
        val respawn = respawns.remove(name)
        if (respawn != null) {
            when (respawn.type) {
                Respawn.RespawnType.WAIT -> waitRespawns.remove(respawn)
                Respawn.RespawnType.PLAYER -> playerRespawns.remove(respawn)
                Respawn.RespawnType.ZOMBIE -> zombieRespawns.remove(respawn)
                Respawn.RespawnType.ZOMBIE_MAIN -> zombieMainRespawns.remove(respawn)
                Respawn.RespawnType.DOOR_PLAYER -> {
                    if (respawn.doorNumber != null) {
                        doorPlayerRespawns[respawn.doorNumber]?.remove(respawn)
                    }
                    if (respawn.roomNumber != null) {
                        roomPlayerRespawns[respawn.roomNumber]?.remove(respawn)
                    }
                }
                Respawn.RespawnType.DOOR_ZOMBIE -> {
                    if (respawn.doorNumber != null) {
                        doorZombieRespawns[respawn.doorNumber]?.remove(respawn)
                    }
                }
            }
        }
    }

    fun selectRespawn(player: Player): Respawn {
        return when (plugin.gameManager.getPlayerTeam(player)) {
            GameManager.Team.HUMAN -> getPlayerInitialRespawn() ?: getDefaultRespawn()
            GameManager.Team.ZOMBIE -> getZombieRespawn() ?: getDefaultRespawn()
            GameManager.Team.ZOMBIE_MAIN -> getZombieMainRespawn() ?: getDefaultRespawn()
            else -> getWaitRespawn() ?: getDefaultRespawn()
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
                loc.pitch.toDouble()
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
        val location = respawn.getLocation(player.world)
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 重生至默认点")
    }

    fun selectNearestRespawn(location: Location): Respawn {
        var nearestRespawn: Respawn? = null
        var minDistance = Double.MAX_VALUE

        respawns.values.forEach { respawn ->
            val distance = respawn.getDistance(location)
            if (distance < minDistance) {
                minDistance = distance
                nearestRespawn = respawn
            }
        }

        return nearestRespawn ?: getDefaultRespawn()
    }

    fun getWaitRespawn(): Respawn? {
        return waitRespawns.randomOrNull()
    }

    fun getPlayerInitialRespawn(): Respawn? {
        return playerRespawns.randomOrNull()
    }

    fun getZombieRespawn(): Respawn? {
        return zombieRespawns.randomOrNull()
    }

    fun getZombieMainRespawn(): Respawn? {
        return zombieMainRespawns.randomOrNull() ?: zombieRespawns.randomOrNull()
    }

    fun getDoorPlayerRespawn(doorNumber: Int): Respawn? {
        return doorPlayerRespawns[doorNumber]?.randomOrNull()
    }

    fun getRoomPlayerRespawn(roomNumber: Int): Respawn? {
        return roomPlayerRespawns[roomNumber]?.randomOrNull()
    }

    fun getDoorZombieRespawn(doorNumber: Int): Respawn? {
        return doorZombieRespawns[doorNumber]?.randomOrNull()
    }

    fun getSpecialZombieRespawn(doorNumber: Int): Respawn? {
        if (doorNumber == 6 || doorNumber == 7) {
            return doorZombieRespawns[doorNumber]?.randomOrNull()
        }
        return null
    }

    fun teleportToWaitRespawn(player: Player) {
        val respawn = getWaitRespawn() ?: getDefaultRespawn()
        val location = respawn.getLocation(player.world)
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 传送到等待出生点 ${respawn.name}")
    }

    fun teleportToPlayerInitialRespawn(player: Player) {
        val respawn = getPlayerInitialRespawn() ?: getDefaultRespawn()
        val location = respawn.getLocation(player.world)
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 传送到初始出生点 ${respawn.name}")
    }

    fun teleportToZombieRespawn(zombie: Player) {
        val respawn = getZombieRespawn() ?: getDefaultRespawn()
        val location = respawn.getLocation(zombie.world)
        zombie.teleportAsync(location)
        plugin.logger.info("僵尸传送到出生点 ${respawn.name}")
    }

    fun teleportToZombieMainRespawn(zombie: Player) {
        val respawn = getZombieMainRespawn() ?: getDefaultRespawn()
        val location = respawn.getLocation(zombie.world)
        zombie.teleportAsync(location)
        plugin.logger.info("母体僵尸传送到出生点 ${respawn.name}")
    }

    fun teleportPlayerByDoorClose(player: Player, doorNumber: Int) {
        val respawn = getDoorPlayerRespawn(doorNumber) ?: getDefaultRespawn()
        val location = respawn.getLocation(player.world)
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 因门关闭传送到门${doorNumber}传送点 ${respawn.name}")
    }

    fun teleportPlayerByRoom(player: Player, roomNumber: Int) {
        val respawn = getRoomPlayerRespawn(roomNumber) ?: getDefaultRespawn()
        val location = respawn.getLocation(player.world)
        player.teleportAsync(location)
        plugin.logger.info("玩家 ${player.name} 传送到房间${roomNumber}传送点 ${respawn.name}")
    }

    fun teleportZombieByDoorClose(zombie: Player, doorNumber: Int) {
        val respawn = getDoorZombieRespawn(doorNumber) ?: getZombieRespawn() ?: getDefaultRespawn()
        val location = respawn.getLocation(zombie.world)
        zombie.teleportAsync(location)
        plugin.logger.info("僵尸传送到门${doorNumber}传送点 ${respawn.name}")
    }

    fun teleportZombieSpecial(zombie: Player, doorNumber: Int) {
        val respawn = getSpecialZombieRespawn(doorNumber) ?: getDoorZombieRespawn(doorNumber) ?: getZombieRespawn() ?: getDefaultRespawn()
        val location = respawn.getLocation(zombie.world)
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
