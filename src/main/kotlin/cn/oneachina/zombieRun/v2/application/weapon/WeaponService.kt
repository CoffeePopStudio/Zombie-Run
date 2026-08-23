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
            messages.chat(playerId, "随机武器：${weapon.displayName}")
        } else {
            messages.chat(playerId, "随机武器 ${weapon.displayName} 发放失败（外部武器系统不可用）")
        }
        return weapon
    }
}