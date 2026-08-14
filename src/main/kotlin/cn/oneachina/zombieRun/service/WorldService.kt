package cn.oneachina.zombieRun.service

import cn.oneachina.zombieRun.ZombieRun
import com.onarandombox.MultiverseCore.MultiverseCore
import org.bukkit.Bukkit
import org.bukkit.World

class WorldService(private val plugin: ZombieRun) {
    private var mvCore: MultiverseCore? = null

    fun init() {
        try {
            val mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core")
            if (mv is MultiverseCore) {
                mvCore = mv
                plugin.logger.info("检测到 Multiverse-Core，启用多世界模式")
            }
        } catch (_: NoClassDefFoundError) {
            // MV 不在 classpath 上（未安装），安全回退单世界
        }
    }

    fun getWorld(name: String): World? {
        if (mvCore != null) {
            try {
                mvCore?.mvWorldManager?.getMVWorld(name)?.cbWorld?.let { return it }
            } catch (_: NoClassDefFoundError) {
                // MV 不存在，回退 Bukkit
            }
        }
        return Bukkit.getWorld(name)
    }

    fun getWorldOrFirst(name: String): World {
        getWorld(name)?.let { return it }
        val fallback = Bukkit.getWorlds().firstOrNull()
            ?: throw IllegalStateException("服务器上没有任何已加载的世界")
        plugin.logger.warning("世界 $name 未加载，回退使用世界 ${fallback.name}")
        return fallback
    }

    fun isMultiverseEnabled(): Boolean = mvCore != null
}
