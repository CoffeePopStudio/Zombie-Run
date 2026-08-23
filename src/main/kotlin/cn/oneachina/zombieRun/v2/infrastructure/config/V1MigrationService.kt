package cn.oneachina.zombierun.v2.infrastructure.config

import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonMode
import cn.oneachina.zombierun.v2.domain.arena.RespawnDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnType
import cn.oneachina.zombierun.v2.domain.door.BlockRegion
import cn.oneachina.zombierun.v2.domain.door.DoorBehavior
import cn.oneachina.zombierun.v2.domain.door.DoorBehaviorType
import cn.oneachina.zombierun.v2.domain.door.DoorDefinition
import cn.oneachina.zombierun.v2.domain.door.DoorMode
import cn.oneachina.zombierun.v2.domain.door.Portal
import cn.oneachina.zombierun.v2.domain.door.PortalAxis
import cn.oneachina.zombierun.v2.domain.door.PortalFront
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * v1 `config/config.yml` → v2 `config/arenas` 目录一次性迁移。
 * 门/按钮/重生点可迁移；v1 方块快照、特殊门行为暂需人工复查。
 */
class V1MigrationService(
    private val dataFolder: File,
    private val arenaRepository: ArenaYamlRepository,
    private val logger: V2Logger,
) {
    data class MigrationReport(
        val world: String?,
        val doorsMigrated: Int,
        val buttonsMigrated: Int,
        val respawnsMigrated: Int,
        val skipped: List<String>,
    )

    fun migrate(): MigrationReport {
        val v1Folder = File(dataFolder.parentFile, "zombie-run")
        val v1Config = File(v1Folder, "config/config.yml")
        if (!v1Config.exists()) {
            logger.warn("v1 config not found: $v1Config")
            return MigrationReport(null, 0, 0, 0, listOf("v1 config not found: $v1Config"))
        }

        val yaml = YamlConfiguration.loadConfiguration(v1Config)
        val world = yaml.getString("game.world") ?: "world"
        val doors = migrateDoors(yaml.getConfigurationSection("doors"), world)
        val buttons = migrateButtons(yaml.getConfigurationSection("buttons"), world)
        val respawns = migrateRespawns(yaml.getConfigurationSection("respawns"), world)

        val arenaName = "migrated_v1"
        arenaRepository.save(ArenaDefinition(arenaName, world, doors, buttons, respawns))
        logger.info("v1 migration saved arena '$arenaName': doors=${doors.size}, buttons=${buttons.size}, respawns=${respawns.size}")
        return MigrationReport(world, doors.size, buttons.size, respawns.size, emptyList())
    }

    private fun migrateDoors(section: ConfigurationSection?, world: String): List<DoorDefinition> {
        if (section == null) return emptyList()
        val doors = mutableListOf<DoorDefinition>()
        val skipped = mutableListOf<String>()
        section.getKeys(false).forEach { id ->
            val d = section.getConfigurationSection(id) ?: return@forEach
            val x1 = d.getInt("x1"); val y1 = d.getInt("y1"); val z1 = d.getInt("z1")
            val x2 = d.getInt("x2"); val y2 = d.getInt("y2"); val z2 = d.getInt("z2")
            val axis = if (x1 == x2) PortalAxis.X else if (z1 == z2) PortalAxis.Z else null
            if (axis == null) {
                skipped += "door $id: not an X/Z plane, skipped"
                return@forEach
            }
            val mode = DoorMode.entries.firstOrNull {
                it.name.equals(d.getString("mode", "normal"), ignoreCase = true)
            } ?: DoorMode.NORMAL
            val number = d.getString("door-number")?.toIntOrNull()
            val region = BlockRegion(
                minX = minOf(x1, x2), minY = minOf(y1, y2), minZ = minOf(z1, z2),
                maxX = maxOf(x1, x2), maxY = maxOf(y1, y2), maxZ = maxOf(z1, z2),
            )
            val plane = if (axis == PortalAxis.X) x1.toDouble() else z1.toDouble()
            val portal = if (axis == PortalAxis.X) {
                Portal(axis, PortalFront.POSITIVE, plane, minOf(z1, z2).toDouble(), maxOf(z1, z2).toDouble(), minOf(y1, y2).toDouble(), maxOf(y1, y2).toDouble())
            } else {
                Portal(axis, PortalFront.POSITIVE, plane, minOf(x1, x2).toDouble(), maxOf(x1, x2).toDouble(), minOf(y1, y2).toDouble(), maxOf(y1, y2).toDouble())
            }
            doors += DoorDefinition(
                id = "v1_$id",
                world = world,
                number = number,
                mode = mode,
                group = d.getString("group"),
                openSeconds = d.getInt("open-time", 15),
                closeSeconds = d.getInt("close-time", 15),
                portal = portal,
                region = region,
                snapshotId = null,
                fallbackMaterial = "STONE",
                behavior = parseBehavior(d.getConfigurationSection("special-behavior")),
            )
        }
        if (skipped.isNotEmpty()) logger.warn("v1 migration skipped doors: $skipped")
        return doors
    }

    private fun migrateButtons(section: ConfigurationSection?, world: String): List<ButtonDefinition> {
        if (section == null) return emptyList()
        return section.getKeys(false).mapNotNull { id ->
            val b = section.getConfigurationSection(id) ?: return@mapNotNull null
            val mode = ButtonMode.entries.firstOrNull {
                it.name.equals(b.getString("mode", "normal"), ignoreCase = true)
            } ?: ButtonMode.NORMAL
            val doorNumbers = if (mode == ButtonMode.NORMAL) {
                listOfNotNull(b.getString("door-number")?.toIntOrNull())
            } else {
                emptyList()
            }
            ButtonDefinition("v1_$id", world, b.getInt("x"), b.getInt("y"), b.getInt("z"), mode, doorNumbers)
        }
    }

    private fun migrateRespawns(section: ConfigurationSection?, world: String): List<RespawnDefinition> {
        if (section == null) return emptyList()
        return section.getKeys(false).mapNotNull { id ->
            val r = section.getConfigurationSection(id) ?: return@mapNotNull null
            val type = RespawnType.entries.firstOrNull {
                it.name.equals(r.getString("type"), ignoreCase = true)
            } ?: return@mapNotNull null
            RespawnDefinition(
                id = "v1_$id",
                world = world,
                type = type,
                x = r.getDouble("x"),
                y = r.getDouble("y"),
                z = r.getDouble("z"),
                yaw = r.getDouble("yaw", 0.0).toFloat(),
                pitch = r.getDouble("pitch", 0.0).toFloat(),
                doorNumber = r.getString("door-number")?.toIntOrNull(),
            )
        }
    }

    private fun parseBehavior(section: ConfigurationSection?): DoorBehavior? {
        if (section == null) return null
        val type = DoorBehaviorType.entries.firstOrNull {
            it.name.equals(section.getString("type"), ignoreCase = true)
        } ?: return null
        fun d(key: String): Double? =
            if (section.contains(key)) section.getDouble(key) else null
        fun s(key: String): String? = section.getString(key)
        return DoorBehavior(
            type = type,
            humanTargetX = d("human-target-x"),
            humanTargetY = d("human-target-y"),
            humanTargetZ = d("human-target-z"),
            zombieTargetX = d("zombie-target-x"),
            zombieTargetY = d("zombie-target-y"),
            zombieTargetZ = d("zombie-target-z"),
            lineName = s("line-name"),
            countdown = section.getInt("countdown", 5),
            delayTicks = section.getLong("delay-ticks", 0),
            departureMessage = s("departure-msg"),
            arrivalMessage = s("arrival-msg"),
        )
    }
}