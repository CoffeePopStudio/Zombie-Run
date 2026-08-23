package cn.oneachina.zombierun.v2.domain.weapon

enum class WeaponCategory { GUN, MELEE, SPECIAL }

/**
 * v2 武器配置领域模型。
 * [type] 是外部武器系统（如 QualityArmory）使用的物品/枪械标识。
 */
data class WeaponDefinition(
    val id: String,
    val displayName: String,
    val type: String,
    val category: WeaponCategory,
    val price: Double,
    val enabled: Boolean = true,
)

fun List<WeaponDefinition>.randomWeapon(): WeaponDefinition? {
    if (isEmpty()) return null
    val enabled = filter { it.enabled }
    if (enabled.isEmpty()) return null
    return enabled.random()
}

fun List<WeaponDefinition>.randomGun(): WeaponDefinition? =
    filter { it.enabled && it.category == WeaponCategory.GUN }.randomOrNull()