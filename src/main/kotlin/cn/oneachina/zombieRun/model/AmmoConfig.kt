package cn.oneachina.zombieRun.model

data class AmmoConfig(
    val id: String,
    val material: String,
    val customModelData: Int,
    val name: String,
    val lore: List<String>
)
