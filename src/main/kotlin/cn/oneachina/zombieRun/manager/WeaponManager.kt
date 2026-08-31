package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import me.zombie_striker.customitemmanager.CustomBaseObject
import me.zombie_striker.qg.api.QualityArmory
import me.zombie_striker.qg.attachments.AttachmentBase
import me.zombie_striker.qg.guns.Gun
import me.zombie_striker.qg.miscitems.MeleeItems
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 枪械/道具适配层。
 *
 * 枪械、道具、弹药、伤害、射速、弹匣、换弹、价格等全部配置由 QualityArmory 负责，
 * 本类仅封装 ZombieRun 需要的发放与查询。
 * 伤害规则与统计在 CombatListener 中通过 QA 事件接管。
 */
class WeaponManager(private val plugin: ZombieRun) {

    /** 每局补发的弹匣数量（QA 无独立后备弹药上限概念，按弹匣容量倍数发放） */
    private val magazinesPerGame = 5

    /** 可发放的枪械 ID（= QA 枪械名称），排除附件（AttachmentBase） */
    fun getWeaponIds(): List<String> = buildList {
        QualityArmory.getGuns().forEachRemaining { gun ->
            // QA 2.1.4 从 YAML 加载的默认枪械 getWeaponType() 是 null，
            // 所以不能再用 weaponType.isGun 过滤。
            // gunRegister 里只有 Gun 和 AttachmentBase，排除附件即可得到真正的枪。
            if (gun !is AttachmentBase) add(gun.name)
        }
    }

    /** 可发放的 QA 道具 ID（手榴弹/烟雾弹/闪光弹/燃烧瓶/地雷/医疗包等），排除近战武器 */
    fun getGadgetIds(): List<String> = buildList {
        QualityArmory.getMisc().forEachRemaining { item ->
            if (item !is MeleeItems) add(item.name)
        }
    }

    /** 商店/随机池可用的所有 QA 物品（枪械 + 道具） */
    fun getAllItemIds(): List<String> = getWeaponIds() + getGadgetIds()

    fun getGun(id: String): Gun? = QualityArmory.getGunByName(id)

    fun getItem(id: String): CustomBaseObject? = QualityArmory.getCustomItemByName(id)

    /** 物品价格（QA 配置，单位硬币），枪械和道具通用 */
    fun getWeaponPrice(id: String): Double = getItem(id)?.price ?: 0.0

    fun buildWeaponItem(id: String): ItemStack? {
        val qaGun = QualityArmory.getGunByName(id) ?: run {
            plugin.logger.warning("未找到 QualityArmory 枪械: $id")
            return null
        }
        return QualityArmory.getCustomItemAsItemStack(qaGun)
    }

    fun buildGadgetItem(id: String): ItemStack? {
        val item = getItem(id) ?: run {
            plugin.logger.warning("未找到 QualityArmory 道具: $id")
            return null
        }
        return QualityArmory.getCustomItemAsItemStack(item)
    }

    fun giveWeapon(player: Player, weaponId: String): Boolean {
        val item = buildWeaponItem(weaponId) ?: return false
        QualityArmory.giveOrDrop(player, item)
        return true
    }

    fun giveGadget(player: Player, gadgetId: String): Boolean {
        val item = buildGadgetItem(gadgetId) ?: return false
        QualityArmory.giveOrDrop(player, item)
        return true
    }

    /** 开局发放弹药：将该枪对应弹药补到 magazinesPerGame 个弹匣的量 */
    fun giveAmmoRespectingMaxReserve(player: Player, weaponId: String) {
        val gun = getGun(weaponId) ?: return
        val ammo = gun.ammoType ?: return
        val needed = gun.maxBullets * magazinesPerGame
        val existing = QualityArmory.getAmmoInInventory(player, ammo)
        if (existing < needed) {
            QualityArmory.addAmmoToInventory(player, ammo, needed - existing)
        }
    }
}
