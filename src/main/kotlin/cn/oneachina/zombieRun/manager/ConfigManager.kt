package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.AmmoCategory
import cn.oneachina.zombieRun.model.Door
import cn.oneachina.zombieRun.model.Button
import cn.oneachina.zombieRun.model.Respawn
import cn.oneachina.zombieRun.model.SpecialDoorBehavior
import cn.oneachina.zombieRun.model.WeaponConfig
import cn.oneachina.zombieRun.model.BoltType
import cn.oneachina.zombieRun.model.FireMode
import io.papermc.paper.datacomponent.item.CustomModelData
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
            val doorMode = Door.DoorMode.fromString(modeStr)
            val openTime = doorSection.getInt("open-time", 15)
            val closeTime = doorSection.getInt("close-time", 15)
            val material = doorSection.getString("material", "STONE") ?: "STONE"
            val useScanData = doorSection.getBoolean("use-scan-data", false)
            val blocks = if (useScanData) {
                val fromFile = loadDoorScanData(name)
                if (fromFile.isNotEmpty()) fromFile else migrateLegacyInlineScanData(name, doorSection)
            } else emptyMap()

            val specialBehavior = loadSpecialBehavior(name, doorSection)
            val group = doorSection.getString("group")
            // player/zombie/start 门不参与门号
            val doorNumber = if (doorMode == Door.DoorMode.NORMAL) doorSection.getInt("door-number", 0) else 0

            val door = Door(
                name = name,
                minX = minOf(x1, x2),
                minY = minOf(y1, y2),
                minZ = minOf(z1, z2),
                maxX = maxOf(x1, x2),
                maxY = maxOf(y1, y2),
                maxZ = maxOf(z1, z2),
                openTime = openTime,
                closeTime = closeTime,
                doorNumber = doorNumber,
                material = material,
                specialBehavior = specialBehavior,
                mode = doorMode,
                useScanData = useScanData,
                blocks = blocks,
                group = group
            )
            doors.add(door)
        }
        return doors
    }

    private fun getDoorScanFile(name: String): File {
        val dir = File(plugin.dataFolder, "config/doors")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$name.scandata.yml")
    }

    private fun loadDoorScanData(name: String): Map<String, String> {
        val scanFile = getDoorScanFile(name)
        if (!scanFile.exists()) return emptyMap()

        val scanYaml = YamlConfiguration.loadConfiguration(scanFile)
        val blocks = mutableMapOf<String, String>()
        for (key in scanYaml.getKeys(false)) {
            val mat = scanYaml.getString(key)
            if (mat != null) blocks[key] = mat
        }
        return blocks
    }

    private fun loadSpecialBehavior(name: String, section: org.bukkit.configuration.ConfigurationSection): SpecialDoorBehavior? {
        val sb = section.getConfigurationSection("special-behavior") ?: return null
        val type = (sb.getString("type") ?: "").uppercase()
        return when (type) {
            "ELEVATOR" -> SpecialDoorBehavior.Elevator(
                humanTargetY = sb.getInt("human-target-y"),
                zombieTargetY = if (sb.contains("zombie-target-y")) sb.getInt("zombie-target-y") else null,
                countdown = sb.getInt("countdown", 5),
                departureMsg = sb.getString("departure-msg") ?: "<yellow>电梯即将到达……</yellow>",
                arrivalMsg = sb.getString("arrival-msg") ?: "<green>电梯已到达，祝您旅途愉快</green>"
            )
            "SUBWAY" -> SpecialDoorBehavior.Subway(
                humanTargetX = sb.getInt("human-target-x"),
                humanTargetY = sb.getInt("human-target-y"),
                humanTargetZ = sb.getInt("human-target-z"),
                zombieTargetX = if (sb.contains("zombie-target-x")) sb.getInt("zombie-target-x") else null,
                zombieTargetY = if (sb.contains("zombie-target-y")) sb.getInt("zombie-target-y") else null,
                zombieTargetZ = if (sb.contains("zombie-target-z")) sb.getInt("zombie-target-z") else null,
                lineName = sb.getString("line-name") ?: "1号线",
                departureMsg = sb.getString("departure-msg") ?: "<aqua>{line}即将发车……</aqua>",
                arrivalMsg = sb.getString("arrival-msg") ?: "<green>{line}已到站，请有序下车</green>"
            )
            "AIRPORT" -> SpecialDoorBehavior.Airport(
                humanTargetX = sb.getInt("human-target-x"),
                humanTargetY = sb.getInt("human-target-y"),
                humanTargetZ = sb.getInt("human-target-z"),
                zombieTargetX = if (sb.contains("zombie-target-x")) sb.getInt("zombie-target-x") else null,
                zombieTargetY = if (sb.contains("zombie-target-y")) sb.getInt("zombie-target-y") else null,
                zombieTargetZ = if (sb.contains("zombie-target-z")) sb.getInt("zombie-target-z") else null,
                delayTicks = sb.getLong("delay-ticks", 60),
                departureMsg = sb.getString("departure-msg") ?: "<green>感谢乘坐机场专线</green>",
                arrivalMsg = sb.getString("arrival-msg") ?: "<yellow>请拿好你的行李，有序下车</yellow>"
            )
            else -> {
                plugin.logger.warning("门 $name 的 special-behavior.type 未知: $type")
                null
            }
        }
    }

    private fun saveDoorScanData(name: String, blocks: Map<String, String>) {
        val scanFile = getDoorScanFile(name)
        val scanYaml = YamlConfiguration()
        blocks.forEach { (pos, mat) -> scanYaml.set(pos, mat) }
        try {
            scanYaml.save(scanFile)
        } catch (e: IOException) {
            plugin.logger.severe("门扫描数据保存失败 ${name}: ${e.message}")
        }
    }

    private fun deleteDoorScanData(name: String) {
        getDoorScanFile(name).delete()
    }

    private fun migrateLegacyInlineScanData(name: String, doorSection: org.bukkit.configuration.ConfigurationSection): Map<String, String> {
        val blocksSection = doorSection.getConfigurationSection("blocks") ?: return emptyMap()
        val blocks = mutableMapOf<String, String>()
        for (key in blocksSection.getKeys(false)) {
            val mat = blocksSection.getString(key)
            if (mat != null) blocks[key] = mat
        }
        if (blocks.isNotEmpty()) {
            saveDoorScanData(name, blocks)
            doorSection.set("blocks", null)
            try {
                config.save(configFile)
            } catch (_: IOException) {}
            plugin.logger.info("已迁移门 $name 的扫描数据到独立文件 (${blocks.size} 个方块)")
        }
        return blocks
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

            val button = Button(
                name = name,
                x = section.getInt("x"),
                y = section.getInt("y"),
                z = section.getInt("z"),
                mode = mode,
                doorNumber = doorNumber,
                doorNumbers = doorNumbers
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
        } else {
            section.set("door-number", null)
        }
        if (button.doorNumbers != null && button.doorNumbers.isNotEmpty()) {
            section.set("door-numbers", button.doorNumbers)
        } else {
            section.set("door-numbers", null)
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

    fun loadAmmoCategories(): Map<String, AmmoCategory> {
        val cats = mutableMapOf<String, AmmoCategory>()
        val section = config.getConfigurationSection("ammo-categories") ?: return cats
        for (id in section.getKeys(false)) {
            val cs = section.getConfigurationSection(id) ?: continue
            cats[id] = AmmoCategory(
                id = id,
                name = cs.getString("name") ?: id,
                itemMaterial = cs.getString("item-material") ?: "PAPER",
                customModelData = cs.getInt("custom-model-data", 0)
            )
        }
        return cats
    }

    fun loadWeaponConfigs(): Map<String, WeaponConfig> {
        val weapons = mutableMapOf<String, WeaponConfig>()
        val section = config.getConfigurationSection("custom-weapons") ?: return weapons
        for (id in section.getKeys(false)) {
            val ws = section.getConfigurationSection(id) ?: continue
            val cfg = WeaponConfig(
                id = id,
                material = ws.getString("material") ?: "WOODEN_HOE",
                customModelData = CustomModelData.customModelData().addFloat(ws.getInt("custom-model-data", 0).toFloat()).build(),
                name = ws.getString("name") ?: id,
                lore = ws.getStringList("lore"),
                damage = ws.getDouble("damage", 5.0),
                ammoCategory = ws.getString("ammo-category") ?: "light",
                magazineSize = ws.getInt("magazine-size", 12),
                maxReserve = ws.getInt("max-reserve", 60),
                reloadTimeTicks = ws.getInt("reload-time-ticks", 30),
                price = ws.getInt("price", 300),
                cooldownTicks = ws.getInt("cooldown-ticks", 10),
                spread = ws.getDouble("spread", 0.08),
                adsSpreadMult = ws.getDouble("ads-spread-mult", 0.5),
                adsRecoilMult = ws.getDouble("ads-recoil-mult", 0.7),
                headshotMult = ws.getDouble("headshot-mult", 2.0),
                knockback = ws.getDouble("knockback", 0.0),
                range = ws.getInt("range", 30),
                pellets = ws.getInt("pellets", 1),
                sound = ws.getString("sound"),
                hitSound = ws.getString("hit-sound"),
                recoil = ws.getDoubleList("recoil").toList(),
                spreadPerShot = ws.getDouble("spread-per-shot", 0.0),
                fireMode = parseFireMode(ws.getString("fire-mode")),
                boltType = parseBoltType(ws.getString("bolt-type")),
                burstCount = ws.getInt("burst-count", 3),
                aimTime = ws.getDouble("aim-time", 0.15).toFloat()
            )
            weapons[id] = cfg
        }
        return weapons
    }

    fun addDoorFull(door: Door) {
        val doorSection = config.createSection("doors.${door.name}")
        doorSection.set("x1", door.minX)
        doorSection.set("y1", door.minY)
        doorSection.set("z1", door.minZ)
        doorSection.set("x2", door.maxX)
        doorSection.set("y2", door.maxY)
        doorSection.set("z2", door.maxZ)
        doorSection.set("open-time", door.openTime)
        doorSection.set("close-time", door.closeTime)
        doorSection.set("door-number", door.doorNumber)
        doorSection.set("material", door.material)
        doorSection.set("mode", door.mode.name.lowercase())
        doorSection.set("use-scan-data", door.useScanData)
        doorSection.set("group", door.group)
        if (door.specialBehavior != null) {
            val sb = doorSection.createSection("special-behavior")
            when (val b = door.specialBehavior!!) {
                is SpecialDoorBehavior.Elevator -> {
                    sb.set("type", "ELEVATOR")
                    sb.set("human-target-y", b.humanTargetY)
                    b.zombieTargetY?.let { sb.set("zombie-target-y", it) }
                    sb.set("countdown", b.countdown)
                    sb.set("departure-msg", b.departureMsg)
                    sb.set("arrival-msg", b.arrivalMsg)
                }
                is SpecialDoorBehavior.Subway -> {
                    sb.set("type", "SUBWAY")
                    sb.set("human-target-x", b.humanTargetX)
                    sb.set("human-target-y", b.humanTargetY)
                    sb.set("human-target-z", b.humanTargetZ)
                    b.zombieTargetX?.let { sb.set("zombie-target-x", it) }
                    b.zombieTargetY?.let { sb.set("zombie-target-y", it) }
                    b.zombieTargetZ?.let { sb.set("zombie-target-z", it) }
                    sb.set("line-name", b.lineName)
                    sb.set("departure-msg", b.departureMsg)
                    sb.set("arrival-msg", b.arrivalMsg)
                }
                is SpecialDoorBehavior.Airport -> {
                    sb.set("type", "AIRPORT")
                    sb.set("human-target-x", b.humanTargetX)
                    sb.set("human-target-y", b.humanTargetY)
                    sb.set("human-target-z", b.humanTargetZ)
                    b.zombieTargetX?.let { sb.set("zombie-target-x", it) }
                    b.zombieTargetY?.let { sb.set("zombie-target-y", it) }
                    b.zombieTargetZ?.let { sb.set("zombie-target-z", it) }
                    sb.set("delay-ticks", b.delayTicks)
                    sb.set("departure-msg", b.departureMsg)
                    sb.set("arrival-msg", b.arrivalMsg)
                }
            }
        }
        if (door.useScanData && door.blocks.isNotEmpty()) {
            saveDoorScanData(door.name, door.blocks)
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
        deleteDoorScanData(name)
        saveConfig()
    }

    fun removeRespawn(name: String) {
        config.set("respawns.$name", null)
        saveConfig()
    }

    // ---- 武器配置解析 ----

    private fun parseFireMode(s: String?): FireMode = when (s?.uppercase()) {
        "SEMI" -> FireMode.SEMI
        "BURST" -> FireMode.BURST
        else -> FireMode.AUTO
    }

    private fun parseBoltType(s: String?): BoltType = when (s?.uppercase()) {
        "CLOSED_BOLT" -> BoltType.CLOSED_BOLT
        "MANUAL_ACTION" -> BoltType.MANUAL_ACTION
        else -> BoltType.OPEN_BOLT
    }

    // ---- 新增配置文件加载 ----

    fun loadCombatConfig(): CombatConfig {
        val file = File(plugin.dataFolder, "config/combat.yml")
        if (!file.exists()) plugin.saveResource("config/combat.yml", false)
        val cfg = YamlConfiguration.loadConfiguration(file)
        return CombatConfig(
            swordDamage = cfg.getDouble("sword-damage", 5.0),
            zombieDamage = cfg.getDouble("zombie-damage", 6.0),
            zombieMainDamage = cfg.getDouble("zombie-main-damage", 10.0),
            zombieMaxHealth = cfg.getDouble("zombie-max-health", 200.0),
            zombieMainMaxHealth = cfg.getDouble("zombie-main-max-health", 500.0),
            humanMaxHealth = cfg.getDouble("human-max-health", 20.0)
        )
    }

    fun loadEconomyConfig(): EconomyConfig {
        val file = File(plugin.dataFolder, "config/economy.yml")
        if (!file.exists()) plugin.saveResource("config/economy.yml", false)
        val cfg = YamlConfiguration.loadConfiguration(file)
        return EconomyConfig(
            killZombieCoins = cfg.getInt("kill-zombie-coins", 50),
            killZombieMainCoins = cfg.getInt("kill-zombie-main-coins", 150),
            infectHumanCoins = cfg.getInt("infect-human-coins", 50),
            surviveHumanCoins = cfg.getInt("survive-human-coins", 200),
            headshotXp = cfg.getInt("headshot-xp", 5),
            killZombieXp = cfg.getInt("kill-zombie-xp", 30),
            killZombieMainXp = cfg.getInt("kill-zombie-main-xp", 10),
            infectHumanXp = cfg.getInt("infect-human-xp", 20),
            passDoorXp = cfg.getInt("pass-door-xp", 5),
            humanWinXp = cfg.getInt("human-win-xp", 100),
            participateXp = cfg.getInt("participate-xp", 50)
        )
    }

    fun loadBalanceConfig(): BalanceConfig {
        val file = File(plugin.dataFolder, "config/balance.yml")
        if (!file.exists()) plugin.saveResource("config/balance.yml", false)
        val cfg = YamlConfiguration.loadConfiguration(file)
        return BalanceConfig(
            doorOpenCooldownMs = cfg.getLong("door-open-cooldown-ms", 3000L),
            transferCountdownSec = cfg.getInt("transfer-countdown-sec", 10),
            helicopterCountdownSec = cfg.getInt("helicopter-countdown-sec", 30),
            infectCountdownSec = cfg.getInt("infect-countdown-sec", 5),
            respawnDelayTicks = cfg.getLong("respawn-delay-ticks", 100L),
            adsSpeedMultiplier = cfg.getDouble("ads-speed-multiplier", 0.4),
            defaultMoveSpeed = cfg.getDouble("default-move-speed", 0.1)
        )
    }
}

data class CombatConfig(
    val swordDamage: Double,
    val zombieDamage: Double,
    val zombieMainDamage: Double,
    val zombieMaxHealth: Double,
    val zombieMainMaxHealth: Double,
    val humanMaxHealth: Double
)

data class EconomyConfig(
    val killZombieCoins: Int,
    val killZombieMainCoins: Int,
    val infectHumanCoins: Int,
    val surviveHumanCoins: Int,
    val headshotXp: Int,
    val killZombieXp: Int,
    val killZombieMainXp: Int,
    val infectHumanXp: Int,
    val passDoorXp: Int,
    val humanWinXp: Int,
    val participateXp: Int
)

data class BalanceConfig(
    val doorOpenCooldownMs: Long,
    val transferCountdownSec: Int,
    val helicopterCountdownSec: Int,
    val infectCountdownSec: Int,
    val respawnDelayTicks: Long,
    val adsSpeedMultiplier: Double,
    val defaultMoveSpeed: Double
)
