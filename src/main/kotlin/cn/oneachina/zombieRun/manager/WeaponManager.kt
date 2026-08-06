package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import me.zombie_striker.qg.api.QualityArmory
import me.zombie_striker.qg.guns.Gun
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 枪械适配层。
 *
 * 枪械、弹药、伤害、射速、弹匣、换弹、价格等全部配置由 QualityArmory 负责，
 * 本类仅封装 ZombieRun 需要的发放与查询。
 * 伤害规则与统计在 CombatListener 中通过 QA 事件接管。
 */
class WeaponManager(private val plugin: ZombieRun) {

    /** 每局补发的弹匣数量（QA 无独立后备弹药上限概念，按弹匣容量倍数发放） */
    private val magazinesPerGame = 5

    /** 可发放的枪械 ID（= QA 枪械名称），排除近战/投掷等非枪械类型 */
    fun getWeaponIds(): List<String> = buildList {
        QualityArmory.getGuns().forEachRemaining { gun ->
            if (gun.weaponType.isGun) add(gun.name)
        }
    }

    fun getGun(id: String): Gun? = QualityArmory.getGunByName(id)

    /** 枪械价格（QA 配置，单位硬币） */
    fun getWeaponPrice(id: String): Double = getGun(id)?.price ?: 0.0

    fun buildWeaponItem(id: String): ItemStack? {
        val qaGun = QualityArmory.getGunByName(id) ?: run {
            plugin.logger.warning("未找到 QualityArmory 枪械: $id")
            return null
        }
        return QualityArmory.getCustomItemAsItemStack(qaGun)
    }

    fun giveWeapon(player: Player, weaponId: String): Boolean {
        val item = buildWeaponItem(weaponId) ?: return false
        QualityArmory.giveOrDrop(player, item)
        return true
    }

    /** 开局发放弹药：将该枪对应弹药补到 magazinesPerGame 个弹匣的量 */
    fun giveAmmoRespectingMaxReserve(player: Player, weaponId: String) {
        val gun = QualityArmory.getGunByName(weaponId) ?: return
        val ammo = gun.ammoType ?: return
        val needed = gun.maxBullets * magazinesPerGame
        val existing = QualityArmory.getAmmoInInventory(player, ammo)
        if (existing < needed) {
            QualityArmory.addAmmoToInventory(player, ammo, needed - existing)
        }
    }
}
