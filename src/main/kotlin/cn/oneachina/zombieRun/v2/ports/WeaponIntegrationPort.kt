package cn.oneachina.zombierun.v2.ports

import java.util.UUID

/**
 * 外部武器系统集成端口。QualityArmory 等实现必须线程安全。
 */
interface WeaponIntegrationPort {
    /** 给玩家发放武器，返回是否成功。 */
    fun giveWeapon(playerId: UUID, weaponType: String): Boolean

    /** 从玩家身上移除武器，返回是否成功。 */
    fun removeWeapon(playerId: UUID, weaponType: String): Boolean

    /** 武器类型是否在外部系统中可用。 */
    fun isAvailable(weaponType: String): Boolean

    /**
     * 给玩家补充弹药：一次性补满 [magazines] 个弹匣的量（不超过 QA 弹药上限逻辑）。
     * 返回是否成功（武器不存在或无弹药类型时为 false）。
     */
    fun refillAmmo(playerId: UUID, weaponType: String, magazines: Int): Boolean = false

    /** 玩家背包中是否持有该武器（用于按持有武器计算补弹费用）。无法判定时返回 false。 */
    fun holdsWeapon(playerId: UUID, weaponType: String): Boolean = false
}