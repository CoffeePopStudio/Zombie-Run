package cn.oneachina.zombieRun.util

import cn.oneachina.zombieRun.ZombieRun
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit

object DebugLogger {

    private var plugin: ZombieRun? = null

    fun init(plugin: ZombieRun) {
        this.plugin = plugin
    }

    private fun log(prefix: String, msg: Component) {
        val p = plugin ?: return
        if (!p.debugMode) return
        val full = Component.text()
            .append(Component.text("[Debug-", NamedTextColor.GRAY))
            .append(Component.text(prefix, NamedTextColor.YELLOW))
            .append(Component.text("] ", NamedTextColor.GRAY))
            .append(msg)
            .build()
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(full) }
    }

    fun door(msg: String) = door(Component.text(msg, NamedTextColor.WHITE))
    fun door(msg: Component) = log("门", msg)

    fun damage(msg: String) = damage(Component.text(msg, NamedTextColor.WHITE))
    fun damage(msg: Component) = log("伤害", msg)

    fun room(msg: String) = room(Component.text(msg, NamedTextColor.WHITE))
    fun room(msg: Component) = log("区域", msg)

    fun game(msg: String) = game(Component.text(msg, NamedTextColor.WHITE))
    fun game(msg: Component) = log("游戏", msg)
}
