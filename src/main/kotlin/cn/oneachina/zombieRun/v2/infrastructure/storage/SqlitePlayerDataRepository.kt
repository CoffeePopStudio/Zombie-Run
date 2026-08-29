package cn.oneachina.zombierun.v2.infrastructure.storage

import cn.oneachina.zombierun.v2.domain.player.PlayerProfile
import cn.oneachina.zombierun.v2.ports.PlayerDataPort
import cn.oneachina.zombierun.v2.support.V2Logger
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * SQLite 玩家数据仓储，与 v1 共用 `data/zr_economy.db` 目录但不是同一张表。
 * v2 使用独立 `player_data_v2` 表，避免迁移前互相污染。
 */
class SqlitePlayerDataRepository(
    dataFolder: File,
    private val logger: V2Logger,
) : PlayerDataPort {

    private val dataSource: HikariDataSource

    init {
        val dataDir = File(dataFolder, "data")
        dataDir.mkdirs()
        val dbFile = File(dataDir, "zr_economy.db")
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$dbFile"
            maximumPoolSize = 2
            minimumIdle = 1
            connectionTimeout = 5000
            poolName = "zombie-run-v2-db"
            connectionInitSql = "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000"
        }
        dataSource = HikariDataSource(config)
        createTable()
        migrateColumns()
        logger.info("SQLite player data initialized: $dbFile")
    }

    private fun createTable() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS player_data_v2 (
                        player_id TEXT PRIMARY KEY,
                        coins INTEGER NOT NULL DEFAULT 0,
                        xp INTEGER NOT NULL DEFAULT 0,
                        level INTEGER NOT NULL DEFAULT 1,
                        title TEXT,
                        zombie_kills INTEGER NOT NULL DEFAULT 0,
                        door_passes INTEGER NOT NULL DEFAULT 0,
                        total_infections INTEGER NOT NULL DEFAULT 0,
                        games_played INTEGER NOT NULL DEFAULT 0,
                        human_wins INTEGER NOT NULL DEFAULT 0,
                        unlocked_titles TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun migrateColumns() {
        dataSource.connection.use { conn ->
            val columns = mutableSetOf<String>()
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(player_data_v2)").use { rs ->
                    while (rs.next()) columns.add(rs.getString("name"))
                }
            }
            val additions = mapOf(
                "total_infections" to "INTEGER NOT NULL DEFAULT 0",
                "games_played" to "INTEGER NOT NULL DEFAULT 0",
                "human_wins" to "INTEGER NOT NULL DEFAULT 0",
                "unlocked_titles" to "TEXT NOT NULL DEFAULT ''",
            )
            additions.forEach { (column, definition) ->
                if (column !in columns) {
                    conn.createStatement().use { st ->
                        st.executeUpdate("ALTER TABLE player_data_v2 ADD COLUMN $column $definition")
                    }
                    logger.info("SQLite player data column added: $column")
                }
            }
        }
    }

    override fun load(playerId: UUID): PlayerProfile? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT player_id, coins, xp, level, title, zombie_kills, door_passes, total_infections, games_played, human_wins, unlocked_titles FROM player_data_v2 WHERE player_id = ?"
            ).use { ps ->
                ps.setString(1, playerId.toString())
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toProfile()
                }
            }
        }
    }

    override fun save(profile: PlayerProfile) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO player_data_v2 (
                    player_id, coins, xp, level, title, zombie_kills, door_passes,
                    total_infections, games_played, human_wins, unlocked_titles
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_id) DO UPDATE SET
                    coins = excluded.coins,
                    xp = excluded.xp,
                    level = excluded.level,
                    title = excluded.title,
                    zombie_kills = excluded.zombie_kills,
                    door_passes = excluded.door_passes,
                    total_infections = excluded.total_infections,
                    games_played = excluded.games_played,
                    human_wins = excluded.human_wins,
                    unlocked_titles = excluded.unlocked_titles
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, profile.playerId.toString())
                ps.setInt(2, profile.coins)
                ps.setInt(3, profile.xp)
                ps.setInt(4, profile.level)
                ps.setString(5, profile.title)
                ps.setInt(6, profile.zombieKills)
                ps.setInt(7, profile.doorPasses)
                ps.setInt(8, profile.totalInfections)
                ps.setInt(9, profile.gamesPlayed)
                ps.setInt(10, profile.humanWins)
                ps.setString(11, profile.unlockedTitles.joinToString(","))
                ps.executeUpdate()
            }
        }
    }

    override fun topCoins(limit: Int): List<Pair<UUID, Int>> {
        val safeLimit = limit.coerceIn(1, 100)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT player_id, coins FROM player_data_v2 ORDER BY coins DESC LIMIT ?"
            ).use { ps ->
                ps.setInt(1, safeLimit)
                ps.executeQuery().use { rs ->
                    val result = ArrayList<Pair<UUID, Int>>(safeLimit)
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString("player_id")) }.getOrNull() ?: continue
                        result += id to rs.getInt("coins")
                    }
                    return result
                }
            }
        }
    }

    override fun close() {
        dataSource.close()
        logger.info("SQLite player data closed")
    }

    private fun ResultSet.toProfile(): PlayerProfile =
        PlayerProfile(
            playerId = UUID.fromString(getString("player_id")),
            coins = getInt("coins"),
            xp = getInt("xp"),
            level = getInt("level"),
            title = getString("title"),
            zombieKills = getInt("zombie_kills"),
            doorPasses = getInt("door_passes"),
            totalInfections = getInt("total_infections"),
            gamesPlayed = getInt("games_played"),
            humanWins = getInt("human_wins"),
            unlockedTitles = getString("unlocked_titles")
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet(),
        )
}