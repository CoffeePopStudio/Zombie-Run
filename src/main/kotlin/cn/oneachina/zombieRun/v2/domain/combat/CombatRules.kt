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


/**
 * 即时经济奖励规则（settings.yml economy 节可覆盖）。
 */
data class EconomyRules(
    val killZombieCoins: Int = 50,
    val killZombieXp: Int = 30,
    val killZombieMainCoins: Int = 150,
    val killZombieMainXp: Int = 30,
    val infectHumanCoins: Int = 50,
    val infectHumanXp: Int = 20,
    val headshotXp: Int = 5,
    val passDoorXp: Int = 5,
    val surviveHumanCoins: Int = 200,
    val humanWinXp: Int = 100,
    val participateXp: Int = 50,
    /** 结算榜单奖励：击杀/感染 Top3（index 0 = 第 1 名） */
    val rankRewardCoins: List<Int> = listOf(200, 150, 100),
)
