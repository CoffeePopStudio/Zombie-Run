package cn.oneachina.zombieRun.model

import io.papermc.paper.datacomponent.item.CustomModelData

data class AmmoConfig(
    val id: String,
    val material: String,
    val customModelData: CustomModelData,
    val name: String,
    val lore: List<String>
)
