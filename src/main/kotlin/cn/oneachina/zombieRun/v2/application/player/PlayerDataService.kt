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

    /** 单玩家原子读-改-写：避免 Folia 多线程下并发扣款/加款丢更新。 */
    private fun mutate(playerId: UUID, action: (PlayerProfile) -> PlayerProfile): PlayerProfile {
        val updated = cache.compute(playerId) { _, existing ->
            val profile = existing ?: storage.load(playerId) ?: PlayerProfile(playerId)
            val updated = action(profile)
            storage.save(updated)
            updated
        }!!
        return updated
    }

    fun addCoins(playerId: UUID, amount: Int): PlayerProfile =
        mutate(playerId) { it.addCoins(amount) }

    fun spendCoins(playerId: UUID, amount: Int): PlayerProfile? {
        var result: PlayerProfile? = null
        cache.compute(playerId) { _, existing ->
            val profile = existing ?: storage.load(playerId) ?: PlayerProfile(playerId)
            val updated = profile.spendCoins(amount)
            if (updated == null) {
                result = null
                profile
            } else {
                storage.save(updated)
                result = updated
                updated
            }
        }
        return result
    }

    fun addXp(playerId: UUID, amount: Int): PlayerProfile {
        var leveled = false
        val updated = mutate(playerId) {
            val after = it.addXp(amount)
            if (after.level > it.level) leveled = true
            after
        }
        if (leveled) {
            messages.chat(playerId, "恭喜升级！当前等级 ${updated.level}")
        }
        return updated
    }

    fun setTitle(playerId: UUID, title: String?): PlayerProfile =
        mutate(playerId) { it.setTitle(title) }

    fun onJoin(playerId: UUID) {
        get(playerId)
        logger.info("player data loaded: $playerId")
    }

    fun onQuit(playerId: UUID) {
        cache.remove(playerId)?.let { storage.save(it) }
    }

    fun onDoorPassed(playerId: UUID) {
        mutate(playerId) { it.addDoorPass() }
    }

    fun onZombieKilled(killerId: UUID) {
        mutate(killerId) { it.addKill() }
    }

    fun close() {
        cache.values.forEach { storage.save(it) }
        cache.clear()
        storage.close()
    }
}