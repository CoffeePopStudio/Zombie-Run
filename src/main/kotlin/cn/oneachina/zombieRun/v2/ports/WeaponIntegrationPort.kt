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
}