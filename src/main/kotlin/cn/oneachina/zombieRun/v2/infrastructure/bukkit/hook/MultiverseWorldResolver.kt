package cn.oneachina.zombierun.v2.infrastructure.bukkit.hook

import com.onarandombox.MultiverseCore.MultiverseCore
import org.bukkit.Bukkit
import org.bukkit.World

/**
 * Multiverse 世界名解析：优先按 Bukkit 世界名精确匹配；
 * 若 v1 配置使用别名/大小写差异，在 Multiverse 的世界表中进行忽略大小写匹配。
 */
object MultiverseWorldResolver {

    fun resolve(name: String): String {
        if (Bukkit.getWorld(name) != null) return name
        val multiverse = Bukkit.getPluginManager().getPlugin("Multiverse-Core")
        if (multiverse !is MultiverseCore) return name
        val manager = multiverse.getMVWorldManager()
        val worlds: List<World> = manager.getMVWorlds().mapNotNull { it.getCBWorld() }
        val alias = worlds.firstOrNull { it.name.equals(name, ignoreCase = true) }?.name ?: return name
        return alias
    }
}