package cn.oneachina.zombieRun.model

import io.papermc.paper.datacomponent.item.CustomModelData

data class WeaponConfig(
    val id: String,
    val material: String,
    val customModelData: CustomModelData,
    val name: String,
    val lore: List<String>,
    val damage: Double,
    val ammoCategory: String,
    val magazineSize: Int,
    val maxReserve: Int,
    val reloadTimeTicks: Int,
    val price: Int,
    val cooldownTicks: Int,
    val spread: Double,
    val adsSpreadMult: Double,
    val adsRecoilMult: Double,
    val headshotMult: Double,
    val knockback: Double,
    val range: Int,
    val pellets: Int,
    val sound: String?,
    val hitSound: String?,
    val recoil: List<Double>,
    val automatic: Boolean,
    val spreadPerShot: Double = 0.0
)
