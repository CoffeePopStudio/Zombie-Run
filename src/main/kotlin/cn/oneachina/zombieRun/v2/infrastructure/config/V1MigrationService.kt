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
import cn.oneachina.zombierun.v2.domain.player.PlayerProfile
import cn.oneachina.zombierun.v2.ports.PlayerDataPort
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.sql.DriverManager
import java.util.UUID

/**
 * v1 `config/config.yml` → v2 `config/arenas` 目录一次性迁移。
 * 门/按钮/重生点可迁移；v1 方块快照、特殊门行为暂需人工复查。
 * 另支持 v1 `data/zr_economy.db` 玩家数据迁移到 v2 SQLite。
 */
class V1MigrationService(
    private val dataFolder: File,
    private val arenaRepository: ArenaYamlRepository,
    private val playerDataPort: PlayerDataPort,
    private val snapshotStore: BlockSnapshotStore,
    private val logger: V2Logger,
) {
    data class MigrationReport(
        val world: String?,
        val doorsMigrated: Int,
        val buttonsMigrated: Int,
        val respawnsMigrated: Int,
        val snapshotsImported: Int,
        val snapshotsAttached: Int,
        val skipped: List<String>,
    )

    data class DataMigrationReport(
        val playersMigrated: Int,
        val playersSkipped: Int,
        val totalCoins: Long,
        val titlesImported: Int,
        val skipped: List<String>,
    )

    /** 从 v1 `data/zr_economy.db` 导入玩家硬币/等级/经验/称号/击杀到 v2。 */
    fun migrateData(overwriteExisting: Boolean = false): DataMigrationReport {
        val v1Db = File(File(dataFolder.parentFile, "zombie-run"), "data/zr_economy.db")
        if (!v1Db.exists()) {
            logger.warn("v1 data db not found: $v1Db")
            return DataMigrationReport(0, 0, 0, 0, listOf("v1 data db not found: $v1Db"))
        }

        val skipped = mutableListOf<String>()
        var playersMigrated = 0
        var playersSkipped = 0
        var totalCoins = 0L
        var titlesImported = 0

        try {
            DriverManager.getConnection("jdbc:sqlite:${v1Db.absolutePath.replace('\\', '/')}").use { conn ->
                conn.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
                val economy = HashMap<String, Int>()
                conn.createStatement().executeQuery("SELECT uuid, coins FROM zr_economy").use { rs ->
                    while (rs.next()) {
                        val uuid = rs.getString("uuid")
                        if (uuid != null) economy[uuid] = rs.getInt("coins")
                    }
                }

                val progression = HashMap<String, PlayerProfile>()
                conn.createStatement().executeQuery(
                    "SELECT uuid, level, xp, total_kills, equipped_title, total_infections, games_played, human_wins FROM player_progression",
                ).use { rs ->
                    while (rs.next()) {
                        val uuid = rs.getString("uuid") ?: continue
                        val id = runCatching { UUID.fromString(uuid) }.getOrNull() ?: continue
                        val coins = economy[uuid] ?: 0
                        val profile = PlayerProfile(
                            playerId = id,
                            coins = coins,
                            xp = rs.getInt("xp"),
                            level = rs.getInt("level").coerceAtLeast(1),
                            title = rs.getString("equipped_title")?.takeIf { it.isNotBlank() },
                            zombieKills = rs.getInt("total_kills"),
                            totalInfections = rs.getInt("total_infections"),
                            gamesPlayed = rs.getInt("games_played"),
                            humanWins = rs.getInt("human_wins"),
                        )
                        progression[uuid] = profile
                    }
                }

                progression.forEach { (uuid, profile) ->
                    val existing = playerDataPort.load(profile.playerId)
                    if (existing != null && !overwriteExisting) {
                        playersSkipped++
                        skipped += "player $uuid already exists; skip (use /zr2 v1 migrate-data --overwrite to replace)"
                        return@forEach
                    }
                    playerDataPort.save(profile)
                    if (profile.title != null) titlesImported++
                    playersMigrated++
                    totalCoins += profile.coins
                    logger.info("v1 data migrated player ${profile.playerId} (${profile.coins} coins, title=${profile.title ?: "-"})")
                }
            }
        } catch (e: Exception) {
            logger.severe("v1 data migration failed: ${e.message}")
            skipped += "migration error: ${e.message}"
        }

        logger.info("v1 data migration done: players=$playersMigrated skipped=$playersSkipped")
        return DataMigrationReport(playersMigrated, playersSkipped, totalCoins, titlesImported, skipped)
    }

    fun migrate(): MigrationReport {
        val v1Folder = File(dataFolder.parentFile, "zombie-run")
        val v1Config = File(v1Folder, "config/config.yml")
        if (!v1Config.exists()) {
            logger.warn("v1 config not found: $v1Config")
            return MigrationReport(null, 0, 0, 0, 0, 0, listOf("v1 config not found: $v1Config"))
        }

        val yaml = YamlConfiguration.loadConfiguration(v1Config)
        val world = yaml.getString("game.world") ?: "world"
        val doors = migrateDoors(yaml.getConfigurationSection("doors"), world)
        val buttons = migrateButtons(yaml.getConfigurationSection("buttons"), world)
        val respawns = migrateRespawns(yaml.getConfigurationSection("respawns"), world)

        // 导入所有 v1 门快照到 v2 存储（数据不丢），再按区域匹配关联
        val v1Snapshots = loadV1Snapshots(File(v1Folder, "config/doors"))
        v1Snapshots.forEach { (id, blocks) -> snapshotStore.save(id, blocks) }
        val (doorsWithSnapshots, snapshotsAttached) = attachSnapshots(doors, v1Snapshots)

        val arenaName = "migrated_v1"
        arenaRepository.save(ArenaDefinition(arenaName, world, doorsWithSnapshots, buttons, respawns))
        logger.info("v1 migration saved arena '$arenaName': doors=${doorsWithSnapshots.size}, buttons=${buttons.size}, respawns=${respawns.size}, snapshots imported=${v1Snapshots.size} attached=$snapshotsAttached")
        return MigrationReport(world, doorsWithSnapshots.size, buttons.size, respawns.size, v1Snapshots.size, snapshotsAttached, emptyList())
    }

    private fun migrateDoors(section: ConfigurationSection?, world: String): List<DoorDefinition> {
        if (section == null) return emptyList()
        val doors = mutableListOf<DoorDefinition>()
        val skipped = mutableListOf<String>()
        section.getKeys(false).forEach { id ->
            val d = section.getConfigurationSection(id) ?: return@forEach
            val x1 = d.getInt("x1"); val y1 = d.getInt("y1"); val z1 = d.getInt("z1")
            val x2 = d.getInt("x2"); val y2 = d.getInt("y2"); val z2 = d.getInt("z2")
            val axis = when {
                x1 == x2 -> PortalAxis.X
                z1 == z2 -> PortalAxis.Z
                y1 == y2 -> PortalAxis.Y
                else -> null
            }
            if (axis == null) {
                skipped += "door $id: not an X/Z/Y plane, skipped"
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
            val plane = when (axis) {
                PortalAxis.X -> x1.toDouble()
                PortalAxis.Z -> z1.toDouble()
                PortalAxis.Y -> y1.toDouble()
            }
            // v1 的 reverse-direction 隐藏选项映射为 v2 的 front（ARCHITECTURE.md 5.4）
            val reverse = d.getBoolean("reverse-direction", false) || d.getBoolean("reverse_direction", false)
            val front = if (reverse) PortalFront.NEGATIVE else PortalFront.POSITIVE
            val portal = when (axis) {
                PortalAxis.X -> Portal(axis, front, plane, minOf(z1, z2).toDouble(), maxOf(z1, z2).toDouble(), minOf(y1, y2).toDouble(), maxOf(y1, y2).toDouble())
                PortalAxis.Z -> Portal(axis, front, plane, minOf(x1, x2).toDouble(), maxOf(x1, x2).toDouble(), minOf(y1, y2).toDouble(), maxOf(y1, y2).toDouble())
                PortalAxis.Y -> Portal(axis, front, plane, minOf(x1, x2).toDouble(), maxOf(x1, x2).toDouble(), minOf(z1, z2).toDouble(), maxOf(z1, z2).toDouble())
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

    // ---------- 门方块快照迁移 ----------

    // 读取 v1 config/doors/*.scandata.yml，key 为 "x,y,z"，value 为材质名。
    private fun loadV1Snapshots(dir: File): Map<String, Map<String, String>> {
        if (!dir.exists()) return emptyMap()
        val result = LinkedHashMap<String, Map<String, String>>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".scandata.yml") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val blocks = LinkedHashMap<String, String>()
                file.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEach
                    val idx = trimmed.indexOf(':')
                    if (idx <= 0) return@forEach
                    blocks[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
                }
                if (blocks.isNotEmpty()) {
                    result[file.name.removeSuffix(".scandata.yml")] = blocks
                }
            }
        logger.info("loaded ${result.size} v1 door snapshot(s) from ${dir.absolutePath}")
        return result
    }

    // 将已导入 v2 存储的 v1 快照按“方块坐标落在门区域内”的最佳匹配关联到门。
    // 返回 (带 snapshotId 的门列表, 关联成功的快照数)。
    private fun attachSnapshots(
        doors: List<DoorDefinition>,
        v1Snapshots: Map<String, Map<String, String>>,
    ): Pair<List<DoorDefinition>, Int> {
        if (v1Snapshots.isEmpty() || doors.isEmpty()) return doors to 0

        var attached = 0
        val snapshotIds = LinkedHashSet<String>()
        val result = doors.map { door ->
            val region = door.region
            val best = v1Snapshots.entries.maxByOrNull { (_, blocks) ->
                blocks.keys.count { key ->
                    coordOf(key)?.let { (x, y, z) ->
                        x in region.minX..region.maxX && y in region.minY..region.maxY && z in region.minZ..region.maxZ
                    } == true
                }
            }
            val bestId = best?.key ?: return@map door
            val overlap = best.value.keys.count { key ->
                coordOf(key)?.let { (x, y, z) ->
                    x in region.minX..region.maxX && y in region.minY..region.maxY && z in region.minZ..region.maxZ
                } == true
            }
            if (overlap == 0) return@map door

            snapshotIds += bestId
            attached++
            door.copy(snapshotId = bestId)
        }
        if (attached > 0) {
            logger.info("attached $attached v1 door snapshot(s): ${snapshotIds.joinToString(", ")}")
        }
        return result to attached
    }

    private fun coordOf(key: String): Triple<Int, Int, Int>? {
        val parts = key.split(',')
        if (parts.size != 3) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        val z = parts[2].trim().toIntOrNull() ?: return null
        return Triple(x, y, z)
    }
}