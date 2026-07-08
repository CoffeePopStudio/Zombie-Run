package cn.oneachina.zombieRun.model

data class WeaponConfig(
    val id: String,
    val material: String,
    val customModelData: Int,
    val name: String,
    val lore: List<String>,
    val damage: Double,
    val ammoType: String,
    val maxAmmo: Int,
    val price: Int,
    val cooldownTicks: Int,
    val sound: String?,
    val knockback: Double,
    val range: Int,
    val pellets: Int,
    val automatic: Boolean
)
