package cn.oneachina.zombieRun.model

import io.papermc.paper.datacomponent.item.CustomModelData

enum class FireMode { AUTO, SEMI, BURST }

enum class BoltType {
    /** 开膛待击 — 射击直接从弹匣消耗 */
    OPEN_BOLT,
    /** 闭膛待击 — 膛内有子弹才能射击，无膛弹时自动从弹匣推入 */
    CLOSED_BOLT,
    /** 手动上膛 — 必须膛内有子弹，射击后需拉栓 */
    MANUAL_ACTION
}

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
    /** 射击间隔 (ticks) */
    val cooldownTicks: Int,
    /** 基础散布 (radians) */
    val spread: Double,
    /** ADS 散布倍率 */
    val adsSpreadMult: Double = 0.5,
    /** ADS 后坐力倍率 */
    val adsRecoilMult: Double = 0.7,
    /** 爆头倍率 */
    val headshotMult: Double = 2.0,
    /** 击退力度 */
    val knockback: Double = 0.0,
    /** 射程 (blocks) */
    val range: Int = 30,
    /** 弹丸数 (>1 = 霰弹) */
    val pellets: Int = 1,
    val sound: String? = null,
    val hitSound: String? = null,
    /** 后坐力数组 (度) */
    val recoil: List<Double> = emptyList(),
    /** 连发散布增量 */
    val spreadPerShot: Double = 0.0,
    /** 开火模式 */
    val fireMode: FireMode = FireMode.AUTO,
    /** 枪机类型 */
    val boltType: BoltType = BoltType.OPEN_BOLT,
    /** BURST 模式每次连发数 */
    val burstCount: Int = 3,
    /** ADS 过渡时间 (秒)，0 = 瞬间 */
    val aimTime: Float = 0.15f
)
