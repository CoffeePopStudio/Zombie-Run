package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import cn.oneachina.zombieRun.model.Button
import cn.oneachina.zombieRun.model.Door
import cn.oneachina.zombieRun.model.Respawn
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class ZombieRunCommand(private val plugin: ZombieRun) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "start", "spawn", "doors", "buttons", "reload", "open", "close", "reset", "debug", "game" ->
                handleAdminCommand(sender, args)
            "postool" -> handlePostool(sender)
            "coins" -> CoinCommands.handle(plugin, sender, args.drop(1).toTypedArray())
            "shop" -> handleShop(sender)
            "select", "unselect", "randomgun", "lobby", "transfer" ->
                handlePlayerCommand(sender, args)
            "door" -> handleDoorCommand(sender, args)
            "profile" -> handleProfile(sender, args)
            "quest" -> handleQuest(sender)
            "title" -> handleTitle(sender, args)
            "xp" -> handleXp(sender, args)
            "level" -> handleLevel(sender, args)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun handleAdminCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("zombie.run.admin")) {
            sender.sendMessage(Component.text("你没有权限使用此命令！", NamedTextColor.RED))
            return true
        }
        return when (args[0].lowercase()) {
            "start" -> { handleStart(sender, args.drop(1).toTypedArray()); true }
            "spawn" -> { handleSpawn(sender, args.drop(1).toTypedArray()); true }
            "doors" -> { handleDoors(sender, args.drop(1).toTypedArray()); true }
            "buttons" -> { handleButtons(sender, args.drop(1).toTypedArray()); true }
            "reload" -> { handleReload(sender); true }
            "open" -> { handleOpen(sender, args.drop(1).toTypedArray()); true }
            "close" -> { handleClose(sender, args.drop(1).toTypedArray()); true }
            "reset" -> { handleReset(sender, args.drop(1).toTypedArray()); true }
            "debug" -> { handleDebug(sender); true }
            "game" -> { handleGame(sender, args.drop(1).toTypedArray()); true }
            "migrate" -> { handleMigrate(sender); true }
            else -> { sendHelp(sender); true }
        }
    }

    private fun handlePlayerCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return true
        }
        return when (args[0].lowercase()) {
            "select" -> { handleSelect(sender, args.drop(1).toTypedArray()); true }
            "unselect" -> { handleUnselect(sender); true }
            "randomgun" -> { handleRandomgun(sender); true }
            "lobby" -> { handleLobby(sender); true }
            "transfer" -> { handleTransfer(sender, args.drop(1).toTypedArray()); true }
            else -> { sendHelp(sender); true }
        }
    }

    private fun handleDoorCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("zombie.run.admin")) {
            sender.sendMessage(Component.text("你没有权限使用此命令！", NamedTextColor.RED))
            return true
        }
        val subArgs = args.drop(1).toTypedArray()
        if (subArgs.isNotEmpty() && subArgs[0].lowercase() == "behavior") {
            DoorBehaviorCommands.handle(plugin, sender, subArgs.drop(1).toTypedArray(), plugin.gameListener)
        } else {
            if (sender !is Player) {
                sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
                return true
            }
            handleDoor(sender, subArgs)
        }
        return true
    }

    private fun handlePostool(sender: CommandSender) {
        if (!sender.hasPermission("zombie.run.admin")) {
            sender.sendMessage(Component.text("你没有权限使用此命令！", NamedTextColor.RED))
            return
        }
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return
        }
        if (plugin.isPostoolActive(sender)) {
            plugin.deactivatePostool(sender)
            sender.sendMessage(Component.text("postool 已关闭", NamedTextColor.YELLOW))
        } else {
            plugin.activatePostool(sender)
            sender.inventory.addItem(org.bukkit.inventory.ItemStack(org.bukkit.Material.STICK))
            sender.sendMessage(Component.text()
                .append(Component.text("已给你一根选区棒！", NamedTextColor.GREEN))
                .append(Component.text("左键方块=pos1, 右键方块=pos2", NamedTextColor.YELLOW))
                .build())
            sender.sendMessage(Component.text("再输入一次 /zr postool 可关闭", NamedTextColor.YELLOW))
        }
    }

    private fun handleShop(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return true
        }
        plugin.shopGUI.open(sender)
        return true
    }

    private fun handleProfile(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return true
        }
        val target = if (args.size > 1) Bukkit.getPlayer(args[1]) else sender
        if (target == null) {
            sender.sendMessage(Component.text("玩家不在线！", NamedTextColor.RED))
            return true
        }
        plugin.profileGUI.open(sender, target)
        return true
    }

    private fun handleQuest(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return true
        }
        plugin.questGUI.open(sender)
        return true
    }

    private fun handleTitle(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return true
        }
        if (args.size == 1) {
            plugin.titleGUI.open(sender)
        } else {
            val titleName = args.drop(1).joinToString(" ")
            if (plugin.titleManager.equipTitle(sender, titleName)) {
                sender.sendMessage(Component.text()
                    .append(Component.text("已装备称号：", NamedTextColor.GREEN))
                    .append(Component.text(titleName, NamedTextColor.YELLOW))
                    .build())
            } else {
                sender.sendMessage(Component.text("你没有解锁这个称号！", NamedTextColor.RED))
            }
        }
        return true
    }

    private fun handleXp(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("zombie.run.admin")) {
            sender.sendMessage(Component.text("你没有权限使用此命令！", NamedTextColor.RED))
            return true
        }
        if (args.size < 4) {
            sender.sendMessage(Component.text("用法: /zr xp <add|set> <玩家> <数量>", NamedTextColor.RED))
            return true
        }
        val amount = args.getOrNull(3)?.toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage(Component.text("数量必须是正整数！", NamedTextColor.RED))
            return true
        }
        val target = Bukkit.getPlayer(args[2])
        if (target == null) {
            sender.sendMessage(Component.text("玩家不在线！", NamedTextColor.RED))
            return true
        }
        when (args[1].lowercase()) {
            "add" -> {
                plugin.progressionManager.addXp(target, amount, "管理员操作")
                sender.sendMessage(Component.text("已给 ${target.name} 增加 $amount XP", NamedTextColor.GREEN))
            }
            "set" -> {
                plugin.progressionManager.setXp(target.uniqueId, amount)
                sender.sendMessage(Component.text("已将 ${target.name} 的 XP 设置为 $amount", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("用法: /zr xp <add|set> <玩家> <数量>", NamedTextColor.RED))
        }
        return true
    }

    private fun handleLevel(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("zombie.run.admin")) {
            sender.sendMessage(Component.text("你没有权限使用此命令！", NamedTextColor.RED))
            return true
        }
        if (args.size < 4 || args[1].lowercase() != "set") {
            sender.sendMessage(Component.text("用法: /zr level set <玩家> <等级>", NamedTextColor.RED))
            return true
        }
        val level = args.getOrNull(3)?.toIntOrNull()
        if (level == null || level < 1 || level > cn.oneachina.zombieRun.manager.ProgressionManager.MAX_LEVEL) {
            sender.sendMessage(Component.text("等级必须在 1-${cn.oneachina.zombieRun.manager.ProgressionManager.MAX_LEVEL} 之间！", NamedTextColor.RED))
            return true
        }
        val target = Bukkit.getPlayer(args[2])
        if (target == null) {
            sender.sendMessage(Component.text("玩家不在线！", NamedTextColor.RED))
            return true
        }
        plugin.progressionManager.setLevel(target.uniqueId, level)
        sender.sendMessage(Component.text("已将 ${target.name} 的等级设置为 $level", NamedTextColor.GREEN))
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(Component.text("===== 僵尸快跑命令 =====", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr start - 开始游戏（需要管理员）", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr door <门号> - 触发指定门", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr spawn <wait|player|zombie|alpha|door-player|door-zombie> - 添加重生点", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr spawn remove <名称> - 删除重生点", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr spawn list - 列出重生点", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr doors add <x1> <y1> <z1> <x2> <y2> <z2> <mode> [门号] [delay] - 添加门（自动扫描方块）", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr doors remove <名称> - 删除门", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr doors list - 列出门", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr doors reset <名称> - 重置门为关闭状态", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr buttons add <x> <y> <z> normal <门号> - 添加普通开门按钮", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr buttons add <x> <y> <z> escape - 添加撤离按钮", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr buttons remove <名称> - 删除按钮", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr buttons list - 列出按钮", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr reload - 重载配置", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr open - 开始游戏（管理员/控制台）", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr close - 结束游戏（管理员/控制台）", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr select <编号> - 选择想要购买的枪械", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr unselect - 取消选择", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr randomgun - 随机获得枪械（仅人类）", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr lobby - 返回大厅", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr debug - 切换 Debug 模式（管理员）", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr game list - 查看各世界游戏状态（管理员）", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr migrate - 迁移旧配置格式（管理员）", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("提示: /zr start|open|close 可加 -w <世界名> 指定世界（玩家默认当前世界，控制台默认配置世界）", NamedTextColor.YELLOW))
    }

    private fun handleStart(sender: CommandSender, args: Array<out String>) {
        val world = resolveWorld(sender, args)
        val started = plugin.gameManager.forceStartGame(world, manual = true)
        if (started) {
            sender.sendMessage(Component.text("[$world] 游戏开始！", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("[$world] 无法开始游戏：游戏已在运行/倒计时中或没有玩家在线！", NamedTextColor.RED))
        }
    }

    /** /zr migrate - 迁移旧配置格式（管理员） */
    private fun handleMigrate(sender: CommandSender) {
        val migrated = plugin.configManager.migrateConfigIfNeeded()
        if (migrated) {
            sender.sendMessage(Component.text("配置迁移完成！", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("配置无需迁移或迁移失败（详见控制台日志）。", NamedTextColor.YELLOW))
        }
    }

    /** 解析目标世界：优先 -w <world> 参数，其次玩家所在世界，最后配置默认世界 */
    private fun resolveWorld(sender: CommandSender, args: Array<out String>): String {
        val wIdx = args.indexOf("-w")
        val explicit = if (wIdx >= 0 && wIdx + 1 < args.size) args[wIdx + 1] else null
        return explicit ?: (if (sender is Player) sender.world.name else plugin.configManager.getWorldName())
    }

    /** /zr game list - 查看各世界游戏状态 */
    private fun handleGame(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty() || args[0].lowercase() != "list") {
            sender.sendMessage(Component.text("用法: /zr game list", NamedTextColor.RED))
            return
        }
        val worlds = (plugin.gameManager.getAllGames().map { it.worldName } +
            plugin.doorManager.getAllDoors().map { it.world } +
            plugin.respawnManager.getAllRespawns().map { it.world }).distinct()
        if (worlds.isEmpty()) {
            sender.sendMessage(Component.text("当前没有任何游戏世界。", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("===== 游戏世界列表 =====", NamedTextColor.GREEN))
        worlds.forEach { w ->
            val status = plugin.gameManager.getGameStatus(w)
            val players = plugin.gameManager.getWorldPlayers(w).size
            sender.sendMessage(Component.text("- $w [${status.name}] 玩家: $players", NamedTextColor.GREEN))
        }
    }

    private fun handleDebug(sender: CommandSender) {
        plugin.debugMode = !plugin.debugMode
        val statusText = if (plugin.debugMode) "开启" else "关闭"
        val statusColor = if (plugin.debugMode) NamedTextColor.GREEN else NamedTextColor.RED
        sender.sendMessage(Component.text()
            .append(Component.text("Debug 模式已", NamedTextColor.YELLOW))
            .append(Component.text(statusText, statusColor))
            .append(Component.text("！", NamedTextColor.YELLOW))
            .build())
        plugin.logger.info("Debug 模式已${if (plugin.debugMode) "开启" else "关闭"}（由 ${sender.name} 操作）")
    }

    private fun handleDoor(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr door <门号>", NamedTextColor.RED))
            return
        }
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return
        }
        val doorNumber = args[0].toIntOrNull()
        if (doorNumber == null) {
            sender.sendMessage(Component.text("门号必须是整数！", NamedTextColor.RED))
            return
        }
        plugin.doorManager.triggerDoor(doorNumber, sender, sender.world.name)
    }

    private fun handleSpawn(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr spawn <wait|player|zombie|alpha|door-player|door-zombie|remove|list>", NamedTextColor.RED))
            return
        }
        when (args[0].lowercase()) {
            "wait" -> handleSpawnQuick(sender, Respawn.RespawnType.WAIT, args.drop(1).toTypedArray())
            "player" -> handleSpawnQuick(sender, Respawn.RespawnType.PLAYER, args.drop(1).toTypedArray())
            "zombie" -> handleSpawnQuick(sender, Respawn.RespawnType.ZOMBIE, args.drop(1).toTypedArray())
            "alpha" -> handleSpawnQuick(sender, Respawn.RespawnType.ZOMBIE_MAIN, args.drop(1).toTypedArray())
            "door-player" -> handleSpawnQuick(sender, Respawn.RespawnType.DOOR_PLAYER, args.drop(1).toTypedArray())
            "door-zombie" -> handleSpawnQuick(sender, Respawn.RespawnType.DOOR_ZOMBIE, args.drop(1).toTypedArray())
            "remove" -> handleSpawnRemove(sender, args.drop(1).toTypedArray())
            "list" -> handleSpawnList(sender)
            else -> sender.sendMessage(Component.text("未知子命令，可用: wait, player, zombie, alpha, door-player, door-zombie, remove, list", NamedTextColor.RED))
        }
    }

    private fun handleSpawnQuick(sender: CommandSender, type: Respawn.RespawnType, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return
        }
        val doorNumber = if (args.isNotEmpty()) args[0].toIntOrNull() else null
        if ((type == Respawn.RespawnType.DOOR_PLAYER || type == Respawn.RespawnType.DOOR_ZOMBIE) && doorNumber == null) {
            sender.sendMessage(Component.text("此类型需要指定门号！用法: /zr spawn ${type.name.lowercase().replace("_", "-")} <门号>", NamedTextColor.RED))
            return
        }

        val prefix = type.name.lowercase()
        val existing = plugin.respawnManager.getAllRespawns()
        var idx = 1
        var name: String
        do {
            name = "${prefix}_$idx"
            idx++
        } while (existing.any { it.name.equals(name, ignoreCase = true) })

        val respawn = Respawn(
            name = name,
            x = sender.location.blockX,
            y = sender.location.blockY,
            z = sender.location.blockZ,
            yaw = sender.location.yaw.toDouble(),
            pitch = sender.location.pitch.toDouble(),
            type = type,
            doorNumber = doorNumber,
            roomNumber = null,
            world = sender.world.name
        )
        plugin.configManager.addRespawn(respawn)
        plugin.respawnManager.addRespawn(respawn)
        sender.sendMessage(Component.text("重生点 '$name' (${type.name}) 添加成功！", NamedTextColor.GREEN))
    }

    private fun handleSpawnRemove(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr spawn remove <名称>", NamedTextColor.RED))
            return
        }
        val name = args[0]
        plugin.configManager.removeRespawn(name)
        plugin.respawnManager.removeRespawn(name)
        sender.sendMessage(Component.text("重生点 '$name' 删除成功！", NamedTextColor.GREEN))
    }

    private fun handleSpawnList(sender: CommandSender) {
        val respawns = plugin.respawnManager.getAllRespawns()
        if (respawns.isEmpty()) {
            sender.sendMessage(Component.text("当前没有重生点。", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("===== 重生点列表 =====", NamedTextColor.GREEN))
        respawns.forEach { sender.sendMessage(Component.text("- ${it.name} (${it.type})", NamedTextColor.GREEN)) }
    }

    private fun handleDoors(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr doors <add|edit|remove|list|reset>", NamedTextColor.RED))
            return
        }
        when (args[0].lowercase()) {
            "add" -> handleDoorsAdd(sender, args.drop(1).toTypedArray())
            "edit" -> handleDoorsEdit(sender, args.drop(1).toTypedArray())
            "remove" -> handleDoorsRemove(sender, args.drop(1).toTypedArray())
            "list" -> handleDoorsList(sender)
            "reset" -> handleDoorsReset(sender, args.drop(1).toTypedArray())
            "info" -> handleDoorsInfo(sender, args.drop(1).toTypedArray())
            else -> sender.sendMessage(Component.text("未知子命令，可用: add, edit, remove, list, reset, info", NamedTextColor.RED))
        }
    }

    private fun handleDoorsAdd(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr doors add <mode> [-g <组名>]", NamedTextColor.RED))
            sender.sendMessage(Component.text("mode: normal, player, zombie, start", NamedTextColor.RED))
            sender.sendMessage(Component.text("使用 /zr postool 选区后可直接 /zr doors add normal", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("open-time/close-time 默认 15，用 /zr doors edit 修改", NamedTextColor.YELLOW))
            return
        }

        val mode = args[0].lowercase()
        val validModes = setOf("normal", "player", "zombie", "start")
        if (mode !in validModes) {
            sender.sendMessage(Component.text("模式必须是 normal, player, zombie, start 之一", NamedTextColor.RED))
            return
        }

        val doorMode = Door.DoorMode.fromString(mode)

        // 解析可选参数: [-g 组名]
        var group: String? = null
        var idx = 1
        while (idx < args.size) {
            when {
                args[idx] == "-g" -> {
                    if (idx + 1 < args.size) { group = args[idx + 1]; idx += 2 }
                    else { sender.sendMessage(Component.text("-g 后需要组名！", NamedTextColor.RED)); return }
                }
                else -> { sender.sendMessage(Component.text("未知参数: ${args[idx]}", NamedTextColor.RED)); return }
            }
        }

        // 坐标来源
        val coords: List<Int> = if (sender is Player && plugin.isPostoolActive(sender)) {
            val p1 = plugin.gameListener.getPos1(sender)
            val p2 = plugin.gameListener.getPos2(sender)
            if (p1 == null || p2 == null) {
                sender.sendMessage(Component.text("请先用 postool 选好两个角！左键=pos1, 右键=pos2", NamedTextColor.RED))
                return
            }
            listOf(
                minOf(p1.blockX, p2.blockX), minOf(p1.blockY, p2.blockY), minOf(p1.blockZ, p2.blockZ),
                maxOf(p1.blockX, p2.blockX), maxOf(p1.blockY, p2.blockY), maxOf(p1.blockZ, p2.blockZ)
            )
        } else {
            // 手打坐标
            if (args.size < 7) {
                sender.sendMessage(Component.text("坐标不足，需要 <x1> <y1> <z1> <x2> <y2> <z2> <mode> 或用 postool", NamedTextColor.RED))
                return
            }
            val raw = args.take(6).map { it.toIntOrNull() }
            if (raw.any { it == null }) {
                sender.sendMessage(Component.text("坐标必须是整数！", NamedTextColor.RED))
                return
            }
            val x1 = raw[0]!!; val y1 = raw[1]!!; val z1 = raw[2]!!
            val x2 = raw[3]!!; val y2 = raw[4]!!; val z2 = raw[5]!!
            listOf(
                minOf(x1, x2), minOf(y1, y2), minOf(z1, z2),
                maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2)
            )
        }
        val minX = coords[0]; val minY = coords[1]; val minZ = coords[2]
        val maxX = coords[3]; val maxY = coords[4]; val maxZ = coords[5]

        // 世界
        val worldName = if (sender is Player) sender.world.name else plugin.configManager.getWorldName()

        // 门号（按世界唯一）
        val doorNumber = when {
            doorMode != Door.DoorMode.NORMAL -> 0
            group != null -> {
                val existing = plugin.doorManager.getDoorsInGroup(group).filter { it.world == worldName }
                if (existing.isNotEmpty()) existing.first().doorNumber
                else plugin.doorManager.getNextDoorNumber(worldName)
            }
            else -> plugin.doorManager.getNextDoorNumber(worldName)
        }

        // 扫描方块
        val blocks = mutableMapOf<String, String>()
        val world = plugin.worldService.getWorldOrFirst(worldName)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    blocks["$x,$y,$z"] = world.getBlockAt(x, y, z).type.name
                }
            }
        }
        sender.sendMessage(Component.text("已扫描门区域，共记录 ${blocks.size} 个方块。", NamedTextColor.GREEN))

        val doorName = "door_${System.currentTimeMillis()}"

        val door = Door(
            name = doorName,
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ,
            openTime = 15,
            closeTime = 15,
            doorNumber = doorNumber,
            material = "",
            mode = doorMode,
            useScanData = true,
            blocks = blocks,
            group = group,
            world = worldName
        )
        plugin.configManager.addDoorFull(door)
        plugin.doorManager.addDoor(door)
        val extra = buildString {
            if (group != null) append(" 组=$group")
            append(" 门号=$doorNumber open=${door.openTime}s close=${door.closeTime}s")
        }
        sender.sendMessage(Component.text("门 '$doorName' 添加成功！模式: $mode$extra", NamedTextColor.GREEN))
    }

    private fun handleDoorsEdit(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(Component.text("用法: /zr doors edit <名称> open-time|close-time|door-number|group <值>", NamedTextColor.RED))
            return
        }
        val door = plugin.doorManager.getDoorByName(args[0])
        if (door == null) {
            sender.sendMessage(Component.text("未找到门 '${args[0]}'", NamedTextColor.RED))
            return
        }
        when (args[1].lowercase()) {
            "open-time" -> {
                val v = args[2].toIntOrNull()
                if (v == null || v <= 0) {
                    sender.sendMessage(Component.text("open-time 必须是正整数！", NamedTextColor.RED))
                    return
                }
                val newDoor = door.with(openTime = v)
                plugin.doorManager.removeDoor(door.name)
                plugin.doorManager.addDoor(newDoor)
                plugin.configManager.addDoorFull(newDoor)
                sender.sendMessage(Component.text("${door.name} open-time 已更新为 $v", NamedTextColor.GREEN))
            }
            "close-time" -> {
                val v = args[2].toIntOrNull()
                if (v == null || v <= 0) {
                    sender.sendMessage(Component.text("close-time 必须是正整数！", NamedTextColor.RED))
                    return
                }
                val newDoor = door.with(closeTime = v)
                plugin.doorManager.removeDoor(door.name)
                plugin.doorManager.addDoor(newDoor)
                plugin.configManager.addDoorFull(newDoor)
                sender.sendMessage(Component.text("${door.name} close-time 已更新为 $v", NamedTextColor.GREEN))
            }
            "door-number" -> {
                val v = args[2].toIntOrNull() ?: return
                val newDoor = door.with(doorNumber = v)
                plugin.doorManager.removeDoor(door.name)
                plugin.doorManager.addDoor(newDoor)
                plugin.configManager.addDoorFull(newDoor)
                sender.sendMessage(Component.text("${door.name} 门号已更新为 $v", NamedTextColor.GREEN))
            }
            "group" -> {
                val v = args[2]
                val newDoor = door.with(group = v)
                plugin.doorManager.removeDoor(door.name)
                plugin.doorManager.addDoor(newDoor)
                plugin.configManager.addDoorFull(newDoor)
                sender.sendMessage(Component.text("${door.name} 组已更新为 $v", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("未知字段: ${args[1]}", NamedTextColor.RED))
        }
    }

    private fun handleDoorsInfo(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr doors info <名称>", NamedTextColor.RED))
            return
        }
        val door = plugin.doorManager.getDoorByName(args[0])
        if (door == null) {
            sender.sendMessage(Component.text("未找到门 '${args[0]}'", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("===== ${door.name} =====", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("模式: ${door.mode}  门号: ${door.doorNumber}  组: ${door.group ?: "-"}", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("open: ${door.openTime}s  close: ${door.closeTime}s  坐标: (${door.minX},${door.minY},${door.minZ})-(${door.maxX},${door.maxY},${door.maxZ})", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("scanData: ${if (door.useScanData) "已记录 ${door.blocks.size} 方块" else "未使用"}", NamedTextColor.GREEN))
        val sb = door.specialBehavior
        if (sb != null) {
            sender.sendMessage(Component.text("特殊行为: ${sb.javaClass.simpleName}", NamedTextColor.AQUA))
        }
    }

    private fun handleDoorsRemove(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr doors remove <名称>", NamedTextColor.RED))
            return
        }
        val name = args[0]
        plugin.configManager.removeDoor(name)
        plugin.doorManager.removeDoor(name)
        sender.sendMessage(Component.text("门 '$name' 删除成功！", NamedTextColor.GREEN))
    }

    private fun handleDoorsList(sender: CommandSender) {
        val doors = plugin.doorManager.getAllDoors()
        if (doors.isEmpty()) {
            sender.sendMessage(Component.text("当前没有门。", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("===== 门列表 =====", NamedTextColor.GREEN))
        doors.forEach { sender.sendMessage(Component.text("- ${it.name} (#${it.doorNumber}, ${it.mode})", NamedTextColor.GREEN)) }
    }

    private fun handleDoorsReset(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr doors reset <名称>", NamedTextColor.RED))
            return
        }
        val name = args[0]
        val door = plugin.doorManager.getAllDoors().find { it.name == name }
        if (door == null) {
            sender.sendMessage(Component.text("未找到名为 '$name' 的门", NamedTextColor.RED))
            return
        }
        plugin.doorManager.resetDoor(name)
        sender.sendMessage(Component.text("门 '$name' 已重置为关闭状态！", NamedTextColor.GREEN))
    }

    private fun handleReload(sender: CommandSender) {
        plugin.configManager.reloadConfig()
        plugin.doorManager.loadDoors()
        plugin.respawnManager.loadRespawns()
        plugin.buttonManager.loadButtons()
        plugin.startEffectManager.loadEffects()
        sender.sendMessage(Component.text("配置重载成功！", NamedTextColor.GREEN))
    }

    private fun handleOpen(sender: CommandSender, args: Array<out String>) {
        val world = resolveWorld(sender, args)
        val game = plugin.gameManager.getGame(world)
        when (game.status) {
            GameManager.GameStatus.RUNNING -> {
                plugin.logger.warning("[$world] 游戏已在运行中！")
                sender.sendMessage(Component.text("[$world] 游戏已在运行中！", NamedTextColor.RED))
            }
            GameManager.GameStatus.STARTING -> {
                // 倒计时中：立即开局
                val began = plugin.gameManager.beginGame(game)
                if (began) {
                    sender.sendMessage(Component.text("[$world] 已跳过倒计时立即开局！", NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text("[$world] 无法开局：没有玩家在线！", NamedTextColor.RED))
                }
            }
            else -> {
                // 未开始时：1 秒倒计时立即开局（仍会执行传送/母体选定等开局流程）
                if (plugin.gameManager.forceStartGame(world, countdownSeconds = 1, manual = true)) {
                    sender.sendMessage(Component.text("[$world] 已立即开始（1 秒倒计时）！", NamedTextColor.GREEN))
                } else {
                    plugin.logger.warning("[$world] 无法开始游戏：没有玩家在线")
                    sender.sendMessage(Component.text("[$world] 无法开始游戏：没有玩家在线！", NamedTextColor.RED))
                }
            }
        }
    }

    private fun handleClose(sender: CommandSender, args: Array<out String>) {
        val world = resolveWorld(sender, args)
        val game = plugin.gameManager.getGame(world)
        when (game.status) {
            GameManager.GameStatus.RUNNING -> {
                plugin.gameManager.endGame(game, GameManager.Team.SPECTATOR)
                sender.sendMessage(Component.text("[$world] 游戏已结束！", NamedTextColor.GREEN))
            }
            GameManager.GameStatus.STARTING -> {
                // 倒计时中：取消开局，回到等待
                plugin.gameManager.cancelCountdownTask(game)
                game.manuallyForced = false
                plugin.gameManager.setGameStatus(game, GameManager.GameStatus.WAITING)
                plugin.gameManager.getWorldPlayers(world).forEach {
                    it.sendMessage(Component.text("开局已取消，游戏回到等待", NamedTextColor.RED))
                }
                sender.sendMessage(Component.text("[$world] 已取消开局倒计时！", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("[$world] 当前没有进行中的游戏。", NamedTextColor.YELLOW))
        }
    }

    private fun handleReset(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr reset <玩家>", NamedTextColor.RED))
            return
        }
        val target = Bukkit.getPlayer(args[0])
        val uuid = if (target != null) {
            target.uniqueId
        } else {
            try {
                java.util.UUID.fromString(args[0])
            } catch (_: IllegalArgumentException) {
                sender.sendMessage(Component.text("玩家不在线且未提供有效的 UUID！", NamedTextColor.RED))
                return
            }
        }
        plugin.progressionManager.resetPlayer(uuid)
        plugin.coinManager.resetCoins(uuid)
        sender.sendMessage(Component.text("已重置玩家数据：等级、XP、硬币、击杀、感染、任务、解锁均已清空。", NamedTextColor.GREEN))
        if (target != null) {
            target.sendMessage(Component.text("你的数据已被管理员重置。", NamedTextColor.RED))
        }
    }

    private fun handleSelect(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return
        }
        val weapons = plugin.miscManager.getSelectableWeapons()
        if (weapons.isEmpty()) {
            sender.sendMessage(Component.text("当前没有可选枪械。", NamedTextColor.RED))
            return
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr select <编号 1-${weapons.size}>", NamedTextColor.RED))
            sender.sendMessage(Component.text("可选枪械:", NamedTextColor.GRAY))
            weapons.forEachIndexed { index, weapon ->
                sender.sendMessage(Component.text("${index + 1}. $weapon", NamedTextColor.GRAY))
            }
            return
        }
        val num = args[0].toIntOrNull()
        if (num == null || num !in 1..weapons.size) {
            sender.sendMessage(Component.text("编号必须是 1-${weapons.size} 的整数！", NamedTextColor.RED))
            return
        }
        if (!plugin.miscManager.setSelectedWeapon(sender, num)) {
            sender.sendMessage(Component.text("选择失败，请重试。", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("已选择枪械 ${weapons[num - 1]}，下次随机时将自动购买。", NamedTextColor.GREEN))
    }

    private fun handleUnselect(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return
        }
        plugin.miscManager.clearSelectedWeapon(sender)
        sender.sendMessage(Component.text("已取消选择，将随机获得枪械。", NamedTextColor.GREEN))
    }

    private fun handleRandomgun(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return
        }
        plugin.miscManager.giveRandomGun(sender)
    }

    private fun handleLobby(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return
        }
        plugin.miscManager.teleportToLobby(sender)
    }

    private fun handleButtons(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr buttons <add|remove|list>", NamedTextColor.RED))
            return
        }
        when (args[0].lowercase()) {
            "add" -> handleButtonsAdd(sender, args.drop(1).toTypedArray())
            "remove" -> handleButtonsRemove(sender, args.drop(1).toTypedArray())
            "list" -> handleButtonsList(sender)
            else -> sender.sendMessage(Component.text("未知子命令，可用: add, remove, list", NamedTextColor.RED))
        }
    }

    private fun handleButtonsAdd(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("此命令只能由玩家执行！", NamedTextColor.RED))
            return
        }
        if (args.size < 4) {
            sender.sendMessage(Component.text("用法: /zr buttons add <x> <y> <z> <mode> [参数...]", NamedTextColor.RED))
            sender.sendMessage(Component.text("模式 normal: /zr buttons add <x> <y> <z> normal <门号>  或  <门号1,门号2,...> 多门", NamedTextColor.RED))
            sender.sendMessage(Component.text("模式 escape: /zr buttons add <x> <y> <z> escape", NamedTextColor.RED))
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
                        sender.sendMessage(Component.text("normal模式需要指定门号！", NamedTextColor.RED))
                        return
                    }
                    // 支持逗号分隔的多门号，如 "7,8,9"
                    val parts = args[4].split(",").map { it.trim() }
                    val nums = parts.mapNotNull { it.toIntOrNull() }
                    if (nums.isEmpty()) {
                        sender.sendMessage(Component.text("门号必须是整数！", NamedTextColor.RED))
                        return
                    }
                    val name = "button_${x}_${y}_${z}_normal"
                    Button(name, x, y, z, mode,
                        doorNumber = if (nums.size == 1) nums[0] else null,
                        doorNumbers = if (nums.size > 1) nums else null,
                        world = sender.world.name
                    )
                }
                "escape" -> {
                    val name = "button_${x}_${y}_${z}_escape"
                    Button(name, x, y, z, mode, world = sender.world.name)
                }
                else -> {
                    sender.sendMessage(Component.text("无效的模式！可用: normal, escape", NamedTextColor.RED))
                    return
                }
            }

            plugin.buttonManager.addButton(button)
            plugin.configManager.addButton(button)

            sender.sendMessage(Component.text("按钮添加成功！名称: ${button.name}, 模式: ${button.mode}", NamedTextColor.GREEN))
        } catch (_: NumberFormatException) {
            sender.sendMessage(Component.text("坐标和数字参数必须是整数！", NamedTextColor.RED))
        }
    }

    private fun handleButtonsRemove(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr buttons remove <名称>", NamedTextColor.RED))
            return
        }
        val name = args[0]
        plugin.buttonManager.removeButton(name)
        plugin.configManager.removeButton(name)
        sender.sendMessage(Component.text("按钮 '$name' 删除成功！", NamedTextColor.GREEN))
    }

    private fun handleButtonsList(sender: CommandSender) {
        val buttons = plugin.buttonManager.getAllButtons()
        if (buttons.isEmpty()) {
            sender.sendMessage(Component.text("当前没有按钮。", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("===== 按钮列表 =====", NamedTextColor.GREEN))
        buttons.forEach {
            val info = when {
                it.isNormal() -> {
                    val all = it.getAllDoorNumbers()
                    if (all.size > 1) "门号: ${all.joinToString(", ")}"
                    else "门号: ${it.doorNumber}"
                }
                it.isEscape() -> "撤离按钮"
                else -> ""
            }
            sender.sendMessage(Component.text("- ${it.name} 模式: ${it.mode} 坐标: ${it.x},${it.y},${it.z} $info", NamedTextColor.GREEN))
        }
    }

    private fun handleTransfer(sender: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr transfer <玩家> <金额>", NamedTextColor.RED))
            return
        }
        if (args.size < 2) {
            sender.sendMessage(Component.text("用法: /zr transfer <玩家> <金额>", NamedTextColor.RED))
            return
        }
        val target = Bukkit.getPlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("玩家不在线！", NamedTextColor.RED))
            return
        }
        val amount = args[1].toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage(Component.text("金额必须是正整数！", NamedTextColor.RED))
            return
        }
        val current = plugin.coinManager.getCoins(sender.uniqueId)
        if (current < amount) {
            sender.sendMessage(Component.text("你的硬币不足！", NamedTextColor.RED))
            return
        }
        plugin.coinManager.takeCoins(sender.uniqueId, amount)
        plugin.coinManager.addCoins(target.uniqueId, amount)
        sender.sendMessage(Component.text("成功转账 $amount 硬币给 ${target.name}", NamedTextColor.GREEN))
        target.sendMessage(Component.text("你收到了来自 ${sender.name} 的 $amount 硬币", NamedTextColor.GREEN))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String>? {
        val isAdmin = sender.hasPermission("zombie.run.admin")
        val adminCmds = listOf("start", "spawn", "doors", "buttons", "reload", "open", "close", "reset", "debug", "xp", "level", "game", "migrate")
        val playerCmds = listOf("door", "select", "unselect", "randomgun", "lobby", "profile", "quest", "title", "transfer", "shop", "coins")

        return when (args.size) {
            1 -> {
                val all = playerCmds + if (isAdmin) adminCmds else emptyList()
                all.filter { it.startsWith(args[0].lowercase()) }.toMutableList()
            }
            2 -> {
                when (args[0].lowercase()) {
                    "door" -> {
                        (listOf("behavior") + (1..9).map { it.toString() })
                            .filter { it.startsWith(args[1]) }.toMutableList()
                    }
                    "spawn" -> {
                    if (!isAdmin) return mutableListOf()
                    listOf("wait", "player", "zombie", "alpha", "door-player", "door-zombie", "remove", "list")
                        .filter { it.startsWith(args[1].lowercase()) }
                        .toMutableList()
                }
                    "doors" -> {
                        if (!isAdmin) return mutableListOf()
                        listOf("add", "remove", "list").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    "buttons" -> {
                        if (!isAdmin) return mutableListOf()
                        listOf("add", "remove", "list").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    "game" -> {
                        if (!isAdmin) return mutableListOf()
                        listOf("list").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    "select" -> {
                        val count = plugin.miscManager.getSelectableWeapons().size
                        (1..count).map { it.toString() }.filter { it.startsWith(args[1]) }.toMutableList()
                    }
                    "xp" -> {
                        if (!isAdmin) return mutableListOf()
                        listOf("add", "set").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    "level" -> {
                        if (!isAdmin) return mutableListOf()
                        listOf("set").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                    }
                    "coins" -> {
                        if (!isAdmin) {
                            listOf("top").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                        } else {
                            listOf("add", "remove", "set", "get", "top").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                        }
                    }
                    else -> mutableListOf()
                }
            }
            else -> {
                when (args[0].lowercase()) {
                    "door" -> {
                        if (args.size >= 3 && args[1].lowercase() == "behavior") {
                            TabCompleters.doorBehavior(plugin, args.drop(2).toList())
                        } else mutableListOf()
                    }
                    "spawn" -> {
                        if (!isAdmin) return mutableListOf()
                        TabCompleters.spawn(plugin, args)
                    }
                    "doors" -> {
                        if (!isAdmin) return mutableListOf()
                        TabCompleters.doors(plugin, args, sender)
                    }
                    "buttons" -> {
                        if (!isAdmin) return mutableListOf()
                        TabCompleters.buttons(plugin, args, sender)
                    }
                    "xp" -> {
                        if (!isAdmin) return mutableListOf()
                        if (args.size == 2) listOf("add", "set").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
                        else null
                    }
                    else -> mutableListOf()
                }
            }
        }
    }
}
