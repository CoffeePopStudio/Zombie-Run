package cn.oneachina.zombierun.v2.domain.player

import java.util.UUID

/**
 * 玩家持久化进度聚合（纯 Kotlin）。
 *
 * [level] 由累计经验决定；[title] 为玩家当前称号（null 表示未设置）。
 */
data class PlayerProfile(
    val playerId: UUID,
    val coins: Int = 0,
    val xp: Int = 0,
    val level: Int = 1,
    val title: String? = null,
    val zombieKills: Int = 0,
    val doorPasses: Int = 0,
) {
    fun addCoins(amount: Int): PlayerProfile =
        copy(coins = coins + amount)

    fun spendCoins(amount: Int): PlayerProfile? {
        if (amount < 0 || coins < amount) return null
        return copy(coins = coins - amount)
    }

    fun addXp(amount: Int): PlayerProfile {
        if (amount <= 0) return this
        var newXp = xp + amount
        var newLevel = level
        while (newXp >= nextLevelXpAt(newLevel)) {
            newXp -= nextLevelXpAt(newLevel)
            newLevel++
        }
        return copy(xp = newXp, level = newLevel)
    }

    fun setTitle(newTitle: String?): PlayerProfile =
        copy(title = newTitle)

    fun addKill(): PlayerProfile =
        copy(zombieKills = zombieKills + 1)

    fun addDoorPass(): PlayerProfile =
        copy(doorPasses = doorPasses + 1)

    fun nextLevelXp(): Int = 100 * level

    companion object {
        fun nextLevelXpAt(level: Int): Int = 100 * level
    }
}