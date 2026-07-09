package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import cn.oneachina.zombieRun.model.Button
import cn.oneachina.zombieRun.model.Door
import cn.oneachina.zombieRun.model.Respawn
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ZombieRunCommand(private val plugin: ZombieRun) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "start", "spawn", "doors", "buttons", "reload", "open", "close", "reset" -> {
                if (!sender.hasPermission("zombie.run.admin")) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c你没有权限使用此命令！"))
                    return true
                }
                when (args[0].lowercase()) {
                    "start" -> handleStart(sender)
                    "spawn" -> handleSpawn(sender, args.drop(1).toTypedArray())
                    "doors" -> handleDoors(sender, args.drop(1).toTypedArray())
                    "buttons" -> handleButtons(sender, args.drop(1).toTypedArray())
                    "reload" -> handleReload(sender)
                    "open" -> handleOpen()
                    "close" -> handleClose()
                    "reset" -> handleReset(sender, args.drop(1).toTypedArray())
                    "weapon" -> WeaponCommands.handle(plugin, sender, args.drop(1).toTypedArray())
                }
            }
            "coins" -> CoinCommands.handle(plugin, sender, args.drop(1).toTypedArray())
            "shop" -> {
                if (sender !is Player) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
                    return true
                }
                plugin.shopGUI.open(sender)
            }
            "select", "unselect", "randomgun", "lobby", "transfer" -> {
                if (sender !is Player) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
                    return true
                }
                when (args[0].lowercase()) {
                    "select" -> handleSelect(sender, args.drop(1).toTypedArray())
                    "unselect" -> handleUnselect(sender)
                    "randomgun" -> handleRandomgun(sender)
                    "lobby" -> handleLobby(sender)
                    "transfer" -> handleTransfer(sender, args.drop(1).toTypedArray())
                }
            }
            "door" -> {
                if (sender !is Player) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
                    return true
                }
                handleDoor(sender, args.drop(1).toTypedArray())
            }
            "profile" -> {
                if (sender !is Player) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
                    return true
                }
                val target = if (args.size > 1) Bukkit.getPlayer(args[1]) else sender
                if (target == null) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c玩家不在线！"))
                    return true
                }
                plugin.profileGUI.open(sender, target)
            }
            "quest" -> {
                if (sender !is Player) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
                    return true
                }
                plugin.questGUI.open(sender)
            }
            "title" -> {
                if (sender !is Player) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
                    return true
                }
                if (args.size == 1) {
                    plugin.titleGUI.open(sender)
                } else {
                    val titleName = args.drop(1).joinToString(" ")
                    if (plugin.titleManager.equipTitle(sender, titleName)) {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已装备称号：§e$titleName"))
                    } else {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c你没有解锁这个称号！"))
                    }
                }
            }
            "xp" -> {
                if (!sender.hasPermission("zombie.run.admin")) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c你没有权限使用此命令！"))
                    return true
                }
                if (args.size < 4) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr xp <add|set> <玩家> <数量>"))
                    return true
                }
                val amount = args.getOrNull(3)?.toIntOrNull()
                if (amount == null || amount <= 0) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c数量必须是正整数！"))
                    return true
                }
                val target = Bukkit.getPlayer(args[2])
                if (target == null) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c玩家不在线！"))
                    return true
                }
                when (args[1].lowercase()) {
                    "add" -> {
                        plugin.progressionManager.addXp(target, amount, "管理员操作")
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已给 ${target.name} 增加 $amount XP"))
                    }
                    "set" -> {
                        plugin.progressionManager.setXp(target.uniqueId, amount)
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已将 ${target.name} 的 XP 设置为 $amount"))
                    }
                    else -> sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr xp <add|set> <玩家> <数量>"))
                }
            }
            "level" -> {
                if (!sender.hasPermission("zombie.run.admin")) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c你没有权限使用此命令！"))
                    return true
                }
                if (args.size < 4 || args[1].lowercase() != "set") {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr level set <玩家> <等级>"))
                    return true
                }
                val level = args.getOrNull(3)?.toIntOrNull()
                if (level == null || level < 1 || level > cn.oneachina.zombieRun.manager.ProgressionManager.MAX_LEVEL) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c等级必须在 1-${cn.oneachina.zombieRun.manager.ProgressionManager.MAX_LEVEL} 之间！"))
                    return true
                }
                val target = Bukkit.getPlayer(args[2])
                if (target == null) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c玩家不在线！"))
                    return true
                }
                plugin.progressionManager.setLevel(target.uniqueId, level)
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已将 ${target.name} 的等级设置为 $level"))
            }
            else -> sendHelp(sender)
        }
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a===== 僵尸快跑命令 ====="))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr start - 开始游戏（需要管理员）"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr door <门号> - 触发指定门"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr spawn add <名称> <类型> [门号] [房间号] - 添加重生点"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr spawn remove <名称> - 删除重生点"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr spawn list - 列出重生点"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr doors add <x1> <y1> <z1> <x2> <y2> <z2> [mode] [门号] [delay] [材质] - 添加门"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr doors remove <名称> - 删除门"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr doors list - 列出门"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr buttons add <x> <y> <z> normal <门号> - 添加普通开门按钮"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr buttons add <x> <y> <z> tp <playerX> <playerY> <playerZ> <zombieX> <zombieY> <zombieZ> <门号1> [门号2] [门号3] [门号4] [门号5] - 添加传送按钮（人类和僵尸目标，最多控制5个门）"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr buttons add <x> <y> <z> escape - 添加撤离按钮"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr buttons remove <名称> - 删除按钮"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr buttons list - 列出按钮"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr reload - 重载配置"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr open - 开始游戏（管理员/控制台）"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr close - 结束游戏（管理员/控制台）"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr select <编号> - 选择想要购买的枪械"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr unselect - 取消选择"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr randomgun - 随机获得枪械（仅人类）"))
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a/zr lobby - 返回大厅"))
    }

    private fun handleStart(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
            return
        }
        plugin.gameManager.forceStartGame()
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a游戏开始！"))
    }

    private fun handleDoor(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr door <门号>"))
            return
        }
        if (sender !is Player) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
            return
        }
        val doorNumber = args[0].toIntOrNull()
        if (doorNumber == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c门号必须是整数！"))
            return
        }
        plugin.doorManager.triggerDoor(doorNumber, sender)
    }

    private fun handleSpawn(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr spawn <add|remove|list>"))
            return
        }
        when (args[0].lowercase()) {
            "add" -> handleSpawnAdd(sender, args.drop(1).toTypedArray())
            "remove" -> handleSpawnRemove(sender, args.drop(1).toTypedArray())
            "list" -> handleSpawnList(sender)
            else -> sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c未知子命令，可用: add, remove, list"))
        }
    }

    private fun handleSpawnAdd(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
            return
        }
        if (args.size < 2) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr spawn add <名称> <类型> [门号] [房间号]"))
            return
        }
        val name = args[0]
        val typeStr = args[1].uppercase()
        val type = try {
            Respawn.RespawnType.valueOf(typeStr)
        } catch (_: IllegalArgumentException) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c无效的类型！可用: ${Respawn.RespawnType.entries.joinToString(", ")}"))
            return
        }
        val doorNumber = if (args.size > 2) args[2].toIntOrNull() else null
        val roomNumber = if (args.size > 3) args[3].toIntOrNull() else null

        val respawn = Respawn(
            name = name,
            x = sender.location.blockX,
            y = sender.location.blockY,
            z = sender.location.blockZ,
            yaw = sender.location.yaw.toDouble(),
            pitch = sender.location.pitch.toDouble(),
            type = type,
            doorNumber = doorNumber,
            roomNumber = roomNumber
        )
        plugin.configManager.addRespawn(respawn)
        plugin.respawnManager.addRespawn(respawn)
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a重生点 '$name' 添加成功！"))
    }

    private fun handleSpawnRemove(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr spawn remove <名称>"))
            return
        }
        val name = args[0]
        plugin.configManager.removeRespawn(name)
        plugin.respawnManager.removeRespawn(name)
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a重生点 '$name' 删除成功！"))
    }

    private fun handleSpawnList(sender: CommandSender) {
        val respawns = plugin.respawnManager.getAllRespawns()
        if (respawns.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c当前没有重生点。"))
            return
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a===== 重生点列表 ====="))
        respawns.forEach { sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a- ${it.name} (${it.type})")) }
    }

    private fun handleDoors(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr doors <add|remove|list>"))
            return
        }
        when (args[0].lowercase()) {
            "add" -> handleDoorsAdd(sender, args.drop(1).toTypedArray())
            "remove" -> handleDoorsRemove(sender, args.drop(1).toTypedArray())
            "list" -> handleDoorsList(sender)
            else -> sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c未知子命令，可用: add, remove, list"))
        }
    }

    private fun handleDoorsAdd(sender: CommandSender, args: Array<out String>) {
        if (args.size < 7) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr doors add <x1> <y1> <z1> <x2> <y2> <z2> [mode] [门号] [delay] [材质]"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cmode: normal, player, zombie, start (默认为 normal)"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c材质可填具体材质名（如 STONE）或 auto（自动扫描当前方块）"))
            return
        }
        try {
            val x1 = args[0].toInt()
            val y1 = args[1].toInt()
            val z1 = args[2].toInt()
            val x2 = args[3].toInt()
            val y2 = args[4].toInt()
            val z2 = args[5].toInt()

            val mode = args[6].lowercase()
            val validModes = setOf("normal", "player", "zombie", "start")
            if (mode !in validModes) {
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c模式必须是 normal, player, zombie, start 之一"))
                return
            }

            val doorNumber = if (args.size > 7) args[7].toIntOrNull() ?: 0 else 0
            val delay = if (args.size > 8) args[8].toIntOrNull() ?: 30 else 30
            val materialArg = if (args.size > 9) args[9] else "STONE"

            val useScanData = materialArg.equals("auto", ignoreCase = true)
            val material = if (useScanData) "" else materialArg

            val minX = minOf(x1, x2)
            val minY = minOf(y1, y2)
            val minZ = minOf(z1, z2)
            val maxX = maxOf(x1, x2)
            val maxY = maxOf(y1, y2)
            val maxZ = maxOf(z1, z2)

            val blocks = mutableMapOf<String, String>()
            if (useScanData) {
                val world = if (sender is Player) sender.world else Bukkit.getWorlds().first()
                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        for (z in minZ..maxZ) {
                            val block = world.getBlockAt(x, y, z)
                            blocks["$x,$y,$z"] = block.type.name
                        }
                    }
                }
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已扫描门区域，共记录 ${blocks.size} 个方块。"))
            }

            val doorName = "door_${System.currentTimeMillis()}"

            val door = Door(
                name = doorName,
                minX = minX,
                minY = minY,
                minZ = minZ,
                maxX = maxX,
                maxY = maxY,
                maxZ = maxZ,
                doorNumber = doorNumber,
                delay = delay,
                material = material,
                mode = Door.DoorMode.fromString(mode),
                useScanData = useScanData,
                blocks = blocks
            )
            plugin.configManager.addDoorFull(door)
            plugin.doorManager.addDoor(door)
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a门 '$doorName' 添加成功！模式: $mode"))
            if (useScanData) {
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a使用自动扫描模式，关门时将恢复原始方块。"))
            }
        } catch (_: NumberFormatException) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c坐标必须是整数！"))
        }
    }

    private fun handleDoorsRemove(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr doors remove <名称>"))
            return
        }
        val name = args[0]
        plugin.configManager.removeDoor(name)
        plugin.doorManager.removeDoor(name)
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a门 '$name' 删除成功！"))
    }

    private fun handleDoorsList(sender: CommandSender) {
        val doors = plugin.doorManager.getAllDoors()
        if (doors.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c当前没有门。"))
            return
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a===== 门列表 ====="))
        doors.forEach { sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a- ${it.name} (#${it.doorNumber}, ${it.mode})")) }
    }

    private fun handleReload(sender: CommandSender) {
        plugin.configManager.reloadConfig()
        plugin.doorManager.loadDoors()
        plugin.respawnManager.loadRespawns()
        plugin.buttonManager.loadButtons()
        plugin.startEffectManager.loadEffects()
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a配置重载成功！"))
    }

    private fun handleOpen() {
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
            plugin.gameManager.beginGame()
        } else {
            plugin.logger.warning("游戏已在运行中！")
        }
    }

    private fun handleClose() {
        plugin.gameManager.endGame(GameManager.Team.SPECTATOR)
    }

    private fun handleReset(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr reset <玩家>"))
            return
        }
        val target = Bukkit.getPlayer(args[0])
        val uuid = if (target != null) {
            target.uniqueId
        } else {
            try {
                java.util.UUID.fromString(args[0])
            } catch (_: IllegalArgumentException) {
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c玩家不在线且未提供有效的 UUID！"))
                return
            }
        }
        plugin.progressionManager.resetPlayer(uuid)
        plugin.coinManager.resetCoins(uuid)
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已重置玩家数据：等级、XP、硬币、击杀、感染、任务、解锁均已清空。"))
        if (target != null) {
            target.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c你的数据已被管理员重置。"))
        }
    }

    private fun handleSelect(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
            return
        }
        val weapons = plugin.miscManager.getSelectableWeapons()
        if (weapons.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c当前没有可选枪械。"))
            return
        }
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr select <编号 1-${weapons.size}>"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§7可选枪械:"))
            weapons.forEachIndexed { index, weapon ->
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§7${index + 1}. $weapon"))
            }
            return
        }
        val num = args[0].toIntOrNull()
        if (num == null || num !in 1..weapons.size) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c编号必须是 1-${weapons.size} 的整数！"))
            return
        }
        if (!plugin.miscManager.setSelectedWeapon(sender, num)) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c选择失败，请重试。"))
            return
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已选择枪械 ${weapons[num - 1]}，下次随机时将自动购买。"))
    }

    private fun handleUnselect(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
            return
        }
        plugin.miscManager.clearSelectedWeapon(sender)
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已取消选择，将随机获得枪械。"))
    }

    private fun handleRandomgun(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
            return
        }
        plugin.miscManager.giveRandomGun(sender)
    }

    private fun handleLobby(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
            return
        }
        plugin.miscManager.teleportToLobby(sender)
    }

    private fun handleButtons(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr buttons <add|remove|list>"))
            return
        }
        when (args[0].lowercase()) {
            "add" -> handleButtonsAdd(sender, args.drop(1).toTypedArray())
            "remove" -> handleButtonsRemove(sender, args.drop(1).toTypedArray())
            "list" -> handleButtonsList(sender)
            else -> sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c未知子命令，可用: add, remove, list"))
        }
    }

    private fun handleButtonsAdd(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此命令只能由玩家执行！"))
            return
        }
        if (args.size < 4) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr buttons add <x> <y> <z> <mode> [参数...]"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c模式 normal: /zr buttons add <x> <y> <z> normal <门号>"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c模式 tp: /zr buttons add <x> <y> <z> tp <playerX> <playerY> <playerZ> <zombieX> <zombieY> <zombieZ> <门号> [操控门号1] [操控门号2] [操控门号3] [操控门号4] [操控门号5] (操控门号可选)"))
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c模式 escape: /zr buttons add <x> <y> <z> escape"))
            return
        }
        try {
            val x = args[0].toInt()
            val y = args[1].toInt()
            val z = args[2].toInt()
            val mode = args[3].lowercase()

            val button: Button = when (mode) {
                "normal" -> {
                    if (args.size < 5) {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cnormal模式需要指定门号！"))
                        return
                    }
                    val doorNumber = args[4].toIntOrNull()
                    if (doorNumber == null) {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c门号必须是整数！"))
                        return
                    }
                    val name = "button_${x}_${y}_${z}_normal"
                    Button(name, x, y, z, mode, doorNumber = doorNumber)
                }
                "tp" -> {
                    if (args.size < 11) {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§ctp模式需要指定人类目标坐标、僵尸目标坐标和区域门号！"))
                        return
                    }
                    val playerX = args[4].toIntOrNull()
                    val playerY = args[5].toIntOrNull()
                    val playerZ = args[6].toIntOrNull()
                    val zombieX = args[7].toIntOrNull()
                    val zombieY = args[8].toIntOrNull()
                    val zombieZ = args[9].toIntOrNull()
                    val areaDoorNumber = args[10].toIntOrNull()
                    if (playerX == null || playerY == null || playerZ == null ||
                        zombieX == null || zombieY == null || zombieZ == null ||
                        areaDoorNumber == null) {
                        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c所有目标坐标和门号必须是整数！"))
                        return
                    }
                    
                    val doorNumbers = mutableListOf<Int>()
                    for (i in 11 until minOf(args.size, 16)) {
                        val doorNumber = args[i].toIntOrNull()
                        if (doorNumber != null) {
                            doorNumbers.add(doorNumber)
                        }
                    }
                    
                    val name = "button_${x}_${y}_${z}_tp"
                    Button(
                        name, x, y, z, mode,
                        areaDoorNumber = areaDoorNumber,
                        doorNumbers = if (doorNumbers.isNotEmpty()) doorNumbers else null,
                        playerTargetX = playerX,
                        playerTargetY = playerY,
                        playerTargetZ = playerZ,
                        zombieTargetX = zombieX,
                        zombieTargetY = zombieY,
                        zombieTargetZ = zombieZ
                    )
                }
                "escape" -> {
                    val name = "button_${x}_${y}_${z}_escape"
                    Button(name, x, y, z, mode)
                }
                else -> {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c无效的模式！可用: normal, tp, escape"))
                    return
                }
            }

            plugin.buttonManager.addButton(button)
            plugin.configManager.addButton(button)

            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a按钮添加成功！名称: ${button.name}, 模式: ${button.mode}"))
        } catch (_: NumberFormatException) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c坐标和数字参数必须是整数！"))
        }
    }

    private fun handleButtonsRemove(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr buttons remove <名称>"))
            return
        }
        val name = args[0]
        plugin.buttonManager.removeButton(name)
        plugin.configManager.removeButton(name)
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a按钮 '$name' 删除成功！"))
    }

    private fun handleButtonsList(sender: CommandSender) {
        val buttons = plugin.buttonManager.getAllButtons()
        if (buttons.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c当前没有按钮。"))
            return
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a===== 按钮列表 ====="))
        buttons.forEach {
            val info = when {
                it.isNormal() -> "门号: ${it.doorNumber}"
                it.isTp() -> "人类目标: (${it.playerTargetX},${it.playerTargetY},${it.playerTargetZ}) 僵尸目标: (${it.zombieTargetX},${it.zombieTargetY},${it.zombieTargetZ}) 区域门: ${it.areaDoorNumber} ${if (it.doorNumbers != null) "控制门: ${it.doorNumbers.joinToString(", ")}" else ""}"
                it.isEscape() -> "撤离按钮"
                else -> ""
            }
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a- ${it.name} 模式: ${it.mode} 坐标: ${it.x},${it.y},${it.z} $info"))
        }
    }

    private fun handleTransfer(sender: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr transfer <玩家> <金额>"))
            return
        }
        if (args.size < 2) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c用法: /zr transfer <玩家> <金额>"))
            return
        }
        val target = Bukkit.getPlayer(args[0])
        if (target == null) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c玩家不在线！"))
            return
        }
        val amount = args[1].toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c金额必须是正整数！"))
            return
        }
        val current = plugin.coinManager.getCoins(sender.uniqueId)
        if (current < amount) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c你的硬币不足！"))
            return
        }
        plugin.coinManager.takeCoins(sender.uniqueId, amount)
        plugin.coinManager.addCoins(target.uniqueId, amount)
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a成功转账 $amount 硬币给 ${target.name}"))
        target.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a你收到了来自 ${sender.name} 的 $amount 硬币"))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String>? {
        if (!sender.hasPermission("zombie.run.admin")) return mutableListOf()

        return when (args.size) {
            1 -> {
                listOf("start", "door", "spawn", "doors", "buttons", "reload", "open", "close", "reset", "select", "unselect", "randomgun", "lobby", "profile", "quest", "title", "xp", "level")
                    .filter { it.startsWith(args[0].lowercase()) }
                    .toMutableList()
            }
            2 -> {
                when (args[0].lowercase()) {
                    "door" -> {
                        (1..9).map { it.toString() }.filter { it.startsWith(args[1]) }.toMutableList()
                    }
                    "spawn" -> {
                        listOf("add", "remove", "list").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    "doors" -> {
                        listOf("add", "remove", "list").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    "buttons" -> {
                        listOf("add", "remove", "list").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    "select" -> {
                        val count = plugin.miscManager.getSelectableWeapons().size
                        (1..count).map { it.toString() }.filter { it.startsWith(args[1]) }.toMutableList()
                    }
                    "xp" -> {
                        listOf("add", "set").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    "level" -> {
                        listOf("set").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    else -> mutableListOf()
                }
            }
            else -> {
                when (args[0].lowercase()) {
                    "spawn" -> TabCompleters.spawn(plugin, args)
                    "doors" -> TabCompleters.doors(plugin, args)
                    "buttons" -> TabCompleters.buttons(plugin, args)
                    "xp" -> {
                        if (args.size == 2) listOf("add", "set").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                        else null
                    }
                    else -> mutableListOf()
                }
            }
        }
    }
}
