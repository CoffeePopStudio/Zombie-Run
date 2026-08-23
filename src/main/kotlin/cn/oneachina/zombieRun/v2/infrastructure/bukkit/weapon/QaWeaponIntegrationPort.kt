package cn.oneachina.zombierun.v2.infrastructure.bukkit.weapon

import cn.oneachina.zombierun.v2.ports.WeaponIntegrationPort
import cn.oneachina.zombierun.v2.support.V2Logger
import me.zombie_striker.qg.api.QualityArmory
import org.bukkit.Bukkit
import java.util.UUID

class QaWeaponIntegrationPort(
    private val logger: V2Logger,
) : WeaponIntegrationPort {

    override fun giveWeapon(playerId: UUID, weaponType: String): Boolean {
        val player = Bukkit.getPlayer(playerId) ?: return false
        val gun = QualityArmory.getGunByName(weaponType) ?: run {
            logger.warn("QA gun not found: $weaponType")
            return false
        }
        val item = QualityArmory.getCustomItemAsItemStack(gun) ?: return false
        QualityArmory.giveOrDrop(player, item)
        return true
    }

    override fun removeWeapon(playerId: UUID, weaponType: String): Boolean {
        val player = Bukkit.getPlayer(playerId) ?: return false
        val gun = QualityArmory.getGunByName(weaponType) ?: return false
        val item = QualityArmory.getCustomItemAsItemStack(gun) ?: return false
        player.inventory.removeItem(item)
        return true
    }

    override fun isAvailable(weaponType: String): Boolean = QualityArmory.getGunByName(weaponType) != null
}