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
                        door_passes INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun load(playerId: UUID): PlayerProfile? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT player_id, coins, xp, level, title, zombie_kills, door_passes FROM player_data_v2 WHERE player_id = ?"
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
                INSERT INTO player_data_v2 (player_id, coins, xp, level, title, zombie_kills, door_passes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_id) DO UPDATE SET
                    coins = excluded.coins,
                    xp = excluded.xp,
                    level = excluded.level,
                    title = excluded.title,
                    zombie_kills = excluded.zombie_kills,
                    door_passes = excluded.door_passes
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, profile.playerId.toString())
                ps.setInt(2, profile.coins)
                ps.setInt(3, profile.xp)
                ps.setInt(4, profile.level)
                ps.setString(5, profile.title)
                ps.setInt(6, profile.zombieKills)
                ps.setInt(7, profile.doorPasses)
                ps.executeUpdate()
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
        )
}