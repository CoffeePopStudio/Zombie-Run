package cn.oneachina.zombierun.v2.application.player

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.GameEndedEvent
import cn.oneachina.zombierun.v2.application.event.GameStartedEvent
import cn.oneachina.zombierun.v2.application.event.InfectHumanEvent
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `infection game and win events update extended stats`() {
        val storage = FakeStorage()
        val bus = ApplicationEventBus()
        val service = PlayerDataService(storage, FakeMessages(), V2Logger(Logger.getLogger("test")), bus)
        val id = UUID.randomUUID()
        val victim = UUID.randomUUID()

        bus.publish(InfectHumanEvent(id, victim))
        bus.publish(GameStartedEvent("w", mapOf(id to "HUMAN")))
        bus.publish(GameEndedEvent("w", "HUMAN", setOf(id)))

        val profile = service.profileOf(id)
        assertEquals(1, profile.totalInfections)
        assertEquals(1, profile.gamesPlayed)
        assertEquals(1, profile.humanWins)
    }

    @Test
    fun `transfer moves coins atomically`() {
        val storage = FakeStorage()
        val bus = ApplicationEventBus()
        val service = PlayerDataService(storage, FakeMessages(), V2Logger(Logger.getLogger("test")), bus)
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        service.addCoins(from, 100)

        assertTrue(service.transferCoins(from, to, 40))
        assertEquals(60, service.profileOf(from).coins)
        assertEquals(40, service.profileOf(to).coins)
        assertFalse(service.transferCoins(from, to, 100))
        assertEquals(60, service.profileOf(from).coins)
        assertEquals(40, service.profileOf(to).coins)
    }
}