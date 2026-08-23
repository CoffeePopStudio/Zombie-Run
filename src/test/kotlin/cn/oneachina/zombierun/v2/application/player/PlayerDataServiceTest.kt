package cn.oneachina.zombierun.v2.application.player

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.PlayerPassedDoorEvent
import cn.oneachina.zombierun.v2.application.event.ZombieKilledEvent
import cn.oneachina.zombierun.v2.domain.player.PlayerProfile
import cn.oneachina.zombierun.v2.ports.PlayerDataPort
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerDataServiceTest {

    private class FakeStorage : PlayerDataPort {
        val store = ConcurrentHashMap<UUID, PlayerProfile>()
        override fun load(playerId: UUID): PlayerProfile? = store[playerId]
        override fun save(profile: PlayerProfile) {
            store[profile.playerId] = profile
        }
        override fun close() = Unit
    }

    private class FakeMessages : PlayerMessagePort {
        override fun actionBar(playerId: UUID, message: String) = Unit
        override fun chat(playerId: UUID, message: String) = Unit
        override fun title(playerId: UUID, title: String, subtitle: String) = Unit
        override fun soundBell(worldName: String) = Unit
    }

    @Test
    fun `door and kill events update profile and save`() {
        val storage = FakeStorage()
        val bus = ApplicationEventBus()
        val service = PlayerDataService(storage, FakeMessages(), V2Logger(Logger.getLogger("test")), bus)
        val id = UUID.randomUUID()

        bus.publish(PlayerPassedDoorEvent("w", id, listOf(1)))
        bus.publish(PlayerPassedDoorEvent("w", id, listOf(2)))
        bus.publish(ZombieKilledEvent("w", id, UUID.randomUUID()))

        val profile = service.profileOf(id)
        assertEquals(2, profile.doorPasses)
        assertEquals(1, profile.zombieKills)
        assertEquals(2, storage.store[id]?.doorPasses)
    }

    @Test
    fun `spend returns null when insufficient coins`() {
        val storage = FakeStorage()
        val bus = ApplicationEventBus()
        val service = PlayerDataService(storage, FakeMessages(), V2Logger(Logger.getLogger("test")), bus)
        val id = UUID.randomUUID()
        assertEquals(null, service.spendCoins(id, 10))
    }
}