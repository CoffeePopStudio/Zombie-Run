package cn.oneachina.zombierun.v2.domain.combat

/**
 * 体力规则（v2 schema 可覆盖）。
 */
data class StaminaRules(
    val max: Double = 20.0,
    val sprintDrainPerTick: Double = 0.25,
    val regenPerTick: Double = 0.08,
    /** 耗尽后需要多少 tick 才开始恢复。 */
    val exhaustRecoveryDelayTicks: Int = 40,
    /** 恢复门槛：体力回到该值以上才解除疲劳。 */
    val recoverAt: Double = 4.0,
)

enum class StaminaStatus { NORMAL, EXHAUSTED, RECOVERING }

/**
 * 单玩家体力状态（纯 Kotlin）。
 */
class StaminaState(
    private val rules: StaminaRules,
) {
    var current: Double = rules.max
        private set

    var status: StaminaStatus = StaminaStatus.NORMAL
        private set

    private var ticksInExhaustion: Int = 0

    /** 每个 5 tick 调用一次。[sprinting] 为当前是否疾跑。返回是否刚进入疲劳。 */
    fun tick(sprinting: Boolean): Boolean {
        var newlyExhausted = false
        when (status) {
            StaminaStatus.NORMAL -> {
                if (sprinting) {
                    current = (current - rules.sprintDrainPerTick).coerceAtLeast(0.0)
                    if (current <= 0.0) {
                        status = StaminaStatus.EXHAUSTED
                        ticksInExhaustion = 0
                        newlyExhausted = true
                    }
                } else {
                    current = (current + rules.regenPerTick).coerceAtMost(rules.max)
                }
            }
            StaminaStatus.EXHAUSTED, StaminaStatus.RECOVERING -> {
                if (sprinting) {
                    // 疲劳期间继续疾跑无法恢复
                    status = StaminaStatus.EXHAUSTED
                    ticksInExhaustion = 0
                } else {
                    ticksInExhaustion++
                    status = StaminaStatus.RECOVERING
                    if (ticksInExhaustion >= rules.exhaustRecoveryDelayTicks) {
                        current = (current + rules.regenPerTick).coerceAtMost(rules.max)
                    }
                    if (current >= rules.recoverAt) {
                        status = StaminaStatus.NORMAL
                        ticksInExhaustion = 0
                    }
                }
            }
        }
        return newlyExhausted
    }

    fun fraction(): Double = current / rules.max
}
