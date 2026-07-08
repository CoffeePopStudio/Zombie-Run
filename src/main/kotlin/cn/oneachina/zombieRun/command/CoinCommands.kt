package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

object CoinCommands {

    fun handle(plugin: ZombieRun, sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr coins <add|remove|set|get|top> [...]")
            return
        }
        when (args[0].lowercase()) {
            "add", "remove", "set" -> {
                if (!sender.hasPermission("zombie.run.admin")) {
                    sender.sendMessage("§c你没有权限使用此命令！")
                    return
                }
                if (args.size < 3) {
                    sender.sendMessage("§c用法: /zr coins ${args[0]} <玩家> <金额>")
                    return
                }
                val amount = args[2].toIntOrNull()
                if (amount == null || amount <= 0) {
                    sender.sendMessage("§c金额必须是正整数！")
                    return
                }
                val target = Bukkit.getPlayer(args[1])
                if (target == null) {
                    sender.sendMessage("§c玩家不在线！")
                    return
                }
                when (args[0]) {
                    "add" -> {
                        plugin.coinManager.addCoins(target.uniqueId, amount)
                        sender.sendMessage("§a已给 ${target.name} 增加 $amount 硬币")
                        target.sendMessage("§6管理员给了你 $amount 硬币")
                    }
                    "remove" -> {
                        if (!plugin.coinManager.takeCoins(target.uniqueId, amount)) {
                            sender.sendMessage("§c玩家硬币不足！")
                        } else {
                            sender.sendMessage("§a已从 ${target.name} 扣除 $amount 硬币")
                        }
                    }
                    "set" -> {
                        plugin.coinManager.setCoins(target.uniqueId, amount)
                        sender.sendMessage("§a已将 ${target.name} 的硬币设置为 $amount")
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
                    sender.sendMessage("§c玩家不在线")
                    return
                }
                sender.sendMessage("§a${targetName} 的硬币: $coins")
            }
            "top" -> {
                val count = if (args.size > 1) args[1].toIntOrNull() ?: 10 else 10
                val top = plugin.coinManager.getTopCoins(count)
                sender.sendMessage("§a===== 硬币排行榜 (TOP $count) =====")
                top.forEachIndexed { index, (name, coins) ->
                    sender.sendMessage("§a${index + 1}. $name - $coins 硬币")
                }
            }
            else -> sender.sendMessage("§c未知子命令，可用: add, remove, set, get, top")
        }
    }
}
