package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class CoinManager(private val plugin: ZombieRun) {

    private val cache = ConcurrentHashMap<UUID, Int>()
    private lateinit var dataSource: HikariDataSource

    fun init() {
        val dataDir = File(plugin.dataFolder, "data")
        if (!dataDir.exists()) dataDir.mkdirs()
        val dbFile = File(dataDir, "zr_economy.db")

        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$dbFile"
            maximumPoolSize = 2
            minimumIdle = 1
            connectionTimeout = 5000
        }
        dataSource = HikariDataSource(config)

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS zr_economy (
                        uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(32),
                        coins INT DEFAULT 0
                    )
                """.trimIndent())
            }
        }
        plugin.logger.info("CoinManager SQLite 已初始化: $dbFile")
    }

    fun loadPlayer(uuid: UUID, username: String): Int {
        val coins = CompletableFuture.supplyAsync {
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT coins FROM zr_economy WHERE uuid = ?").use { stmt ->
                    stmt.setString(1, uuid.toString())
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        rs.getInt("coins")
                    } else {
                        conn.prepareStatement("INSERT INTO zr_economy (uuid, username, coins) VALUES (?, ?, 0)").use { ins ->
                            ins.setString(1, uuid.toString())
                            ins.setString(2, username)
                            ins.executeUpdate()
                        }
                        0
                    }
                }
            }
        }.get()
        cache[uuid] = coins
        return coins
    }

    fun getCoins(uuid: UUID): Int = cache.getOrDefault(uuid, 0)

    fun addCoins(uuid: UUID, amount: Int) {
        val current = getCoins(uuid)
        cache[uuid] = current + amount
        updateAsync(uuid)
    }

    fun takeCoins(uuid: UUID, amount: Int): Boolean {
        val current = getCoins(uuid)
        if (current < amount) return false
        cache[uuid] = current - amount
        updateAsync(uuid)
        return true
    }

    fun setCoins(uuid: UUID, amount: Int) {
        cache[uuid] = amount
        updateAsync(uuid)
    }

    fun savePlayer(uuid: UUID, username: String) {
        val coins = cache[uuid] ?: return
        CompletableFuture.runAsync {
            dataSource.connection.use { conn ->
                conn.prepareStatement("UPDATE zr_economy SET coins = ?, username = ? WHERE uuid = ?").use { stmt ->
                    stmt.setInt(1, coins)
                    stmt.setString(2, username)
                    stmt.setString(3, uuid.toString())
                    stmt.executeUpdate()
                }
            }
        }
        cache.remove(uuid)
    }

    fun flushAll() {
        cache.forEach { (uuid, coins) ->
            runCatching {
                dataSource.connection.use { conn ->
                    conn.prepareStatement("UPDATE zr_economy SET coins = ? WHERE uuid = ?").use { stmt ->
                        stmt.setInt(1, coins)
                        stmt.setString(2, uuid.toString())
                        stmt.executeUpdate()
                    }
                }
            }
        }
        cache.clear()
    }

    fun getTopCoins(limit: Int): List<Pair<String, Int>> {
        return CompletableFuture.supplyAsync {
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT username, coins FROM zr_economy ORDER BY coins DESC LIMIT ?").use { stmt ->
                    stmt.setInt(1, limit)
                    val rs = stmt.executeQuery()
                    val result = mutableListOf<Pair<String, Int>>()
                    while (rs.next()) {
                        result.add(Pair(rs.getString("username") ?: "???", rs.getInt("coins")))
                    }
                    result
                }
            }
        }.get()
    }

    private fun updateAsync(uuid: UUID) {
        val coins = cache[uuid] ?: return
        CompletableFuture.runAsync {
            dataSource.connection.use { conn ->
                conn.prepareStatement("UPDATE zr_economy SET coins = ? WHERE uuid = ?").use { stmt ->
                    stmt.setInt(1, coins)
                    stmt.setString(2, uuid.toString())
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun close() {
        flushAll()
        dataSource.close()
    }
}
