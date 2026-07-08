package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.AmmoConfig
import cn.oneachina.zombieRun.model.Door
import cn.oneachina.zombieRun.model.Button
import cn.oneachina.zombieRun.model.Respawn
import cn.oneachina.zombieRun.model.WeaponConfig
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException

class ConfigManager(private val plugin: ZombieRun) {

    private lateinit var config: YamlConfiguration
    private lateinit var configFile: File

    fun loadConfig() {
        val configDir = File(plugin.dataFolder, "config")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }

        configFile = File(configDir, "config.yml")
        if (!configFile.exists()) {
            plugin.saveResource("config/config.yml", false)
        }

        config = YamlConfiguration.loadConfiguration(configFile)
        plugin.logger.info("配置文件加载成功")
    }

    fun reloadConfig() {
        if (::configFile.isInitialized) {
            config = YamlConfiguration.loadConfiguration(configFile)
            plugin.logger.info("配置文件重载成功")
        }
    }

    fun getConfig(): YamlConfiguration {
        return config
    }

    fun saveConfig() {
        try {
            config.save(configFile)
            plugin.logger.info("配置文件保存成功")
        } catch (e: IOException) {
            plugin.logger.severe("配置文件保存失败: ${e.message}")
        }
    }

    fun getGameMode(): String {
        return config.getString("game.mode", "zombie_run") ?: "zombie_run"
    }

    fun getWorldName(): String {
        return config.getString("game.world", "world") ?: "world"
    }

    fun getStartDelay(): Int {
        return config.getInt("game.start-delay", 30)
    }

    fun getMaxDuration(): Int {
        return config.getInt("game.max-duration", 1800)
    }

    fun getMinPlayers(): Int {
        return config.getInt("game.min-players", 8)
    }

    fun getMaxPlayers(): Int {
        return config.getInt("game.max-players", 32)
    }

    fun getSpawnX(): Int {
        return config.getInt("spawn.x", 0)
    }

    fun getSpawnY(): Int {
        return config.getInt("spawn.y", 64)
    }

    fun getSpawnZ(): Int {
        return config.getInt("spawn.z", 0)
    }

    fun getSpawnYaw(): Double {
        return config.getDouble("spawn.yaw", 0.0)
    }

    fun getSpawnPitch(): Double {
        return config.getDouble("spawn.pitch", 0.0)
    }

    fun getEndX(): Int {
        return config.getInt("end.x", 100)
    }

    fun getEndY(): Int {
        return config.getInt("end.y", 64)
    }

    fun getEndZ(): Int {
        return config.getInt("end.z", 100)
    }

    fun loadDoors(): List<Door> {
        val doors = mutableListOf<Door>()
        val doorsSection = config.getConfigurationSection("doors") ?: return doors

        for (name in doorsSection.getKeys(false)) {
            val doorSection = doorsSection.getConfigurationSection(name) ?: continue
            val x1 = doorSection.getInt("x1")
            val y1 = doorSection.getInt("y1")
            val z1 = doorSection.getInt("z1")
            val x2 = doorSection.getInt("x2")
            val y2 = doorSection.getInt("y2")
            val z2 = doorSection.getInt("z2")

            val modeStr = doorSection.getString("mode", "normal") ?: "normal"
            val delay = doorSection.getInt("delay", 30)
            val material = doorSection.getString("material", "STONE") ?: "STONE"
            val useScanData = doorSection.getBoolean("use-scan-data", false)
            val blocks = mutableMapOf<String, String>()
            if (useScanData) {
                val blocksSection = doorSection.getConfigurationSection("blocks")
                if (blocksSection != null) {
                    for (key in blocksSection.getKeys(false)) {
                        val mat = blocksSection.getString(key)
                        if (mat != null) {
                            blocks[key] = mat
                        }
                    }
                }
            }

            val door = Door(
                name = name,
                minX = minOf(x1, x2),
                minY = minOf(y1, y2),
                minZ = minOf(z1, z2),
                maxX = maxOf(x1, x2),
                maxY = maxOf(y1, y2),
                maxZ = maxOf(z1, z2),
                delay = delay,
                duration = doorSection.getInt("duration", 10),
                doorNumber = doorSection.getInt("door-number", 0),
                openTime = doorSection.getInt("open-time", 10),
                closeTime = doorSection.getInt("close-time", 15),
                material = material,
                teleportRegion = doorSection.getString("teleport-region"),
                hasZombieTeleport = doorSection.getBoolean("has-zombie-teleport", false),
                specialTeleport = doorSection.getBoolean("special-teleport", false),
                mode = Door.DoorMode.fromString(modeStr),
                useScanData = useScanData,
                blocks = blocks
            )
            doors.add(door)
        }
        return doors
    }

    fun loadRespawns(): List<Respawn> {
        val respawns = mutableListOf<Respawn>()
        val respawnsSection = config.getConfigurationSection("respawns") ?: return respawns

        for (name in respawnsSection.getKeys(false)) {
            val respawnSection = respawnsSection.getConfigurationSection(name) ?: continue
            val typeStr = respawnSection.getString("type", "player")?.uppercase() ?: "PLAYER"
            val type = try {
                Respawn.RespawnType.valueOf(typeStr)
            } catch (_: IllegalArgumentException) {
                Respawn.RespawnType.PLAYER
            }

            val respawn = Respawn(
                name = name,
                x = respawnSection.getInt("x"),
                y = respawnSection.getInt("y"),
                z = respawnSection.getInt("z"),
                yaw = respawnSection.getDouble("yaw", 0.0),
                pitch = respawnSection.getDouble("pitch", 0.0),
                type = type,
                doorNumber = if (respawnSection.contains("door-number")) respawnSection.getInt("door-number") else null,
                roomNumber = if (respawnSection.contains("room-number")) respawnSection.getInt("room-number") else null
            )
            respawns.add(respawn)
        }
        return respawns
    }

    fun loadButtons(): List<Button> {
        val buttons = mutableListOf<Button>()
        val buttonsSection = config.getConfigurationSection("buttons") ?: return buttons

        for (name in buttonsSection.getKeys(false)) {
            val section = buttonsSection.getConfigurationSection(name) ?: continue
            val mode = section.getString("mode") ?: "normal"
            val doorNumber = if (section.contains("door-number")) section.getInt("door-number") else null
            val doorNumbers = if (section.contains("door-numbers")) section.getIntegerList("door-numbers") else null
            val playerTargetX = if (section.contains("playerTargetX")) section.getInt("playerTargetX") else null
            val playerTargetY = if (section.contains("playerTargetY")) section.getInt("playerTargetY") else null
            val playerTargetZ = if (section.contains("playerTargetZ")) section.getInt("playerTargetZ") else null
            val zombieTargetX = if (section.contains("zombieTargetX")) section.getInt("zombieTargetX") else null
            val zombieTargetY = if (section.contains("zombieTargetY")) section.getInt("zombieTargetY") else null
            val zombieTargetZ = if (section.contains("zombieTargetZ")) section.getInt("zombieTargetZ") else null

            val button = Button(
                name = name,
                x = section.getInt("x"),
                y = section.getInt("y"),
                z = section.getInt("z"),
                mode = mode,
                doorNumber = doorNumber,
                doorNumbers = doorNumbers,
                playerTargetX = playerTargetX,
                playerTargetY = playerTargetY,
                playerTargetZ = playerTargetZ,
                zombieTargetX = zombieTargetX,
                zombieTargetY = zombieTargetY,
                zombieTargetZ = zombieTargetZ
            )
            buttons.add(button)
        }
        return buttons
    }

    fun addButton(button: Button) {
        val section = config.createSection("buttons.${button.name}")
        section.set("x", button.x)
        section.set("y", button.y)
        section.set("z", button.z)
        section.set("mode", button.mode)
        if (button.doorNumber != null) {
            section.set("door-number", button.doorNumber)
        }
        if (button.doorNumbers != null && button.doorNumbers.isNotEmpty()) {
            section.set("door-numbers", button.doorNumbers)
        }
        if (button.playerTargetX != null) {
            section.set("playerTargetX", button.playerTargetX)
        }
        if (button.playerTargetY != null) {
            section.set("playerTargetY", button.playerTargetY)
        }
        if (button.playerTargetZ != null) {
            section.set("playerTargetZ", button.playerTargetZ)
        }
        if (button.zombieTargetX != null) {
            section.set("zombieTargetX", button.zombieTargetX)
        }
        if (button.zombieTargetY != null) {
            section.set("zombieTargetY", button.zombieTargetY)
        }
        if (button.zombieTargetZ != null) {
            section.set("zombieTargetZ", button.zombieTargetZ)
        }
        saveConfig()
    }

    fun removeButton(name: String) {
        config.set("buttons.$name", null)
        saveConfig()
    }

    fun getZombieHealthMultiplier(): Double {
        return config.getDouble("zombie.health-multiplier", 2.0)
    }

    fun getZombieSpeedMultiplier(): Double {
        return config.getDouble("zombie.speed-multiplier", 1.2)
    }

    fun getZombieDamageMultiplier(): Double {
        return config.getDouble("zombie.damage-multiplier", 1.5)
    }

    fun getHumanSpeedMultiplier(): Double {
        return config.getDouble("human.speed-multiplier", 1.0)
    }

    fun getHumanJumpMultiplier(): Double {
        return config.getDouble("human.jump-multiplier", 1.0)
    }

    fun getHumanStaminaRegen(): Double {
        return config.getDouble("human.stamina-regen", 0.1)
    }

    fun getStaminaSprintCost(): Double {
        return config.getDouble("stamina.sprint-cost", 0.5)
    }

    fun getStaminaStandingRegen(): Double {
        return config.getDouble("stamina.standing-regen", 0.2)
    }

    fun getStaminaMax(): Double {
        return config.getDouble("stamina.max", 100.0)
    }

    fun getExplosionDamageReduction(): Double {
        return config.getDouble("misc.explosion-damage-reduction", 0.5)
    }

    fun getKnockbackReduction(): Double {
        return config.getDouble("misc.knockback-reduction", 0.3)
    }

    fun getZombieKnockbackForce(): Double {
        return config.getDouble("misc.zombie-knockback-force", 0.8)
    }

    fun getTpButtonCountdown(): Int {
        return config.getInt("tp-button.countdown", 10)
    }

    fun getTpButtonForceDelay(): Int {
        return config.getInt("tp-button.force-delay", 5)
    }

    fun loadWeaponConfigs(): Map<String, WeaponConfig> {
        val weapons = mutableMapOf<String, WeaponConfig>()
        val section = config.getConfigurationSection("custom-weapons") ?: return weapons
        for (id in section.getKeys(false)) {
            val ws = section.getConfigurationSection(id) ?: continue
            val cfg = WeaponConfig(
                id = id,
                material = ws.getString("material") ?: "WOODEN_HOE",
                customModelData = ws.getInt("custom-model-data", 0),
                name = ws.getString("name") ?: id,
                lore = ws.getStringList("lore") ?: emptyList(),
                damage = ws.getDouble("damage", 5.0),
                ammoType = ws.getString("ammo-type") ?: "pistol_ammo",
                maxAmmo = ws.getInt("max-ammo", 12),
                price = ws.getInt("price", 300),
                cooldownTicks = ws.getInt("cooldown-ticks", 10),
                sound = ws.getString("sound"),
                knockback = ws.getDouble("knockback", 0.0),
                range = ws.getInt("range", 30),
                pellets = ws.getInt("pellets", 1),
                automatic = ws.getBoolean("automatic", false)
            )
            weapons[id] = cfg
        }
        return weapons
    }

    fun loadAmmoConfigs(): Map<String, AmmoConfig> {
        val ammos = mutableMapOf<String, AmmoConfig>()
        val section = config.getConfigurationSection("ammo-items") ?: return ammos
        for (id in section.getKeys(false)) {
            val as_ = section.getConfigurationSection(id) ?: continue
            val cfg = AmmoConfig(
                id = id,
                material = as_.getString("material") ?: "PAPER",
                customModelData = as_.getInt("custom-model-data", 0),
                name = as_.getString("name") ?: id,
                lore = as_.getStringList("lore") ?: emptyList()
            )
            ammos[id] = cfg
        }
        return ammos
    }

    fun addDoorFull(door: Door) {
        val doorSection = config.createSection("doors.${door.name}")
        doorSection.set("x1", door.minX)
        doorSection.set("y1", door.minY)
        doorSection.set("z1", door.minZ)
        doorSection.set("x2", door.maxX)
        doorSection.set("y2", door.maxY)
        doorSection.set("z2", door.maxZ)
        doorSection.set("delay", door.delay)
        doorSection.set("duration", door.duration)
        doorSection.set("door-number", door.doorNumber)
        doorSection.set("open-time", door.openTime)
        doorSection.set("close-time", door.closeTime)
        doorSection.set("material", door.material)
        doorSection.set("teleport-region", door.teleportRegion)
        doorSection.set("has-zombie-teleport", door.hasZombieTeleport)
        doorSection.set("special-teleport", door.specialTeleport)
        doorSection.set("mode", door.mode.name.lowercase())
        doorSection.set("use-scan-data", door.useScanData)
        if (door.blocks.isNotEmpty()) {
            val blocksSection = doorSection.createSection("blocks")
            door.blocks.forEach { (pos, mat) ->
                blocksSection.set(pos, mat)
            }
        }
        saveConfig()
    }

    fun addRespawn(respawn: Respawn) {
        val respawnSection = config.createSection("respawns.${respawn.name}")
        respawnSection.set("x", respawn.x)
        respawnSection.set("y", respawn.y)
        respawnSection.set("z", respawn.z)
        respawnSection.set("yaw", respawn.yaw)
        respawnSection.set("pitch", respawn.pitch)
        respawnSection.set("type", respawn.type.name.lowercase())
        if (respawn.doorNumber != null) {
            respawnSection.set("door-number", respawn.doorNumber)
        }
        if (respawn.roomNumber != null) {
            respawnSection.set("room-number", respawn.roomNumber)
        }
        saveConfig()
    }

    fun removeDoor(name: String) {
        config.set("doors.$name", null)
        saveConfig()
    }

    fun removeRespawn(name: String) {
        config.set("respawns.$name", null)
        saveConfig()
    }
}
