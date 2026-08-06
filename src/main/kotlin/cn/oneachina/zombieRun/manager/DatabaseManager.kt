package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * 统一 SQLite 数据访问层。
 *
 * 单一 Hikari 连接池 + WAL 模式，供 CoinManager / ProgressionManager / QuestManager 共用，
 * 避免多个连接池/裸连接并发写同一文件导致的 database is locked 问题。
 */
class DatabaseManager(private val plugin: ZombieRun) {

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
            poolName = "zombie-run-db"
            // WAL 模式 + busy_timeout：降低读写锁竞争
            connectionInitSql = "PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000"
        }
        dataSource = HikariDataSource(config)
        plugin.logger.info("DatabaseManager SQLite 已初始化 (WAL): $dbFile")
    }

    fun getConnection() = dataSource.connection

    /** 在池化连接上异步执行一次写操作（不阻塞主线程） */
    fun runAsync(block: (java.sql.Connection) -> Unit) {
        CompletableFuture.runAsync {
            dataSource.connection.use(block)
        }
    }

    fun close() {
        if (::dataSource.isInitialized) dataSource.close()
    }
}
