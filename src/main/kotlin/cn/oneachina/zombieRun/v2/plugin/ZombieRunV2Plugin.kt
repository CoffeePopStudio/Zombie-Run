package cn.oneachina.zombierun.v2.plugin

import org.bukkit.plugin.java.JavaPlugin

class ZombieRunV2Plugin : JavaPlugin() {

    lateinit var root: V2CompositionRoot
        private set

    override fun onEnable() {
        root = V2CompositionRoot(this)
        root.enable()
        logger.info("ZombieRun v2 enabled")
    }

    override fun onDisable() {
        if (::root.isInitialized) {
            root.disable()
        }
        logger.info("ZombieRun v2 disabled")
    }
}
