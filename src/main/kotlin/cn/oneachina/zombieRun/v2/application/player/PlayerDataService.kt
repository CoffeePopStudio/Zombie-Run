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
        eventBus.subscribe(InfectHumanEvent::class.java) { event ->
            mutate(event.attackerId) { it.addInfection() }
        }
        eventBus.subscribe(GameStartedEvent::class.java) { event ->
            event.playerAssignments.keys.forEach { playerId ->
                mutate(playerId) { it.addGamePlayed() }
            }
        }
        eventBus.subscribe(GameEndedEvent::class.java) { event ->
            if (event.winner == "HUMAN") {
                event.winnerPlayerIds.forEach { playerId ->
                    mutate(playerId) { it.addHumanWin() }
                }
            }
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
        var previousTitles = emptySet<String>()
        val updated = mutate(playerId) {
            val before = it
            val after = it.addXp(amount)
            if (after.level > before.level) {
                leveled = true
                previousTitles = before.unlockedTitles
            }
            unlockTitlesForLevel(after)
        }
        if (leveled) {
            messages.chat(playerId, "恭喜升级！当前等级 ${updated.level}")
            val newlyUnlocked = updated.unlockedTitles - previousTitles
            if (newlyUnlocked.isNotEmpty()) {
                messages.chat(playerId, "解锁称号：${newlyUnlocked.joinToString("、")}")
            }
        }
        return updated
    }

    fun setTitle(playerId: UUID, title: String?): PlayerProfile =
        mutate(playerId) { it.setTitle(title) }

    fun setCoins(playerId: UUID, amount: Int): PlayerProfile =
        mutate(playerId) { it.copy(coins = amount.coerceAtLeast(0)) }

    fun removeCoins(playerId: UUID, amount: Int): PlayerProfile =
        mutate(playerId) { it.copy(coins = (it.coins - amount).coerceAtLeast(0)) }

    fun setXp(playerId: UUID, xp: Int): PlayerProfile =
        mutate(playerId) { unlockTitlesForLevel(it.copy(xp = xp.coerceAtLeast(0))) }

    fun setLevel(playerId: UUID, level: Int): PlayerProfile =
        mutate(playerId) {
            unlockTitlesForLevel(it.copy(level = level.coerceAtLeast(1), xp = 0))
        }

    fun resetPlayer(playerId: UUID): PlayerProfile {
        val fresh = PlayerProfile(playerId)
        cache[playerId] = fresh
        storage.save(fresh)
        messages.chat(playerId, "玩家数据已重置")
        return fresh
    }

    fun unlockTitle(playerId: UUID, title: String): PlayerProfile =
        mutate(playerId) { it.unlockTitle(title) }

    private fun unlockTitlesForLevel(profile: PlayerProfile): PlayerProfile {
        var result = profile
        TITLE_LEVELS.forEach { (title, level) ->
            if (result.level >= level) result = result.unlockTitle(title)
        }
        return result
    }

    /** 玩家间转账：先原子扣款，再入账；余额不足或同玩家返回 false。 */
    fun transferCoins(fromId: UUID, toId: UUID, amount: Int): Boolean {
        if (fromId == toId || amount <= 0) return false
        val spent = spendCoins(fromId, amount) ?: return false
        addCoins(toId, amount)
        logger.info("transfer $amount coins $fromId -> $toId (from balance=${spent.coins})")
        return true
    }

    fun topCoins(limit: Int): List<Pair<UUID, Int>> = storage.topCoins(limit.coerceIn(1, 100))

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

    companion object {
        /** 等级解锁称号目录（与 GuiService 展示保持一致）。 */
        val TITLE_LEVELS: List<Pair<String, Int>> = listOf(
            "新人" to 1,
            "跑酷者" to 5,
            "门之守护者" to 10,
            "僵尸杀手" to 15,
            "逃生专家" to 20,
            "金色传说" to 30,
        )
    }
}