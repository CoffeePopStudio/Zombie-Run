package cn.oneachina.zombierun.v2.infrastructure.config

import cn.oneachina.zombierun.v2.domain.player.PlayerProfile
import cn.oneachina.zombierun.v2.ports.PlayerDataPort
import cn.oneachina.zombierun.v2.support.V2Logger
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class V1MigrationServiceTest {

    private class FakePlayerData : PlayerDataPort {
        val store = ConcurrentHashMap<UUID, PlayerProfile>()
        override fun load(playerId: UUID): PlayerProfile? = store[playerId]
        override fun save(profile: PlayerProfile) { store[profile.playerId] = profile }
        override fun close() = Unit
    }

    private fun v1Db(dir: File): File {
        val db = File(File(dir, "plugins/zombie-run"), "data/zr_economy.db")
        db.parentFile.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath.replace('\\', '/')}").use { conn ->
            conn.createStatement().executeUpdate(
                """
                CREATE TABLE zr_economy (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(32),
                    coins INT DEFAULT 0
                );
                CREATE TABLE player_progression (
                    uuid VARCHAR(36) PRIMARY KEY,
                    level INT DEFAULT 1,
                    xp INT DEFAULT 0,
                    total_kills INT DEFAULT 0,
                    total_infections INT DEFAULT 0,
                    games_played INT DEFAULT 0,
                    human_wins INT DEFAULT 0,
                    equipped_title VARCHAR(64)
                );
                """.trimIndent(),
            )
            conn.createStatement().executeUpdate(
                "INSERT INTO zr_economy (uuid, username, coins) VALUES ('6e81503a-8a52-3805-bff6-548680ebf5d2','Neamyoo',1000),('279ceafd-ddcb-3eba-9679-4790378e7f69','zxh',250);",
            )
            conn.createStatement().executeUpdate(
                "INSERT INTO player_progression (uuid, level, xp, total_kills, equipped_title) VALUES ('6e81503a-8a52-3805-bff6-548680ebf5d2', 5, 120, 42, '僵尸杀手'), ('279ceafd-ddcb-3eba-9679-4790378e7f69', 2, 80, 3, NULL);",
            )
        }
        return db
    }

    @Test
    fun `migrateData imports v1 players into v2 port`() {
        val dir = createTempDirectory("zr2-migrate-data").toFile()
        v1Db(dir)
        val v2Folder = File(dir, "plugins/zombie-run-v2")
        val fake = FakePlayerData()
        val service = V1MigrationService(v2Folder, ArenaYamlRepository(v2Folder, V2Logger(Logger.getLogger("test"))), fake, V2Logger(Logger.getLogger("test")))

        val report = service.migrateData()

        assertEquals(2, report.playersMigrated)
        assertEquals(0, report.playersSkipped)
        assertEquals(1250L, report.totalCoins)
        val neamyoo = fake.store[UUID.fromString("6e81503a-8a52-3805-bff6-548680ebf5d2")]!!
        assertEquals(1000, neamyoo.coins)
        assertEquals(5, neamyoo.level)
        assertEquals(120, neamyoo.xp)
        assertEquals(42, neamyoo.zombieKills)
        assertEquals("僵尸杀手", neamyoo.title)
    }

    @Test
    fun `migrateData does not overwrite existing player by default`() {
        val dir = createTempDirectory("zr2-migrate-data").toFile()
        v1Db(dir)
        val v2Folder = File(dir, "plugins/zombie-run-v2")
        val fake = FakePlayerData()
        val existing = PlayerProfile(UUID.fromString("6e81503a-8a52-3805-bff6-548680ebf5d2"), coins = 999)
        fake.store[existing.playerId] = existing
        val service = V1MigrationService(v2Folder, ArenaYamlRepository(v2Folder, V2Logger(Logger.getLogger("test"))), fake, V2Logger(Logger.getLogger("test")))

        val report = service.migrateData()

        assertEquals(1, report.playersMigrated)
        assertEquals(1, report.playersSkipped)
        assertEquals(999, fake.store[existing.playerId]!!.coins)
    }
}