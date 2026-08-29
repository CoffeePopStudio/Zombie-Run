package cn.oneachina.zombierun.v2.application.weapon

import cn.oneachina.zombierun.v2.domain.weapon.WeaponCategory
import cn.oneachina.zombierun.v2.domain.weapon.WeaponDefinition
import cn.oneachina.zombierun.v2.infrastructure.config.WeaponYamlRepository
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.WeaponIntegrationPort
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.UUID

/**
 * 武器用例：配置读取、随机发枪、主动发枪。
 * 购买结算在 M5 经济系统接入后扩展。
 */
class WeaponService(
    private val repository: WeaponYamlRepository,
    private val integration: WeaponIntegrationPort,
    private val messages: PlayerMessagePort,
    private val logger: V2Logger,
) {
    /** 玩家预选武器（内存态，对齐 v1 /zr select）。 */
    private val selectedWeapon = java.util.concurrent.ConcurrentHashMap<UUID, String>()

    fun selectWeapon(playerId: UUID, weaponId: String): Boolean {
        val weapon = repository.byId(weaponId) ?: return false
        if (!weapon.enabled) return false
        selectedWeapon[playerId] = weaponId
        messages.chat(playerId, "已预选武器：${weapon.displayName}，开局自动购买发放")
        return true
    }

    fun unselectWeapon(playerId: UUID) {
        selectedWeapon.remove(playerId)
        messages.chat(playerId, "已取消预选武器")
    }

    fun selectedWeaponId(playerId: UUID): String? = selectedWeapon[playerId]

    fun clearSelected(playerId: UUID) {
        selectedWeapon.remove(playerId)
    }
    fun reload() {
        repository.loadAll()
    }

    fun all(): List<WeaponDefinition> = repository.all()

    fun byId(id: String): WeaponDefinition? = repository.byId(id)

    fun add(weapon: WeaponDefinition) {
        repository.save(weapon)
        logger.info("weapon ${weapon.id} saved (${weapon.type})")
    }

    fun remove(id: String): Boolean = repository.remove(id)

    fun giveWeapon(playerId: UUID, weaponId: String): Boolean {
        val weapon = repository.byId(weaponId) ?: return false
        val ok = integration.giveWeapon(playerId, weapon.type)
        if (ok) {
            messages.chat(playerId, "已发放武器：${weapon.displayName}")
        } else {
            messages.chat(playerId, "武器 ${weapon.displayName} 发放失败（外部武器系统不可用）")
        }
        return ok
    }

    fun giveRandom(playerId: UUID, category: WeaponCategory?): WeaponDefinition? {
        val candidates = repository.all().filter { it.enabled && (category == null || it.category == category) }
        val weapon = candidates.randomOrNull() ?: return null
        val ok = integration.giveWeapon(playerId, weapon.type)
        if (ok) {
            integration.refillAmmo(playerId, weapon.type, 1)
            messages.chat(playerId, "随机武器：${weapon.displayName}")
        } else {
            messages.chat(playerId, "随机武器 ${weapon.displayName} 发放失败（外部武器系统不可用）")
        }
        return weapon
    }

    /**
     * 开局发放：发武器本身（用配置的 type 精确匹配 QA 枪械），成功后补满弹药。
     * 与商店购买的差异：不扣款、必定补弹。
     */
    fun giveStarter(playerId: UUID, weaponId: String): Boolean {
        val weapon = repository.byId(weaponId) ?: return false
        val ok = integration.giveWeapon(playerId, weapon.type)
        if (!ok) {
            messages.chat(playerId, "武器 ${weapon.displayName} 发放失败（外部武器系统不可用）")
            return false
        }
        integration.refillAmmo(playerId, weapon.type, STARTER_MAGAZINES)
        return true
    }

    /** 主动发枪（命令/商店），成功即补 1 个弹匣，保证能直接开火。 */
    fun giveWeaponWithAmmo(playerId: UUID, weaponId: String): Boolean {
        val weapon = repository.byId(weaponId) ?: return false
        val ok = integration.giveWeapon(playerId, weapon.type)
        if (!ok) {
            messages.chat(playerId, "武器 ${weapon.displayName} 发放失败（外部武器系统不可用）")
            return false
        }
        integration.refillAmmo(playerId, weapon.type, 1)
        return true
    }

    /** 补充弹药（局内购买用），返回是否成功。 */
    fun refillAmmo(playerId: UUID, weaponId: String, magazines: Int): Boolean {
        val weapon = repository.byId(weaponId) ?: return false
        return integration.refillAmmo(playerId, weapon.type, magazines)
    }

    companion object {
        /** 开局补发的弹匣数量 */
        const val STARTER_MAGAZINES = 5
    }
}