package cn.oneachina.zombierun.v2.domain.combat

/**
 * 战斗规则（settings.yml combat 节可覆盖）。
 */
data class CombatRules(
    /** 人类近战（剑）对僵尸伤害 */
    val swordDamage: Double = 5.0,
    /** 普通僵尸对人类伤害 */
    val zombieDamage: Double = 5.0,
    /** 母体对人类伤害 */
    val zombieMainDamage: Double = 8.0,
    val zombieMaxHealth: Double = 120.0,
    val zombieMainMaxHealth: Double = 300.0,
    val humanMaxHealth: Double = 20.0,
    /** 人类爆炸伤害减免倍率（0.05 = 只受 5%） */
    val explosionDamageReduction: Double = 0.05,
)
