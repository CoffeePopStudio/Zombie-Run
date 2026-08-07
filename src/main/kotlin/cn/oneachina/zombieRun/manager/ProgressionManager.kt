package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.PlayerProfile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class ProgressionManager(private val plugin: ZombieRun) {

    companion object {
        const val MAX_LEVEL = 50

        fun xpForLevel(level: Int): Int = level * 500 + level * level * 50
    }

    private val cache = ConcurrentHashMap<UUID, PlayerProfile>()

    fun init() {
        plugin.databaseManager.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_progression (
                        uuid VARCHAR(36) PRIMARY KEY,
                        level INT DEFAULT 1,
                        xp INT DEFAULT 0,
                        total_kills INT DEFAULT 0,
                        total_infections INT DEFAULT 0,
                        games_played INT DEFAULT 0,
                        human_wins INT DEFAULT 0,
                        equipped_title VARCHAR(64)
                    )
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_unlocks (
                        uuid VARCHAR(36),
                        unlock_id VARCHAR(64),
                        unlocked_at BIGINT,
                        PRIMARY KEY (uuid, unlock_id)
                    )
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_quests (
                        uuid VARCHAR(36),
                        quest_id VARCHAR(64),
                        progress INT DEFAULT 0,
                        completed INT DEFAULT 0,
                        date VARCHAR(10),
                        PRIMARY KEY (uuid, quest_id, date)
                    )
                """.trimIndent())
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_player_quests_date
                    ON player_quests (uuid, date)
                """.trimIndent())
            }
        }
        plugin.logger.info("ProgressionManager 已初始化 (共享 DatabaseManager)")
    }

    fun getConnection(): Connection = plugin.databaseManager.getConnection()

    /** 异步加载玩家档案并写入缓存（进服时预热，避免阻塞主线程） */
    fun loadPlayerAsync(uuid: UUID) {
        plugin.databaseManager.runAsync { conn ->
            val profile = conn.prepareStatement(
                "SELECT level, xp, total_kills, total_infections, games_played, human_wins, equipped_title FROM player_progression WHERE uuid = ?"
            ).use { stmt ->
                stmt.setString(1, uuid.toString())
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    PlayerProfile(
                        uuid = uuid,
                        level = rs.getInt("level"),
                        xp = rs.getInt("xp"),
                        totalKills = rs.getInt("total_kills"),
                        totalInfections = rs.getInt("total_infections"),
                        gamesPlayed = rs.getInt("games_played"),
                        humanWins = rs.getInt("human_wins"),
                        equippedTitle = rs.getString("equipped_title")
                    )
                } else {
                    conn.prepareStatement(
                        "INSERT INTO player_progression (uuid) VALUES (?)"
                    ).use { ins ->
                        ins.setString(1, uuid.toString())
                        ins.executeUpdate()
                    }
                    PlayerProfile(uuid = uuid)
                }
            }
            cache[uuid] = profile
        }
    }

    /**
     * 同步加载玩家档案（仅 cache miss 时调用；SQLite 本地查询毫秒级，避免 supplyAsync+.get()
     * 的伪异步阻塞）。用 putIfAbsent 写入，防止覆盖 loadPlayerAsync 刚预热的最新档案。
     */
    fun loadPlayer(uuid: UUID): PlayerProfile {
        val profile = getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT level, xp, total_kills, total_infections, games_played, human_wins, equipped_title FROM player_progression WHERE uuid = ?"
            ).use { stmt ->
                stmt.setString(1, uuid.toString())
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    PlayerProfile(
                        uuid = uuid,
                        level = rs.getInt("level"),
                        xp = rs.getInt("xp"),
                        totalKills = rs.getInt("total_kills"),
                        totalInfections = rs.getInt("total_infections"),
                        gamesPlayed = rs.getInt("games_played"),
                        humanWins = rs.getInt("human_wins"),
                        equippedTitle = rs.getString("equipped_title")
                    )
                } else {
                    conn.prepareStatement(
                        "INSERT INTO player_progression (uuid) VALUES (?)"
                    ).use { ins ->
                        ins.setString(1, uuid.toString())
                        ins.executeUpdate()
                    }
                    PlayerProfile(uuid = uuid)
                }
            }
        }
        cache.putIfAbsent(uuid, profile)
        return cache[uuid] ?: profile
    }

    fun savePlayer(uuid: UUID) {
        val profile = cache[uuid] ?: return
        CompletableFuture.runAsync {
            getConnection().use { conn ->
                conn.prepareStatement("""
                    UPDATE player_progression SET level = ?, xp = ?, total_kills = ?,
                    total_infections = ?, games_played = ?, human_wins = ?, equipped_title = ?
                    WHERE uuid = ?
                """.trimIndent()).use { stmt ->
                    stmt.setInt(1, profile.level)
                    stmt.setInt(2, profile.xp)
                    stmt.setInt(3, profile.totalKills)
                    stmt.setInt(4, profile.totalInfections)
                    stmt.setInt(5, profile.gamesPlayed)
                    stmt.setInt(6, profile.humanWins)
                    stmt.setString(7, profile.equippedTitle)
                    stmt.setString(8, uuid.toString())
                    stmt.executeUpdate()
                }
            }
        }
        cache.remove(uuid)
    }

    fun getProfile(uuid: UUID): PlayerProfile {
        return cache.getOrPut(uuid) { loadPlayer(uuid) }
    }

    fun addXp(player: Player, amount: Int, reason: String) {
        val profile = getProfile(player.uniqueId)
        if (profile.level >= MAX_LEVEL) return

        profile.xp += amount

        var needed = xpForLevel(profile.level)
        while (profile.xp >= needed && profile.level < MAX_LEVEL) {
            profile.xp -= needed
            profile.level++
            needed = xpForLevel(profile.level)

            player.showTitle(Title.title(
                Component.text("⬆ 升级！", NamedTextColor.GOLD),
                Component.text()
                    .append(Component.text("Lv.${profile.level - 1} → ", NamedTextColor.WHITE))
                    .append(Component.text("Lv.${profile.level}", NamedTextColor.YELLOW))
                    .build(),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMillis(500))
            ))
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
            val lvlUpMsg = Component.text()
                .append(Component.text(player.name, NamedTextColor.GOLD))
                .append(Component.text(" 升级到 ", NamedTextColor.GOLD))
                .append(Component.text("Lv.${profile.level}", NamedTextColor.YELLOW))
                .append(Component.text("！", NamedTextColor.GOLD))
                .build()
            plugin.gameManager.getWorldPlayers(player.world.name).forEach { it.sendMessage(lvlUpMsg) }

            handleLevelUnlock(player.uniqueId, profile.level)
        }

        updateAsync(player.uniqueId)
        player.sendActionBar(buildActionBar(profile))
    }

    private fun handleLevelUnlock(uuid: UUID, level: Int) {
        val player = Bukkit.getPlayer(uuid) ?: return
        when (level) {
            5 -> unlock(uuid, "title_survivor", player,
                Component.text()
                    .append(Component.text("解锁称号：", NamedTextColor.GREEN))
                    .append(Component.text("「幸存者」", NamedTextColor.YELLOW))
                    .build())
            10 -> unlock(uuid, "title_escape_expert", player,
                Component.text()
                    .append(Component.text("解锁称号：", NamedTextColor.GREEN))
                    .append(Component.text("「逃生专家」", NamedTextColor.YELLOW))
                    .build())
            15 -> unlock(uuid, "golden_pistol", player, Component.text("解锁金色手枪皮肤！", NamedTextColor.GREEN))
            20 -> unlock(uuid, "title_elite_agent", player,
                Component.text()
                    .append(Component.text("解锁称号：", NamedTextColor.GREEN))
                    .append(Component.text("「精英特工」", NamedTextColor.YELLOW))
                    .build())
            25 -> unlock(uuid, "title_light_of_hope", player,
                Component.text()
                    .append(Component.text("解锁称号：", NamedTextColor.GREEN))
                    .append(Component.text("「希望之光」", NamedTextColor.YELLOW))
                    .build())
            30 -> unlock(uuid, "title_legend", player,
                Component.text()
                    .append(Component.text("解锁称号：", NamedTextColor.GREEN))
                    .append(Component.text("「传奇」", NamedTextColor.YELLOW))
                    .build())
            35 -> unlock(uuid, "title_butcher", player,
                Component.text()
                    .append(Component.text("解锁称号：", NamedTextColor.GREEN))
                    .append(Component.text("「屠夫」", NamedTextColor.YELLOW))
                    .build())
            40 -> unlock(uuid, "title_undying", player,
                Component.text()
                    .append(Component.text("解锁称号：", NamedTextColor.GREEN))
                    .append(Component.text("「不死之身」", NamedTextColor.YELLOW))
                    .build())
            50 -> unlock(uuid, "golden_rifle", player, Component.text("解锁金色步枪皮肤！", NamedTextColor.GREEN))
        }
    }

    private fun unlock(uuid: UUID, unlockId: String, player: Player, msg: Component) {
        CompletableFuture.runAsync {
            getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO player_unlocks (uuid, unlock_id, unlocked_at) VALUES (?, ?, ?)"
                ).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, unlockId)
                    stmt.setLong(3, System.currentTimeMillis())
                    stmt.executeUpdate()
                }
            }
        }
        plugin.titleManager.invalidateUnlockCache(uuid)
        player.sendMessage(msg)
    }

    fun getUnlocks(uuid: UUID): Set<String> {
        return CompletableFuture.supplyAsync {
            getConnection().use { conn ->
                conn.prepareStatement(
                    "SELECT unlock_id FROM player_unlocks WHERE uuid = ?"
                ).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    val rs = stmt.executeQuery()
                    val set = mutableSetOf<String>()
                    while (rs.next()) set.add(rs.getString("unlock_id"))
                    set
                }
            }
        }.get()
    }

    fun isUnlocked(uuid: UUID, unlockId: String): Boolean {
        return getUnlocks(uuid).contains(unlockId)
    }

    fun getLevel(uuid: UUID): Int = getProfile(uuid).level
    fun getXp(uuid: UUID): Int = getProfile(uuid).xp
    fun getTotalKills(uuid: UUID): Int = getProfile(uuid).totalKills
    fun getTotalInfections(uuid: UUID): Int = getProfile(uuid).totalInfections
    fun getGamesPlayed(uuid: UUID): Int = getProfile(uuid).gamesPlayed
    fun getHumanWins(uuid: UUID): Int = getProfile(uuid).humanWins
    fun getEquippedTitle(uuid: UUID): String? = getProfile(uuid).equippedTitle

    fun setEquippedTitle(uuid: UUID, title: String?) {
        val profile = getProfile(uuid)
        profile.equippedTitle = title
        updateAsync(uuid)
    }

    fun addTotalKill(uuid: UUID) {
        val profile = getProfile(uuid)
        profile.totalKills++
        updateAsync(uuid)
    }

    fun addTotalInfection(uuid: UUID) {
        val profile = getProfile(uuid)
        profile.totalInfections++
        updateAsync(uuid)
    }

    fun addGamePlayed(uuid: UUID) {
        val profile = getProfile(uuid)
        profile.gamesPlayed++
        updateAsync(uuid)
    }

    fun addHumanWin(uuid: UUID) {
        val profile = getProfile(uuid)
        profile.humanWins++
        updateAsync(uuid)
    }

    fun setXp(uuid: UUID, xp: Int) {
        val profile = getProfile(uuid)
        profile.xp = xp.coerceAtLeast(0)
        updateAsync(uuid)
    }

    fun setLevel(uuid: UUID, level: Int) {
        val profile = getProfile(uuid)
        profile.level = level.coerceIn(1, MAX_LEVEL)
        updateAsync(uuid)
    }

    private fun buildActionBar(profile: PlayerProfile): Component {
        val needed = if (profile.level < MAX_LEVEL) xpForLevel(profile.level) else profile.xp
        return Component.text()
            .append(Component.text("Lv.${profile.level}", NamedTextColor.AQUA))
            .append(Component.text("  |  ", NamedTextColor.WHITE))
            .append(Component.text("${profile.xp}", NamedTextColor.GREEN))
            .append(Component.text(" / ", NamedTextColor.WHITE))
            .append(Component.text("$needed XP", NamedTextColor.GRAY))
            .build()
    }

    private fun updateAsync(uuid: UUID) {
        val profile = cache[uuid] ?: return
        CompletableFuture.runAsync {
            getConnection().use { conn ->
                conn.prepareStatement("""
                    UPDATE player_progression SET level = ?, xp = ?, total_kills = ?,
                    total_infections = ?, games_played = ?, human_wins = ?, equipped_title = ?
                    WHERE uuid = ?
                """.trimIndent()).use { stmt ->
                    stmt.setInt(1, profile.level)
                    stmt.setInt(2, profile.xp)
                    stmt.setInt(3, profile.totalKills)
                    stmt.setInt(4, profile.totalInfections)
                    stmt.setInt(5, profile.gamesPlayed)
                    stmt.setInt(6, profile.humanWins)
                    stmt.setString(7, profile.equippedTitle)
                    stmt.setString(8, uuid.toString())
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun flushAll() {
        cache.forEach { (uuid, _) ->
            runCatching { updateAsync(uuid) }
        }
        cache.clear()
    }

    fun resetPlayer(uuid: UUID) {
        CompletableFuture.runAsync {
            getConnection().use { conn ->
                conn.prepareStatement(
                    "UPDATE player_progression SET level = 1, xp = 0, total_kills = 0, total_infections = 0, games_played = 0, human_wins = 0, equipped_title = null WHERE uuid = ?"
                ).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM player_unlocks WHERE uuid = ?").use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM player_quests WHERE uuid = ?").use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.executeUpdate()
                }
            }
        }.get()
        cache[uuid] = PlayerProfile(uuid = uuid)
        plugin.questManager.invalidateCache(uuid)
    }

    fun close() {
        flushAll()
    }
}
