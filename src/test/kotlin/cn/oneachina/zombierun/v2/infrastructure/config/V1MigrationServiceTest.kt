package cn.oneachina.zombierun.v2.infrastructure.config

import cn.oneachina.zombierun.v2.domain.door.PortalFront
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
        val service = V1MigrationService(v2Folder, ArenaYamlRepository(v2Folder, V2Logger(Logger.getLogger("test"))), fake, BlockSnapshotStore(v2Folder), V2Logger(Logger.getLogger("test")))

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
        val service = V1MigrationService(v2Folder, ArenaYamlRepository(v2Folder, V2Logger(Logger.getLogger("test"))), fake, BlockSnapshotStore(v2Folder), V2Logger(Logger.getLogger("test")))

        val report = service.migrateData()

        assertEquals(1, report.playersMigrated)
        assertEquals(1, report.playersSkipped)
        assertEquals(999, fake.store[existing.playerId]!!.coins)
    }

    @Test
    fun `migrate attaches v1 door snapshot matching the door region`() {
        val dir = createTempDirectory("zr2-migrate-snap").toFile()
        // v1 config with one door at region x=10, y=64..66, z=10..20
        val v1Config = File(dir, "plugins/zombie-run/config/config.yml")
        v1Config.parentFile.mkdirs()
        v1Config.writeText(
            """
            game:
              world: test_world
            doors:
              door_1:
                x1: 10
                y1: 64
                z1: 10
                x2: 10
                y2: 66
                z2: 20
                open-time: 15
                close-time: 15
                door-number: 1
                mode: normal
            """.trimIndent(),
        )
        // v1 scandata: absolute coords inside the door region
        val scandata = File(dir, "plugins/zombie-run/config/doors/door_999.scandata.yml")
        scandata.parentFile.mkdirs()
        scandata.writeText(
            """
            10,64,10: AIR
            10,65,10: STONE
            10,66,10: AIR
            999,999,999: AIR
            """.trimIndent(),
        )

        val v2Folder = File(dir, "plugins/zombie-run-v2")
        val fake = FakePlayerData()
        val service = V1MigrationService(v2Folder, ArenaYamlRepository(v2Folder, V2Logger(Logger.getLogger("test"))), fake, BlockSnapshotStore(v2Folder), V2Logger(Logger.getLogger("test")))

        val report = service.migrate()

        assertEquals(1, report.doorsMigrated)
        assertEquals(1, report.snapshotsImported)
        assertEquals(1, report.snapshotsAttached)
        val saved = ArenaYamlRepository(v2Folder, V2Logger(Logger.getLogger("test"))).loadAll().single()
        val door = saved.doors.single()
        assertEquals("door_999", door.snapshotId)
        // v2 snapshot store contains the copied snapshot (full v1 scandata preserved)
        val stored = BlockSnapshotStore(v2Folder).load("door_999")
        assertEquals("STONE", stored["10,65,10"])
        assertEquals("AIR", stored["10,64,10"])
        assertEquals("AIR", stored["999,999,999"])
    }

    @Test
    fun `migrate maps v1 reverse-direction to front negative`() {
        val dir = createTempDirectory("zr2-migrate-reverse").toFile()
        val v1Config = File(dir, "plugins/zombie-run/config/config.yml")
        v1Config.parentFile.mkdirs()
        v1Config.writeText(
            """
            game:
              world: test_world
            doors:
              door_1:
                x1: 10
                y1: 64
                z1: 10
                x2: 10
                y2: 66
                z2: 20
                door-number: 1
                mode: normal
                reverse-direction: true
            """.trimIndent(),
        )

        val v2Folder = File(dir, "plugins/zombie-run-v2")
        val fake = FakePlayerData()
        val service = V1MigrationService(v2Folder, ArenaYamlRepository(v2Folder, V2Logger(Logger.getLogger("test"))), fake, BlockSnapshotStore(v2Folder), V2Logger(Logger.getLogger("test")))

        service.migrate()

        val saved = ArenaYamlRepository(v2Folder, V2Logger(Logger.getLogger("test"))).loadAll().single()
        assertEquals(PortalFront.NEGATIVE, saved.doors.single().portal.front)
    }
}