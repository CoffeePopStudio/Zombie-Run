package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.SpecialDoorBehavior
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

object DoorBehaviorCommands {

    fun handle(plugin: ZombieRun, sender: org.bukkit.command.CommandSender, args: Array<out String>, gListener: cn.oneachina.zombieRun.listener.GameListener) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: /zr door behavior <set|remove|info>"))
            return
        }
        when (args[0].lowercase()) {
            "set" -> handleSet(plugin, sender, args.drop(1).toTypedArray(), gListener)
            "remove" -> handleRemove(plugin, sender, args.drop(1).toTypedArray())
            "info" -> handleInfo(plugin, sender, args.drop(1).toTypedArray())
            else -> sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c未知子命令: set, remove, info"))
        }
    }

    private fun handleSet(plugin: ZombieRun, sender: org.bukkit.command.CommandSender, args: Array<out String>, gListener: cn.oneachina.zombieRun.listener.GameListener) {
        val player = sender as? org.bukkit.entity.Player
        if (args.size < 2) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法:"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c/zr door behavior set <门号> subway <线路名> [手打坐标...]"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c/zr door behavior set <门号> elevator"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c/zr door behavior set <门号> airport [delayTicks]"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&e使用 /zr postool 选区后可省略坐标！"))
            return
        }

        val doorNumber = args[0].toIntOrNull()
        if (doorNumber == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c门号必须是数字！"))
            return
        }

        val door = plugin.doorManager.getDoorByNumber(doorNumber)
        if (door == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c门号 $doorNumber 不存在！"))
            return
        }

        val type = args[1].lowercase()
        try {
            val behavior = createBehavior(type, args, plugin, sender, gListener, player)
            if (behavior == null) return

            // 应用到同组所有门
            val groupDoors = if (!door.group.isNullOrBlank()) {
                plugin.doorManager.getDoorsInGroup(door.group!!)
            } else {
                listOf(door)
            }
            groupDoors.forEach { d ->
                d.specialBehavior = behavior
                plugin.configManager.addDoorFull(d)
            }
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&a门号 ${doorNumber}${if (groupDoors.size > 1) "（同组 ${groupDoors.size} 扇门）" else ""} behavior 已设置为 $type"))
        } catch (_: NumberFormatException) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c坐标/参数必须是整数！"))
        }
    }

    private fun createBehavior(
        type: String,
        args: Array<out String>,
        plugin: ZombieRun,
        sender: org.bukkit.command.CommandSender,
        gListener: cn.oneachina.zombieRun.listener.GameListener,
        player: org.bukkit.entity.Player?
    ): SpecialDoorBehavior? {
        // 如果玩家有 postool 选区，优先用选区
        if (player != null && plugin.isPostoolActive(player)) {
            val p1 = gListener.getPos1(player)
            val p2 = gListener.getPos2(player)
            val hTarget = p1
            val zTarget = p2

            return when (type) {
                "elevator" -> {
                    if (hTarget == null) {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c请先用 postool 选取 pos1（人类目标Y）"))
                        return null
                    }
                    SpecialDoorBehavior.Elevator(
                        humanTargetY = hTarget.blockY,
                        zombieTargetY = zTarget?.blockY
                    )
                }
                "subway" -> {
                    val lineName = args.getOrNull(2) ?: "1号线"
                    if (hTarget != null) {
                        SpecialDoorBehavior.Subway(
                            humanTargetX = hTarget.blockX, humanTargetY = hTarget.blockY, humanTargetZ = hTarget.blockZ,
                            zombieTargetX = zTarget?.blockX, zombieTargetY = zTarget?.blockY, zombieTargetZ = zTarget?.blockZ,
                            lineName = lineName
                        )
                    } else if (args.size >= 5) {
                        SpecialDoorBehavior.Subway(
                            humanTargetX = args[2].toInt(), humanTargetY = args[3].toInt(), humanTargetZ = args[4].toInt(),
                            zombieTargetX = args.getOrNull(5)?.toIntOrNull(),
                            zombieTargetY = args.getOrNull(6)?.toIntOrNull(),
                            zombieTargetZ = args.getOrNull(7)?.toIntOrNull(),
                            lineName = lineName
                        )
                    } else {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c请先用 postool 选区或手打坐标！"))
                        return null
                    }
                }
                "airport" -> {
                    val delayTicks = args.getOrNull(2)?.toLongOrNull() ?: 60
                    if (hTarget != null) {
                        SpecialDoorBehavior.Airport(
                            humanTargetX = hTarget.blockX, humanTargetY = hTarget.blockY, humanTargetZ = hTarget.blockZ,
                            zombieTargetX = zTarget?.blockX, zombieTargetY = zTarget?.blockY, zombieTargetZ = zTarget?.blockZ,
                            delayTicks = delayTicks
                        )
                    } else if (args.size >= 5) {
                        SpecialDoorBehavior.Airport(
                            humanTargetX = args[2].toInt(), humanTargetY = args[3].toInt(), humanTargetZ = args[4].toInt(),
                            zombieTargetX = args.getOrNull(5)?.toIntOrNull(),
                            zombieTargetY = args.getOrNull(6)?.toIntOrNull(),
                            zombieTargetZ = args.getOrNull(7)?.toIntOrNull(),
                            delayTicks = delayTicks
                        )
                    } else {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c请先用 postool 选区或手打坐标！"))
                        return null
                    }
                }
                else -> {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c未知类型: $type"))
                    return null
                }
            }
        }

        // 无选区，手打坐标
        return when (type) {
            "elevator" -> {
                if (args.size < 3) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: ... elevator <human-y> [zombie-y]"))
                    return null
                }
                SpecialDoorBehavior.Elevator(
                    humanTargetY = args[2].toInt(),
                    zombieTargetY = args.getOrNull(3)?.toIntOrNull()
                )
            }
            "subway" -> {
                if (args.size < 6) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: ... subway <线路名> <hx> <hy> <hz> [zx] [zy] [zz]"))
                    return null
                }
                SpecialDoorBehavior.Subway(
                    humanTargetX = args[2].toInt(), humanTargetY = args[3].toInt(), humanTargetZ = args[4].toInt(),
                    zombieTargetX = args.getOrNull(5)?.toIntOrNull(),
                    zombieTargetY = args.getOrNull(6)?.toIntOrNull(),
                    zombieTargetZ = args.getOrNull(7)?.toIntOrNull(),
                    lineName = args[1]
                )
            }
            "airport" -> {
                if (args.size < 5) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: ... airport <hx> <hy> <hz> [zx] [zy] [zz] [delayTicks]"))
                    return null
                }
                SpecialDoorBehavior.Airport(
                    humanTargetX = args[2].toInt(), humanTargetY = args[3].toInt(), humanTargetZ = args[4].toInt(),
                    zombieTargetX = args.getOrNull(5)?.toIntOrNull(),
                    zombieTargetY = args.getOrNull(6)?.toIntOrNull(),
                    zombieTargetZ = args.getOrNull(7)?.toIntOrNull(),
                    delayTicks = args.getOrNull(8)?.toLongOrNull() ?: 60
                )
            }
            else -> {
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c未知类型: $type"))
                null
            }
        }
    }

    private fun handleRemove(plugin: ZombieRun, sender: org.bukkit.command.CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: /zr door behavior remove <门号>"))
            return
        }
        val doorNumber = args[0].toIntOrNull()
        if (doorNumber == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c门号必须是数字！"))
            return
        }
        val door = plugin.doorManager.getDoorByNumber(doorNumber)
        if (door == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c门号 $doorNumber 不存在！"))
            return
        }
        val groupDoors = if (!door.group.isNullOrBlank()) plugin.doorManager.getDoorsInGroup(door.group!!) else listOf(door)
        groupDoors.forEach { d ->
            d.specialBehavior = null
            plugin.configManager.addDoorFull(d)
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&a门号 $doorNumber 的 behavior 已移除"))
    }

    private fun handleInfo(plugin: ZombieRun, sender: org.bukkit.command.CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c用法: /zr door behavior info <门号>"))
            return
        }
        val doorNumber = args[0].toIntOrNull()
        if (doorNumber == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c门号必须是数字！"))
            return
        }
        val door = plugin.doorManager.getDoorByNumber(doorNumber)
        if (door == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&c门号 $doorNumber 不存在！"))
            return
        }
        plugin.logger.info("===== 门号 $doorNumber =====")
        plugin.logger.info("名称: ${door.name}")
        plugin.logger.info("模式: ${door.mode}")
        plugin.logger.info("组: ${door.group ?: "-"}")
        plugin.logger.info("duration: ${door.duration}")
        val b = door.specialBehavior
        if (b == null) {
            plugin.logger.info("特殊行为: 无")
        } else when (b) {
            is SpecialDoorBehavior.Elevator -> {
                plugin.logger.info("类型: 电梯")
                plugin.logger.info("人类Y: ${b.humanTargetY}")
                plugin.logger.info("僵尸Y: ${b.zombieTargetY ?: "无"}")
                plugin.logger.info("倒计时: ${b.countdown}s")
            }
            is SpecialDoorBehavior.Subway -> {
                plugin.logger.info("类型: 地铁")
                plugin.logger.info("线路: ${b.lineName}")
                plugin.logger.info("人类目标: (${b.humanTargetX}, ${b.humanTargetY}, ${b.humanTargetZ})")
                if (b.zombieTargetX != null) plugin.logger.info("僵尸目标: (${b.zombieTargetX}, ${b.zombieTargetY}, ${b.zombieTargetZ})")
            }
            is SpecialDoorBehavior.Airport -> {
                plugin.logger.info("类型: 机场")
                plugin.logger.info("人类目标: (${b.humanTargetX}, ${b.humanTargetY}, ${b.humanTargetZ})")
                if (b.zombieTargetX != null) plugin.logger.info("僵尸目标: (${b.zombieTargetX}, ${b.zombieTargetY}, ${b.zombieTargetZ})")
                plugin.logger.info("延迟: ${b.delayTicks} ticks")
            }
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("&a门号 $doorNumber 信息已输出到控制台"))
    }
}
