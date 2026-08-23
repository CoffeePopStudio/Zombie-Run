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

/**
 * 玩家经济/进度缓存与用例。事件总线驱动过门/击杀进度。
 */
class PlayerDataService(
    private val storage: PlayerDataPort,
    private val messages: PlayerMessagePort,
    private val logger: V2Logger,
    private val eventBus: ApplicationEventBus,
) {
    private val cache = ConcurrentHashMap<UUID, PlayerProfile>()

    init {
        eventBus.subscribe(PlayerPassedDoorEvent::class.java) { event ->
            onDoorPassed(event.playerId)
        }
        eventBus.subscribe(ZombieKilledEvent::class.java) { event ->
            onZombieKilled(event.killerId)
        }
    }

    fun get(playerId: UUID): PlayerProfile =
        cache.computeIfAbsent(playerId) {
            storage.load(it) ?: PlayerProfile(it)
        }

    fun profileOf(playerId: UUID): PlayerProfile = get(playerId)

    fun addCoins(playerId: UUID, amount: Int): PlayerProfile {
        val updated = get(playerId).addCoins(amount)
        cache[playerId] = updated
        storage.save(updated)
        return updated
    }

    fun spendCoins(playerId: UUID, amount: Int): PlayerProfile? {
        val profile = get(playerId).spendCoins(amount) ?: return null
        cache[playerId] = profile
        storage.save(profile)
        return profile
    }

    fun addXp(playerId: UUID, amount: Int): PlayerProfile {
        val before = get(playerId)
        val after = before.addXp(amount)
        cache[playerId] = after
        storage.save(after)
        if (after.level > before.level) {
            messages.chat(playerId, "恭喜升级！当前等级 ${after.level}")
        }
        return after
    }

    fun setTitle(playerId: UUID, title: String?): PlayerProfile {
        val updated = get(playerId).setTitle(title)
        cache[playerId] = updated
        storage.save(updated)
        return updated
    }

    fun onJoin(playerId: UUID) {
        get(playerId)
        logger.info("player data loaded: $playerId")
    }

    fun onQuit(playerId: UUID) {
        cache.remove(playerId)?.let { storage.save(it) }
    }

    fun onDoorPassed(playerId: UUID) {
        val updated = get(playerId).addDoorPass()
        cache[playerId] = updated
        storage.save(updated)
    }

    fun onZombieKilled(killerId: UUID) {
        val updated = get(killerId).addKill()
        cache[killerId] = updated
        storage.save(updated)
    }

    fun close() {
        cache.values.forEach { storage.save(it) }
        cache.clear()
        storage.close()
    }
}