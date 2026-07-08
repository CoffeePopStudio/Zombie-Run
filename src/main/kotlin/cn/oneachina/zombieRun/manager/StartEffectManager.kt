package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import org.bukkit.Bukkit
import java.util.concurrent.CopyOnWriteArrayList

class StartEffectManager(private val plugin: ZombieRun) {

    private val effects = CopyOnWriteArrayList<StartEffect>()

    data class StartEffect(val type: String, val command: String, val delay: Long)

    fun loadEffects() {
        effects.clear()
        val config = plugin.configManager.getConfig()
        val section = config.getConfigurationSection("start-effects") ?: return

        for (key in section.getKeys(false)) {
            val effectSection = section.getConfigurationSection(key) ?: continue
            val type = effectSection.getString("type") ?: "command"
            val command = effectSection.getString("command") ?: continue
            val delay = effectSection.getLong("delay", 0L)
            effects.add(StartEffect(type, command, delay))
        }

        plugin.logger.info("已加载 ${effects.size} 个开场效果")
    }

    fun executeStartEffects() {
        var cumulativeDelay = 0L
        for (effect in effects) {
            cumulativeDelay += effect.delay
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
                when (effect.type.lowercase()) {
                    "command" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), effect.command)
                    else -> plugin.logger.warning("未知的效果类型: ${effect.type}")
                }
            }, cumulativeDelay)
        }
    }
}