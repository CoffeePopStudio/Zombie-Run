package cn.oneachina.zombierun.v2.domain.door

enum class DoorMode { NORMAL, START, PLAYER, ZOMBIE }

data class DoorDefinition(
    val id: String,
    val world: String,
    /** normal 门的顺序门号；start/player/zombie 门为 null */
    val number: Int?,
    val mode: DoorMode,
    val group: String?,
    val openSeconds: Int,
    val closeSeconds: Int,
    val portal: Portal,
    val region: BlockRegion,
    /** 方块快照文件名；为空则使用 [fallbackMaterial] 整体恢复 */
    val snapshotId: String?,
    val fallbackMaterial: String,
) {
    init {
        require(openSeconds > 0) { "door $id: openSeconds must be positive" }
        require(closeSeconds > 0) { "door $id: closeSeconds must be positive" }
    }
}
