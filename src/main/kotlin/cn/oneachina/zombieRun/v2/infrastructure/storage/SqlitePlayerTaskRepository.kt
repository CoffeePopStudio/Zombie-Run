package cn.oneachina.zombierun.v2.infrastructure.storage

import cn.oneachina.zombierun.v2.domain.task.TaskProgress
import cn.oneachina.zombierun.v2.ports.PlayerTaskPort
import cn.oneachina.zombierun.v2.support.V2Logger
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SqlitePlayerTaskRepository(
    dataFolder: File,
    logger: V2Logger,
) : PlayerTaskPort {

    private val dataSource: HikariDataSource
    private val cache = ConcurrentHashMap<UUID, Map<String, TaskProgress>>()

    init {
        dataFolder.mkdirs()
        val dbFile = File(dataFolder, "data/zr_tasks.db")
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath.replace('\\', '/')}"
            maximumPoolSize = 1
            poolName = "zombie-run-v2-tasks"
            // 与经济库一致：WAL + busy_timeout，防 Folia 多线程写入 "database is locked"
            connectionInitSql = "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000"
        }
        dataSource = HikariDataSource(config)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS player_tasks_v2 (
                        player_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        progress INTEGER NOT NULL DEFAULT 0,
                        claimed INTEGER NOT NULL DEFAULT 0,
                        last_reset TEXT,
                        PRIMARY KEY (player_id, task_id)
                    )
                    """.trimIndent(),
                )
            }
        }
        logger.info("SQLite task data initialized: ${dbFile.absolutePath}")
    }

    override fun load(playerId: UUID): Map<String, TaskProgress> {
        cache[playerId]?.let { return it }
        val result = ConcurrentHashMap<String, TaskProgress>()
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT task_id, progress, claimed, last_reset FROM player_tasks_v2 WHERE player_id = ?").use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        result[rs.getString("task_id")] = TaskProgress(
                            taskId = rs.getString("task_id"),
                            progress = rs.getInt("progress"),
                            claimed = rs.getInt("claimed") == 1,
                            lastReset = rs.getString("last_reset"),
                        )
                    }
                }
            }
        }
        cache[playerId] = result
        return result
    }

    override fun save(playerId: UUID, progress: Map<String, TaskProgress>) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("INSERT OR REPLACE INTO player_tasks_v2 (player_id, task_id, progress, claimed, last_reset) VALUES (?, ?, ?, ?, ?)").use { statement ->
                    progress.values.forEach { p ->
                        statement.setString(1, playerId.toString())
                        statement.setString(2, p.taskId)
                        statement.setInt(3, p.progress)
                        statement.setInt(4, if (p.claimed) 1 else 0)
                        statement.setString(5, p.lastReset)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
        cache[playerId] = progress
    }

    override fun close() {
        dataSource.close()
        cache.clear()
    }
}