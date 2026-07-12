package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.SpecialDoorBehavior
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender

object DoorBehaviorCommands {

    fun handle(plugin: ZombieRun, sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: /zr door behavior <set|remove|info>"))
            return
        }
        when (args[0].lowercase()) {
            "set" -> handleSet(plugin, sender, args.drop(1).toTypedArray())
            "remove" -> handleRemove(plugin, sender, args.drop(1).toTypedArray())
            "info" -> handleInfo(plugin, sender, args.drop(1).toTypedArray())
            else -> sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c未知子命令: set, remove, info"))
        }
    }

    private fun handleSet(plugin: ZombieRun, sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法:"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c/zr door behavior set <门名> elevator <target-y> [countdown]"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c/zr door behavior set <门名> subway <target-x> <target-y> <target-z> [line-name] [countdown]"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c/zr door behavior set <门名> airport <target-x> <target-y> <target-z> [delay-ticks]"))
            return
        }
        val doorName = args[0]
        val door = plugin.doorManager.getDoorByName(doorName)
        if (door == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c门 '$doorName' 不存在！"))
            return
        }

        val type = args[1].lowercase()
        try {
            val behavior = when (type) {
                "elevator" -> {
                    if (args.size < 3) {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: ... elevator <target-y> [countdown]"))
                        return
                    }
                    SpecialDoorBehavior.Elevator(
                        targetY = args[2].toInt(),
                        countdown = args.getOrNull(3)?.toIntOrNull() ?: 5
                    )
                }
                "subway" -> {
                    if (args.size < 5) {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: ... subway <target-x> <target-y> <target-z> [line-name] [countdown]"))
                        return
                    }
                    SpecialDoorBehavior.Subway(
                        targetX = args[2].toInt(),
                        targetY = args[3].toInt(),
                        targetZ = args[4].toInt(),
                        lineName = args.getOrNull(5) ?: "1号线",
                        countdown = args.getOrNull(6)?.toIntOrNull() ?: 10
                    )
                }
                "airport" -> {
                    if (args.size < 5) {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: ... airport <target-x> <target-y> <target-z> [delay-ticks]"))
                        return
                    }
                    SpecialDoorBehavior.Airport(
                        targetX = args[2].toInt(),
                        targetY = args[3].toInt(),
                        targetZ = args[4].toInt(),
                        delayTicks = args.getOrNull(5)?.toLongOrNull() ?: 60
                    )
                }
                else -> {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c未知类型: $type，可用: elevator, subway, airport"))
                    return
                }
            }
            door.specialBehavior = behavior
            plugin.configManager.addDoorFull(door)
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&a门 '$doorName' 的 behavior 已设置为 $type"))
        } catch (_: NumberFormatException) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c坐标/参数必须是整数！"))
        }
    }

    private fun handleRemove(plugin: ZombieRun, sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: /zr door behavior remove <门名>"))
            return
        }
        val door = plugin.doorManager.getDoorByName(args[0])
        if (door == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c门 '${args[0]}' 不存在！"))
            return
        }
        door.specialBehavior = null
        plugin.configManager.addDoorFull(door)
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&a门 '${args[0]}' 的 behavior 已移除"))
    }

    private fun handleInfo(plugin: ZombieRun, sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: /zr door behavior info <门名>"))
            return
        }
        val door = plugin.doorManager.getDoorByName(args[0])
        if (door == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c门 '${args[0]}' 不存在！"))
            return
        }
        val b = door.specialBehavior
        if (b == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&7门 '${args[0]}' 没有特殊行为"))
            return
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&a===== ${args[0]} 特殊行为 ====="))
        when (b) {
            is SpecialDoorBehavior.Elevator -> {
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&e类型: 电梯"))
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&e目标Y: ${b.targetY}"))
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&e倒计时: ${b.countdown}s"))
            }
            is SpecialDoorBehavior.Subway -> {
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&b类型: 地铁"))
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&b线路: ${b.lineName}"))
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&b目标: (${b.targetX}, ${b.targetY}, ${b.targetZ})"))
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&b发车倒计时: ${b.countdown}s"))
            }
            is SpecialDoorBehavior.Airport -> {
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&6类型: 机场专线"))
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&6目标: (${b.targetX}, ${b.targetY}, ${b.targetZ})"))
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&6延迟: ${b.delayTicks} ticks"))
            }
        }
    }
}
