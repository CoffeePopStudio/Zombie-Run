package cn.oneachina.zombierun.v2.domain.door

data class BlockRegion(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) {
    val centerX: Double get() = (minX + maxX) / 2.0
    val centerY: Double get() = (minY + maxY) / 2.0
    val centerZ: Double get() = (minZ + maxZ) / 2.0
    val volume: Long
        get() = (maxX - minX + 1L) * (maxY - minY + 1L) * (maxZ - minZ + 1L)
}
