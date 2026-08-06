package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class CoinManager(private val plugin: ZombieRun) {

    private val cache = ConcurrentHashMap<UUID, Int>()

    fun init() {
        plugin.databaseManager.getConnection().use { conn ->
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
        plugin.logger.info("CoinManager 已初始化 (共享 DatabaseManager)")
    }

    /** 异步加载玩家金币并写入缓存；金币展示优先读缓存（默认 0） */
    fun loadPlayerAsync(uuid: UUID, username: String) {
        plugin.databaseManager.runAsync { conn ->
            conn.prepareStatement("SELECT coins FROM zr_economy WHERE uuid = ?").use { stmt ->
                stmt.setString(1, uuid.toString())
                val rs = stmt.executeQuery()
                val coins = if (rs.next()) {
                    rs.getInt("coins")
                } else {
                    conn.prepareStatement("INSERT INTO zr_economy (uuid, username, coins) VALUES (?, ?, 0)").use { ins ->
                        ins.setString(1, uuid.toString())
                        ins.setString(2, username)
                        ins.executeUpdate()
                    }
                    0
                }
                cache[uuid] = coins
            }
        }
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
        plugin.databaseManager.runAsync { conn ->
            conn.prepareStatement("UPDATE zr_economy SET coins = ?, username = ? WHERE uuid = ?").use { stmt ->
                stmt.setInt(1, coins)
                stmt.setString(2, username)
                stmt.setString(3, uuid.toString())
                stmt.executeUpdate()
            }
        }
        cache.remove(uuid)
    }

    fun flushAll() {
        cache.forEach { (uuid, coins) ->
            runCatching {
                plugin.databaseManager.getConnection().use { conn ->
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

    /** 异步查询金币排行榜（命令侧通过回调渲染，避免阻塞主线程） */
    fun getTopCoinsAsync(limit: Int): CompletableFuture<List<Pair<String, Int>>> {
        return CompletableFuture.supplyAsync {
            plugin.databaseManager.getConnection().use { conn ->
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
        }
    }

    private fun updateAsync(uuid: UUID) {
        val coins = cache[uuid] ?: return
        plugin.databaseManager.runAsync { conn ->
            conn.prepareStatement("UPDATE zr_economy SET coins = ? WHERE uuid = ?").use { stmt ->
                stmt.setInt(1, coins)
                stmt.setString(2, uuid.toString())
                stmt.executeUpdate()
            }
        }
    }

    fun resetCoins(uuid: UUID) {
        cache[uuid] = 0
        plugin.databaseManager.runAsync { conn ->
            conn.prepareStatement("UPDATE zr_economy SET coins = 0 WHERE uuid = ?").use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeUpdate()
            }
        }
    }

    fun close() {
        flushAll()
    }
}
