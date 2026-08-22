package cn.oneachina.zombierun.v2.ports

import cn.oneachina.zombierun.v2.domain.door.BlockRegion
import cn.oneachina.zombierun.v2.domain.door.Vec3
import java.util.UUID

data class PlayerRef(
    val id: UUID,
    val name: String,
    val worldName: String,
    val position: Vec3,
)

/** 玩家与世界的只读访问，屏蔽 Bukkit Player/World 类型。 */
interface WorldAccessPort {
    fun playersIn(worldName: String): List<PlayerRef>
    fun player(id: UUID): PlayerRef?
    fun worldLoaded(worldName: String): Boolean
}

/** 门方块开/关操作。实现必须通过 region scheduler 执行。 */
interface BlockOpsPort {
    fun openRegion(worldName: String, region: BlockRegion)

    fun closeRegion(
        worldName: String,
        region: BlockRegion,
        snapshot: Map<String, String>,
        fallbackMaterial: String,
    )

    fun scanRegion(worldName: String, region: BlockRegion): Map<String, String>
}

/** 面向玩家的消息输出（Chat / ActionBar / Title）。 */
interface PlayerMessagePort {
    fun actionBar(playerId: UUID, message: String)
    fun chat(playerId: UUID, message: String)
    fun title(playerId: UUID, title: String, subtitle: String)
    fun soundBell(worldName: String)
}

/** 传送端口。实现需使用 Paper 的 teleportAsync 语义。 */
interface TeleporterPort {
    fun teleport(playerId: UUID, worldName: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float)
}
