package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import cn.oneachina.zombieRun.model.Button
import cn.oneachina.zombieRun.model.Door
import cn.oneachina.zombieRun.model.Respawn
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
            "start", "spawn", "doors", "buttons", "reload", "open", "close" -> {
                if (!sender.hasPermission("zombie.run.admin")) {
                    sender.sendMessage("§c你没有权限使用此命令！")
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
                }
            }
            "select", "unselect", "randomgun", "lobby", "transfer" -> {
                if (sender !is Player) {
                    sender.sendMessage("§c此命令只能由玩家执行！")
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
                    sender.sendMessage("§c此命令只能由玩家执行！")
                    return true
                }
                handleDoor(sender, args.drop(1).toTypedArray())
            }
            else -> sendHelp(sender)
        }
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§a===== 僵尸快跑命令 =====")
        sender.sendMessage("§a/zr start - 开始游戏（需要管理员）")
        sender.sendMessage("§a/zr door <门号> - 触发指定门")
        sender.sendMessage("§a/zr spawn add <名称> <类型> [门号] [房间号] - 添加重生点")
        sender.sendMessage("§a/zr spawn remove <名称> - 删除重生点")
        sender.sendMessage("§a/zr spawn list - 列出重生点")
        sender.sendMessage("§a/zr doors add <x1> <y1> <z1> <x2> <y2> <z2> [mode] [门号] [delay] [材质] - 添加门")
        sender.sendMessage("§a/zr doors remove <名称> - 删除门")
        sender.sendMessage("§a/zr doors list - 列出门")
        sender.sendMessage("§a/zr buttons add <x> <y> <z> normal <门号> - 添加普通开门按钮")
        sender.sendMessage("§a/zr buttons add <x> <y> <z> tp <playerX> <playerY> <playerZ> <zombieX> <zombieY> <zombieZ> <门号1> [门号2] [门号3] [门号4] [门号5] - 添加传送按钮（人类和僵尸目标，最多控制5个门）")
        sender.sendMessage("§a/zr buttons add <x> <y> <z> escape - 添加撤离按钮")
        sender.sendMessage("§a/zr buttons remove <名称> - 删除按钮")
        sender.sendMessage("§a/zr buttons list - 列出按钮")
        sender.sendMessage("§a/zr reload - 重载配置")
        sender.sendMessage("§a/zr open - 开始游戏（管理员/控制台）")
        sender.sendMessage("§a/zr close - 结束游戏（管理员/控制台）")
        sender.sendMessage("§a/zr select <编号> - 选择想要购买的枪械")
        sender.sendMessage("§a/zr unselect - 取消选择")
        sender.sendMessage("§a/zr randomgun - 随机获得枪械（仅人类）")
        sender.sendMessage("§a/zr lobby - 返回大厅")
    }

    private fun handleStart(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§c此命令只能由玩家执行！")
            return
        }
        plugin.gameManager.forceStartGame()
        sender.sendMessage("§a游戏开始！")
    }

    private fun handleDoor(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr door <门号>")
            return
        }
        if (sender !is Player) {
            sender.sendMessage("§c此命令只能由玩家执行！")
            return
        }
        val doorNumber = args[0].toIntOrNull()
        if (doorNumber == null) {
            sender.sendMessage("§c门号必须是整数！")
            return
        }
        plugin.doorManager.triggerDoor(doorNumber, sender)
    }

    private fun handleSpawn(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr spawn <add|remove|list>")
            return
        }
        when (args[0].lowercase()) {
            "add" -> handleSpawnAdd(sender, args.drop(1).toTypedArray())
            "remove" -> handleSpawnRemove(sender, args.drop(1).toTypedArray())
            "list" -> handleSpawnList(sender)
            else -> sender.sendMessage("§c未知子命令，可用: add, remove, list")
        }
    }

    private fun handleSpawnAdd(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage("§c此命令只能由玩家执行！")
            return
        }
        if (args.size < 2) {
            sender.sendMessage("§c用法: /zr spawn add <名称> <类型> [门号] [房间号]")
            return
        }
        val name = args[0]
        val typeStr = args[1].uppercase()
        val type = try {
            Respawn.RespawnType.valueOf(typeStr)
        } catch (_: IllegalArgumentException) {
            sender.sendMessage("§c无效的类型！可用: ${Respawn.RespawnType.entries.joinToString(", ")}")
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
        sender.sendMessage("§a重生点 '$name' 添加成功！")
    }

    private fun handleSpawnRemove(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr spawn remove <名称>")
            return
        }
        val name = args[0]
        plugin.configManager.removeRespawn(name)
        plugin.respawnManager.removeRespawn(name)
        sender.sendMessage("§a重生点 '$name' 删除成功！")
    }

    private fun handleSpawnList(sender: CommandSender) {
        val respawns = plugin.respawnManager.getAllRespawns()
        if (respawns.isEmpty()) {
            sender.sendMessage("§c当前没有重生点。")
            return
        }
        sender.sendMessage("§a===== 重生点列表 =====")
        respawns.forEach { sender.sendMessage("§a- ${it.name} (${it.type})") }
    }

    private fun handleDoors(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr doors <add|remove|list>")
            return
        }
        when (args[0].lowercase()) {
            "add" -> handleDoorsAdd(sender, args.drop(1).toTypedArray())
            "remove" -> handleDoorsRemove(sender, args.drop(1).toTypedArray())
            "list" -> handleDoorsList(sender)
            else -> sender.sendMessage("§c未知子命令，可用: add, remove, list")
        }
    }

    private fun handleDoorsAdd(sender: CommandSender, args: Array<out String>) {
        if (args.size < 7) {
            sender.sendMessage("§c用法: /zr doors add <x1> <y1> <z1> <x2> <y2> <z2> [mode] [门号] [delay] [材质]")
            sender.sendMessage("§cmode: normal, player, zombie, start (默认为 normal)")
            sender.sendMessage("§c材质可填具体材质名（如 STONE）或 auto（自动扫描当前方块）")
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
                sender.sendMessage("§c模式必须是 normal, player, zombie, start 之一")
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
                sender.sendMessage("§a已扫描门区域，共记录 ${blocks.size} 个方块。")
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
            sender.sendMessage("§a门 '$doorName' 添加成功！模式: $mode")
            if (useScanData) {
                sender.sendMessage("§a使用自动扫描模式，关门时将恢复原始方块。")
            }
        } catch (_: NumberFormatException) {
            sender.sendMessage("§c坐标必须是整数！")
        }
    }

    private fun handleDoorsRemove(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr doors remove <名称>")
            return
        }
        val name = args[0]
        plugin.configManager.removeDoor(name)
        plugin.doorManager.removeDoor(name)
        sender.sendMessage("§a门 '$name' 删除成功！")
    }

    private fun handleDoorsList(sender: CommandSender) {
        val doors = plugin.doorManager.getAllDoors()
        if (doors.isEmpty()) {
            sender.sendMessage("§c当前没有门。")
            return
        }
        sender.sendMessage("§a===== 门列表 =====")
        doors.forEach { sender.sendMessage("§a- ${it.name} (#${it.doorNumber}, ${it.mode})") }
    }

    private fun handleReload(sender: CommandSender) {
        plugin.configManager.reloadConfig()
        plugin.doorManager.loadDoors()
        plugin.respawnManager.loadRespawns()
        plugin.buttonManager.loadButtons()
        plugin.startEffectManager.loadEffects()
        sender.sendMessage("§a配置重载成功！")
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

    private fun handleSelect(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage("§c此命令只能由玩家执行！")
            return
        }
        val weapons = plugin.miscManager.getSelectableWeapons()
        if (weapons.isEmpty()) {
            sender.sendMessage("§c当前没有可选枪械。")
            return
        }
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr select <编号 1-${weapons.size}>")
            sender.sendMessage("§7可选枪械:")
            weapons.forEachIndexed { index, weapon ->
                sender.sendMessage("§7${index + 1}. $weapon")
            }
            return
        }
        val num = args[0].toIntOrNull()
        if (num == null || num !in 1..weapons.size) {
            sender.sendMessage("§c编号必须是 1-${weapons.size} 的整数！")
            return
        }
        if (!plugin.miscManager.setSelectedWeapon(sender, num)) {
            sender.sendMessage("§c选择失败，请重试。")
            return
        }
        sender.sendMessage("§a已选择枪械 ${weapons[num - 1]}，下次随机时将自动购买。")
    }

    private fun handleUnselect(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§c此命令只能由玩家执行！")
            return
        }
        plugin.miscManager.clearSelectedWeapon(sender)
        sender.sendMessage("§a已取消选择，将随机获得枪械。")
    }

    private fun handleRandomgun(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§c此命令只能由玩家执行！")
            return
        }
        plugin.miscManager.giveRandomGun(sender)
    }

    private fun handleLobby(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§c此命令只能由玩家执行！")
            return
        }
        plugin.miscManager.teleportToLobby(sender)
    }

    private fun handleButtons(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr buttons <add|remove|list>")
            return
        }
        when (args[0].lowercase()) {
            "add" -> handleButtonsAdd(sender, args.drop(1).toTypedArray())
            "remove" -> handleButtonsRemove(sender, args.drop(1).toTypedArray())
            "list" -> handleButtonsList(sender)
            else -> sender.sendMessage("§c未知子命令，可用: add, remove, list")
        }
    }

    private fun handleButtonsAdd(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage("§c此命令只能由玩家执行！")
            return
        }
        if (args.size < 4) {
            sender.sendMessage("§c用法: /zr buttons add <x> <y> <z> <mode> [参数...]")
            sender.sendMessage("§c模式 normal: /zr buttons add <x> <y> <z> normal <门号>")
            sender.sendMessage("§c模式 tp: /zr buttons add <x> <y> <z> tp <playerX> <playerY> <playerZ> <zombieX> <zombieY> <zombieZ> <门号> [操控门号1] [操控门号2] [操控门号3] [操控门号4] [操控门号5] (操控门号可选)")
            sender.sendMessage("§c模式 escape: /zr buttons add <x> <y> <z> escape")
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
                        sender.sendMessage("§cnormal模式需要指定门号！")
                        return
                    }
                    val doorNumber = args[4].toIntOrNull()
                    if (doorNumber == null) {
                        sender.sendMessage("§c门号必须是整数！")
                        return
                    }
                    val name = "button_${x}_${y}_${z}_normal"
                    Button(name, x, y, z, mode, doorNumber = doorNumber)
                }
                "tp" -> {
                    if (args.size < 11) {
                        sender.sendMessage("§ctp模式需要指定人类目标坐标、僵尸目标坐标和区域门号！")
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
                        sender.sendMessage("§c所有目标坐标和门号必须是整数！")
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
                    sender.sendMessage("§c无效的模式！可用: normal, tp, escape")
                    return
                }
            }

            plugin.buttonManager.addButton(button)
            plugin.configManager.addButton(button)

            sender.sendMessage("§a按钮添加成功！名称: ${button.name}, 模式: ${button.mode}")
        } catch (_: NumberFormatException) {
            sender.sendMessage("§c坐标和数字参数必须是整数！")
        }
    }

    private fun handleButtonsRemove(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr buttons remove <名称>")
            return
        }
        val name = args[0]
        plugin.buttonManager.removeButton(name)
        plugin.configManager.removeButton(name)
        sender.sendMessage("§a按钮 '$name' 删除成功！")
    }

    private fun handleButtonsList(sender: CommandSender) {
        val buttons = plugin.buttonManager.getAllButtons()
        if (buttons.isEmpty()) {
            sender.sendMessage("§c当前没有按钮。")
            return
        }
        sender.sendMessage("§a===== 按钮列表 =====")
        buttons.forEach {
            val info = when {
                it.isNormal() -> "门号: ${it.doorNumber}"
                it.isTp() -> "人类目标: (${it.playerTargetX},${it.playerTargetY},${it.playerTargetZ}) 僵尸目标: (${it.zombieTargetX},${it.zombieTargetY},${it.zombieTargetZ}) 区域门: ${it.areaDoorNumber} ${if (it.doorNumbers != null) "控制门: ${it.doorNumbers.joinToString(", ")}" else ""}"
                it.isEscape() -> "撤离按钮"
                else -> ""
            }
            sender.sendMessage("§a- ${it.name} 模式: ${it.mode} 坐标: ${it.x},${it.y},${it.z} $info")
        }
    }

    private fun handleTransfer(sender: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /zr transfer <玩家> <金额>")
            return
        }
        if (args.size < 2) {
            sender.sendMessage("§c用法: /zr transfer <玩家> <金额>")
            return
        }
        val target = Bukkit.getPlayer(args[0])
        if (target == null) {
            sender.sendMessage("§c玩家不在线！")
            return
        }
        val amount = args[1].toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage("§c金额必须是正整数！")
            return
        }
        val current = plugin.coinManager.getCoins(sender.uniqueId)
        if (current < amount) {
            sender.sendMessage("§c你的硬币不足！")
            return
        }
        plugin.coinManager.takeCoins(sender.uniqueId, amount)
        plugin.coinManager.addCoins(target.uniqueId, amount)
        sender.sendMessage("§a成功转账 $amount 硬币给 ${target.name}")
        target.sendMessage("§a你收到了来自 ${sender.name} 的 $amount 硬币")
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
                listOf("start", "door", "spawn", "doors", "buttons", "reload", "open", "close", "select", "unselect", "randomgun", "lobby")
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
                    else -> mutableListOf()
                }
            }
            else -> {
                when (args[0].lowercase()) {
                    "spawn" -> handleSpawnTabComplete(args)
                    "doors" -> handleDoorsTabComplete(args)
                    "buttons" -> handleButtonsTabComplete(args)
                    else -> mutableListOf()
                }
            }
        }
    }

    private fun handleSpawnTabComplete(args: Array<out String>): MutableList<String> {
        if (args.size < 2) return mutableListOf()
        return when (args[1].lowercase()) {
            "add" -> {
                when (args.size) {
                    3 -> mutableListOf()
                    4 -> {
                        Respawn.RespawnType.entries.map { it.name.lowercase() }
                            .filter { it.startsWith(args[3].lowercase()) }
                            .toMutableList()
                    }
                    5 -> mutableListOf("~")
                    6 -> mutableListOf("~")
                    else -> mutableListOf()
                }
            }
            "remove" -> {
                if (args.size == 3) {
                    plugin.respawnManager.getAllRespawns().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                        .toMutableList()
                } else mutableListOf()
            }
            "list" -> mutableListOf()
            else -> mutableListOf()
        }
    }

    private fun handleDoorsTabComplete(args: Array<out String>): MutableList<String> {
        if (args.size < 2) return mutableListOf()
        return when (args[1].lowercase()) {
            "add" -> {
                when (args.size) {
                    in 3..8 -> mutableListOf("~")
                    9 -> {
                        listOf("normal", "player", "zombie", "start")
                            .filter { it.startsWith(args[7].lowercase()) }
                            .toMutableList()
                    }
                    10 -> {
                        (0..9).map { it.toString() }
                            .filter { it.startsWith(args[8]) }
                            .toMutableList()
                    }
                    11 -> {
                        listOf("30", "60", "90", "120")
                            .filter { it.startsWith(args[9]) }
                            .toMutableList()
                    }
                    12 -> {
                        listOf("STONE", "IRON_BLOCK", "OBSERVER", "auto")
                            .filter { it.startsWith(args[10].uppercase()) }
                            .toMutableList()
                    }
                    else -> mutableListOf()
                }
            }
            "remove" -> {
                if (args.size == 3) {
                    plugin.doorManager.getAllDoors().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                        .toMutableList()
                } else mutableListOf()
            }
            "list" -> mutableListOf()
            else -> mutableListOf()
        }
    }

    private fun handleButtonsTabComplete(args: Array<out String>): MutableList<String> {
        if (args.size < 2) return mutableListOf()
        return when (args[1].lowercase()) {
            "add" -> {
                when (args.size) {
                    3, 4 -> mutableListOf("~")
                    5 -> {
                        listOf("normal", "tp", "escape")
                            .filter { it.startsWith(args[4].lowercase()) }
                            .toMutableList()
                    }

                    6 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "normal" -> listOf("<门号>").filter { it.startsWith(args[5]) }
                            "tp" -> mutableListOf("~")
                            "escape" -> mutableListOf()
                            else -> mutableListOf()
                        }
                    }

                    7 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    8 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    9 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    10 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    11 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    else -> mutableListOf()
                }
            }

            "remove" -> {
                if (args.size == 3) {
                    plugin.buttonManager.getAllButtons().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                        .toMutableList()
                } else mutableListOf()
            }

            "list" -> mutableListOf()
             else -> mutableListOf()
        } as MutableList<String>
    }
}
