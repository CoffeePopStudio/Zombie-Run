package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

object CoinCommands {

    fun handle(plugin: ZombieRun, sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr coins <add|remove|set|get|top> [...]", NamedTextColor.RED))
            return
        }
        when (args[0].lowercase()) {
            "add", "remove", "set" -> {
                if (!sender.hasPermission("zombie.run.admin")) {
                    sender.sendMessage(Component.text("你没有权限使用此命令！", NamedTextColor.RED))
                    return
                }
                if (args.size < 3) {
                    sender.sendMessage(Component.text("用法: /zr coins ${args[0]} <玩家> <金额>", NamedTextColor.RED))
                    return
                }
                val amount = args[2].toIntOrNull()
                if (amount == null || amount <= 0) {
                    sender.sendMessage(Component.text("金额必须是正整数！", NamedTextColor.RED))
                    return
                }
                val target = Bukkit.getPlayer(args[1])
                if (target == null) {
                    sender.sendMessage(Component.text("玩家不在线！", NamedTextColor.RED))
                    return
                }
                when (args[0]) {
                    "add" -> {
                        plugin.coinManager.addCoins(target.uniqueId, amount)
                        sender.sendMessage(Component.text("已给 ${target.name} 增加 $amount 硬币", NamedTextColor.GREEN))
                        target.sendMessage(Component.text("管理员给了你 $amount 硬币", NamedTextColor.GOLD))
                    }
                    "remove" -> {
                        if (!plugin.coinManager.takeCoins(target.uniqueId, amount)) {
                            sender.sendMessage(Component.text("玩家硬币不足！", NamedTextColor.RED))
                        } else {
                            sender.sendMessage(Component.text("已从 ${target.name} 扣除 $amount 硬币", NamedTextColor.GREEN))
                        }
                    }
                    "set" -> {
                        plugin.coinManager.setCoins(target.uniqueId, amount)
                        sender.sendMessage(Component.text("已将 ${target.name} 的硬币设置为 $amount", NamedTextColor.GREEN))
                    }
                }
            }
            "get" -> {
                if (!sender.hasPermission("zombie.run.admin")) return
                val targetName = if (args.size > 1) args[1] else sender.name
                val player = Bukkit.getPlayer(targetName)
                val coins = if (player != null) {
                    plugin.coinManager.getCoins(player.uniqueId)
                } else {
                    sender.sendMessage(Component.text("玩家不在线", NamedTextColor.RED))
                    return
                }
                sender.sendMessage(Component.text("${targetName} 的硬币: $coins", NamedTextColor.GREEN))
            }
            "top" -> {
                val count = if (args.size > 1) args[1].toIntOrNull() ?: 10 else 10
                val top = plugin.coinManager.getTopCoins(count)
                sender.sendMessage(Component.text("===== 硬币排行榜 (TOP $count) =====", NamedTextColor.GREEN))
                top.forEachIndexed { index, (name, coins) ->
                    sender.sendMessage(Component.text("${index + 1}. $name - $coins 硬币", NamedTextColor.GREEN))
                }
            }
            else -> sender.sendMessage(Component.text("未知子命令，可用: add, remove, set, get, top", NamedTextColor.RED))
        }
    }
}
