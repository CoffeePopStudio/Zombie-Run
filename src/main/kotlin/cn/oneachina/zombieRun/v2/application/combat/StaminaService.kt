package cn.oneachina.zombierun.v2.application.combat

import cn.oneachina.zombierun.v2.domain.combat.StaminaRules
import cn.oneachina.zombierun.v2.domain.combat.StaminaState
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 体力用例：按 5 tick 节奏更新所有玩家的疾跑体力。
 */
class StaminaService(
    private val logger: V2Logger,
) {
    private val states = ConcurrentHashMap<UUID, StaminaState>()

    @Volatile
    var rules: StaminaRules = StaminaRules()
        private set

    fun applyRules(newRules: StaminaRules) {
        rules = newRules
        // 规则变更后重置所有玩家状态，避免上限/恢复速率不一致
        states.clear()
        logger.info("stamina rules updated: ${newRules.max}/${newRules.sprintDrainPerTick}/${newRules.regenPerTick}")
    }

    fun stateOf(playerId: UUID): StaminaState =
        states.computeIfAbsent(playerId) { StaminaState(rules) }

    fun reset(playerId: UUID) {
        states[playerId] = StaminaState(rules)
    }

    fun remove(playerId: UUID) {
        states.remove(playerId)
    }

    /** @return true 表示玩家刚刚进入疲劳，需要强制停止疾跑。 */
    fun update(playerId: UUID, sprinting: Boolean): Boolean =
        stateOf(playerId).tick(sprinting)

    fun fraction(playerId: UUID): Double = stateOf(playerId).fraction()
}
