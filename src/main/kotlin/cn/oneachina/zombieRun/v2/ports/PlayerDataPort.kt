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
}