package cn.oneachina.zombierun.v2.application.combat

import cn.oneachina.zombierun.v2.domain.combat.CombatRules
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 自定义血量用例：人类/僵尸/母体独立血量体系，原版血量仅作为红闪载体。
 *
 * 所有伤害入口（QA 枪械/近战/僵尸爪/环境机关）统一汇入 [damage]，
 * 归因记录 lastDamager 供死亡时判定 killer。
 */
class CombatHealthService(
    private val logger: V2Logger,
) {
    private val health = ConcurrentHashMap<UUID, Double>()
    private val maxHealth = ConcurrentHashMap<UUID, Double>()
    private val lastDamager = ConcurrentHashMap<UUID, UUID>()

    var rules: CombatRules = CombatRules()
        private set

    fun applyRules(newRules: CombatRules) {
        rules = newRules
        logger.info(
            "combat rules updated: sword=${newRules.swordDamage} zombie=${newRules.zombieDamage}" +
                "/${newRules.zombieMaxHealth} main=${newRules.zombieMainDamage}/${newRules.zombieMainMaxHealth}",
        )
    }

    fun maxHealthFor(team: GameTeam): Double = when (team) {
        GameTeam.ZOMBIE_MAIN -> rules.zombieMainMaxHealth
        GameTeam.ZOMBIE -> rules.zombieMaxHealth
        else -> rules.humanMaxHealth
    }

    /** 对局身份初始化（队伍切换时调用）：满血并重置归因。 */
    fun initPlayer(playerId: UUID, team: GameTeam) {
        val max = maxHealthFor(team)
        maxHealth[playerId] = max
        health[playerId] = max
        lastDamager.remove(playerId)
    }

    /** 重置为对应队伍满血（复活转僵尸等）。 */
    fun resetForTeam(playerId: UUID, team: GameTeam) = initPlayer(playerId, team)

    fun getHealth(playerId: UUID): Double = health[playerId] ?: 0.0

    fun getMaxHealth(playerId: UUID): Double = maxHealth[playerId] ?: rules.humanMaxHealth

    fun getHealthPercent(playerId: UUID): Double {
        val max = getMaxHealth(playerId)
        return if (max > 0) getHealth(playerId) / max else 0.0
    }

    /**
     * 记录伤害并扣血；[damager] 可为 null（环境伤害）。
     * 返回扣血后是否已归零（死亡判定由调用方处理）。
     */
    fun damage(playerId: UUID, amount: Double, damager: UUID?): Boolean {
        if (amount <= 0) return health[playerId]?.let { it <= 0 } ?: true
        if (damager != null) lastDamager[playerId] = damager
        val remaining = (health[playerId] ?: 0.0) - amount
        val clamped = remaining.coerceAtLeast(0.0)
        health[playerId] = clamped
        logger.debug("combat", "damage $amount -> $playerId remaining=$clamped")
        return clamped <= 0.0
    }

    fun heal(playerId: UUID, amount: Double) {
        if (amount <= 0) return
        val max = getMaxHealth(playerId)
        health[playerId] = ((health[playerId] ?: 0.0) + amount).coerceAtMost(max)
    }

    /** 取出最近攻击者（一次性：取出即清除）。 */
    fun pollLastDamager(playerId: UUID): UUID? = lastDamager.remove(playerId)

    fun clear(playerId: UUID) {
        health.remove(playerId)
        maxHealth.remove(playerId)
        lastDamager.remove(playerId)
    }

    fun clearAll() {
        health.clear()
        maxHealth.clear()
        lastDamager.clear()
    }
}
