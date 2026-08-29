package cn.oneachina.zombierun.v2.ports

import cn.oneachina.zombierun.v2.domain.player.PlayerProfile
import java.util.UUID

/**
 * 玩家持久化端口。实现负责 SQLite/文件存储。
 */
interface PlayerDataPort {
    fun load(playerId: UUID): PlayerProfile?
    fun save(profile: PlayerProfile)
    fun close()

    /** 按硬币余额倒序返回 Top-N；默认空列表便于测试替身，正式存储应覆盖。 */
    fun topCoins(limit: Int): List<Pair<UUID, Int>> = emptyList()
}