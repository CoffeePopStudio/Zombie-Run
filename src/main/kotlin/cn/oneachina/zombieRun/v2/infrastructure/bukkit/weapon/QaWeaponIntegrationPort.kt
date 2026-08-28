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

    override fun refillAmmo(playerId: UUID, weaponType: String, magazines: Int): Boolean {
        if (magazines <= 0) return false
        val player = Bukkit.getPlayer(playerId) ?: return false
        val gun = QualityArmory.getGunByName(weaponType) ?: return false
        val ammo = gun.ammoType ?: return false
        val needed = gun.maxBullets * magazines
        val existing = QualityArmory.getAmmoInInventory(player, ammo)
        if (existing < needed) {
            QualityArmory.addAmmoToInventory(player, ammo, needed - existing)
        }
        return true
    }

    override fun holdsWeapon(playerId: UUID, weaponType: String): Boolean {
        val player = Bukkit.getPlayer(playerId) ?: return false
        val gun = QualityArmory.getGunByName(weaponType) ?: return false
        val item = QualityArmory.getCustomItemAsItemStack(gun) ?: return false
        // QA 枪械以 durability/variant 区分，同样物品的拷贝比较即可覆盖
        return player.inventory.contents?.any { it != null && it.type == item.type && it.durability == item.durability } == true
    }
}