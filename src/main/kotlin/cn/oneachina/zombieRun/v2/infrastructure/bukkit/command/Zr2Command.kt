package cn.oneachina.zombierun.v2.infrastructure.bukkit.command

import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonMode
import cn.oneachina.zombierun.v2.domain.arena.RespawnDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnType
import cn.oneachina.zombierun.v2.domain.weapon.WeaponCategory
import cn.oneachina.zombierun.v2.domain.weapon.WeaponDefinition
import cn.oneachina.zombierun.v2.domain.game.FinishType
import cn.oneachina.zombierun.v2.domain.game.MapFlowDefinition
import cn.oneachina.zombierun.v2.domain.game.MapFlowFinish
import cn.oneachina.zombierun.v2.domain.game.MapFlowStage
import cn.oneachina.zombierun.v2.plugin.V2CompositionRoot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class Zr2Command(
    private val root: V2CompositionRoot,
    private val defaultWorld: String,
    private val plugin: org.bukkit.plugin.java.JavaPlugin,
) : CommandExecutor, TabCompleter, org.bukkit.event.Listener {

    /** postool 已激活的玩家（/zr2 postool 切换）。 */
    private val postoolUsers: MutableSet<java.util.UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** postool 选区：左键=pos1、右键=pos2（手持木棍且已激活）。 */
    private val pos1 = java.util.concurrent.ConcurrentHashMap<java.util.UUID, org.bukkit.Location>()
    private val pos2 = java.util.concurrent.ConcurrentHashMap<java.util.UUID, org.bukkit.Location>()

    @org.bukkit.event.EventHandler
    fun onPostoolInteract(event: org.bukkit.event.player.PlayerInteractEvent) {
        val item = event.item ?: return
        if (item.type != org.bukkit.Material.STICK) return
        if (event.player.uniqueId !in postoolUsers) return
        val block = event.clickedBlock ?: return
        when (event.action) {
            org.bukkit.event.block.Action.LEFT_CLICK_BLOCK -> {
                pos1[event.player.uniqueId] = block.location
                event.isCancelled = true
                event.player.sendMessage(Component.text("pos1 已设为 (${block.x}, ${block.y}, ${block.z})", NamedTextColor.YELLOW))
            }
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK -> {
                pos2[event.player.uniqueId] = block.location
                event.isCancelled = true
                event.player.sendMessage(Component.text("pos2 已设为 (${block.x}, ${block.y}, ${block.z})", NamedTextColor.YELLOW))
            }
            else -> Unit
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        try {
            return handleCommand(sender, args)
        } catch (e: Exception) {
            root.logger.severe("command /$label ${args.joinToString(" ")} error: ${e.message}")
            sender.sendMessage(Component.text("命令执行出错：${e.message}", NamedTextColor.RED))
            return true
        }
    }

    private fun handleCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender is Player && !sender.hasPermission("zombie.run.v2.admin") && !sender.hasPermission("zombie.run.v2.player")) {
            sender.sendMessage(Component.text("你没有权限使用 ZombieRun v2 命令", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            help(sender)
            return true
        }
        val translated = translateV1Command(sender, args)
        if (translated != null) {
            return handleCommand(sender, translated)
        }
        when (args[0].lowercase()) {
            "help" -> help(sender)
            "version" -> version(sender)
            "reload" -> reload(sender)
            "arena" -> handleArena(sender, args.drop(1))
            "door" -> handleDoor(sender, args.drop(1))
            "button" -> handleButton(sender, args.drop(1))
            "respawn" -> handleRespawn(sender, args.drop(1))
            "game" -> handleGame(sender, args.drop(1))
            "weapon" -> handleWeapon(sender, args.drop(1))
            "profile" -> handleProfile(sender, args.drop(1))
            "coins" -> handleCoins(sender, args.drop(1))
            "xp" -> handleXp(sender, args.drop(1))
            "title" -> handleTitle(sender, args.drop(1))
            "level" -> handleLevel(sender, args.drop(1))
            "reset" -> handleReset(sender, args.drop(1))
            "menu" -> handleMenu(sender, args.drop(1))
            "task" -> handleTask(sender, args.drop(1))
            "mapflow" -> handleMapFlow(sender, args.drop(1))
            "v1" -> handleV1(sender, args.drop(1))
            "postool" -> handlePostool(sender)
            "lobby" -> handleLobby(sender)
            "debug" -> handleDebug(sender)
            else -> {
                sender.sendMessage(Component.text("未知子命令：${args[0]}，输入 /zr2 help 查看帮助", NamedTextColor.RED))
            }
        }
        return true
    }

    /** v1 命令 → v2 命令兼容映射；返回 null 表示本身就是 v2 命令。 */
    private fun translateV1Command(sender: CommandSender, args: Array<out String>): Array<out String>? {
        val head = args[0].lowercase()
        val rest = args.drop(1)
        fun playerWorld(): String = (sender as? Player)?.world?.name ?: defaultWorld
        fun firstArena(): String? = root.arenaRepository.byWorld(playerWorld()).firstOrNull()?.name
            ?: root.arenaRepository.all().firstOrNull()?.name
        return when (head) {
            "doors" -> arrayOf("door") + rest
            "buttons" -> arrayOf("button") + rest
            "spawn" -> {
                val sub = rest.firstOrNull()?.lowercase()
                when (sub) {
                    "list" -> arrayOf("respawn", "list") + rest.drop(1)
                    "remove" -> arrayOf("respawn", "remove") + rest.drop(1)
                    "wait", "player", "zombie", "alpha", "door-player", "door-zombie" -> {
                        val type = when (sub) {
                            "wait" -> "WAIT"
                            "player" -> "PLAYER"
                            "zombie" -> "ZOMBIE"
                            "alpha" -> "ZOMBIE_MAIN"
                            "door-player" -> "DOOR_PLAYER"
                            "door-zombie" -> "DOOR_ZOMBIE"
                            else -> "WAIT"
                        }
                        val player = sender as? Player
                        val arena = firstArena() ?: return arrayOf("respawn", "list")
                        if (player == null) return arrayOf("respawn", "add", "--arena", arena, type, "0", "0", "0")
                        val doorNumber = rest.getOrNull(1) ?: "0"
                        arrayOf("respawn", "add", "--arena", arena, type, player.location.blockX.toString(), player.location.blockY.toString(), player.location.blockZ.toString(), doorNumber)
                    }
                    else -> arrayOf("respawn") + rest
                }
            }
            "start" -> arrayOf("game", "start") + if (rest.isEmpty()) listOf(playerWorld()) else rest
            "shop" -> arrayOf("menu", "shop")
            "quest" -> arrayOf("task", "list")
            "randomgun" -> arrayOf("weapon", "random")
            "select" -> arrayOf("weapon", "select") + rest
            "unselect" -> arrayOf("weapon", "unselect")
            "transfer" -> arrayOf("coins", "transfer") + rest
            "migrate" -> arrayOf("v1", "migrate")
            "open" -> arrayOf("door", "trigger") + rest
            "close" -> arrayOf("door", "trigger") + rest
            else -> null
        }
    }

    // ---------- 基础 ----------

    private fun version(sender: CommandSender) {
        sender.sendMessage(Component.text("ZombieRun v2", NamedTextColor.GREEN))
    }

    private fun reload(sender: CommandSender) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        sender.sendMessage(Component.text(root.reload(), NamedTextColor.GREEN))
    }

    private fun help(sender: CommandSender) {
        sender.sendMessage(Component.text("===== ZombieRun v2 =====", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr2 version", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 arena list | info <名称> | create <名称> [世界] | remove <名称>", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 door list [世界] | info <id> | test <id> | trigger <门号>", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 door add [--arena <名称>] [坐标6个] <axis> <front> [--number N] [--group G] [--open N] [--close N]（省略 --arena 自动使用当前世界；/zr2 postool 选区后可省略坐标）", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 postool - 切换选区工具（木棍左键=pos1 右键=pos2）", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 lobby - 返回当前世界等待大厅", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 debug - 切换 debug 日志（管理员）", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 button add [--arena <名称>] <x> <y> <z> normal <门号>（省略 --arena 自动使用当前世界）", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 respawn add [--arena <名称>] <type> <x> <y> <z> [door-number] [yaw] [pitch]（省略 --arena 自动使用当前世界）", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 game list | status <世界> | start <世界> | end <世界> <human|zombie> | reset <世界>", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 weapon list | info <id> | add <id> <type> <category> <price> [name] | remove <id> | give <id> | random [category] | select <id> | unselect", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 profile [玩家] | coins add|give|remove|set|get|spend|transfer|top | xp add|set | level set | reset <玩家> | title set|clear", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 menu profile|shop|tasks|titles", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 task list|claim <任务id>", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 mapflow list|info|init|set|stage|finish|remove", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 v1 migrate", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 reload", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("v1 兼容：/zr doors|buttons|spawn|start|shop|quest|randomgun|select|unselect|transfer|migrate", NamedTextColor.GRAY))
    }

    // ---------- arena ----------

    private fun handleArena(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr2 arena list|info|create|remove", NamedTextColor.RED))
            return
        }
        when (args[0].lowercase()) {
            "list" -> {
                val arenas = root.arenaRepository.all()
                if (arenas.isEmpty()) {
                    sender.sendMessage(Component.text("还没有 arena", NamedTextColor.YELLOW))
                } else {
                    arenas.forEach {
                        sender.sendMessage(Component.text("- ${it.name}  world=${it.world}  doors=${it.doors.size} buttons=${it.buttons.size}", NamedTextColor.GREEN))
                    }
                }
            }
            "info" -> {
                val arena = root.arenaRepository.byName(args.getOrNull(1) ?: "")
                if (arena == null) {
                    sender.sendMessage(Component.text("arena 不存在：${args.getOrNull(1)}", NamedTextColor.RED))
                    return
                }
                sender.sendMessage(Component.text("===== ${arena.name} =====", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("world: ${arena.world}", NamedTextColor.GREEN))
                arena.doors.forEach { door ->
                    val p = door.portal
                    sender.sendMessage(Component.text("- door ${door.id}: #${door.number} axis=${p.axis.name.lowercase()} front=${p.front.name.lowercase()} plane=${p.planeCoordinate} transverse=[${p.transverseMin},${p.transverseMax}] y=[${p.yMin},${p.yMax}]", NamedTextColor.AQUA))
                }
                arena.respawns.forEach {
                    sender.sendMessage(Component.text("- respawn ${it.id}: ${it.type.name} (${it.x}, ${it.y}, ${it.z}) door=${it.doorNumber}", NamedTextColor.AQUA))
                }
            }
            "create" -> createArena(sender, args.drop(1))
            "remove" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) {
                    noPermission(sender)
                    return
                }
                val name = args.getOrNull(1)
                if (name == null) {
                    sender.sendMessage(Component.text("用法: /zr2 arena remove <名称>", NamedTextColor.RED))
                    return
                }
                root.arenaRepository.remove(name)
                sender.sendMessage(Component.text("arena '$name' 已删除", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("未知子命令", NamedTextColor.RED))
        }
    }

    private fun createArena(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        val name = args.firstOrNull()
        if (name == null) {
            sender.sendMessage(Component.text("用法: /zr2 arena create <名称> [世界]", NamedTextColor.RED))
            return
        }
        if (root.arenaRepository.byName(name) != null) {
            sender.sendMessage(Component.text("arena '$name' 已存在", NamedTextColor.RED))
            return
        }
        val world = args.getOrNull(1) ?: (sender as? Player)?.world?.name ?: defaultWorld
        root.arenaRepository.save(ArenaDefinition(name, world))
        sender.sendMessage(Component.text("arena '$name' (world=$world) 已创建", NamedTextColor.GREEN))
    }

    // ---------- door ----------

    private fun handleDoor(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr2 door list|info|test|trigger|add", NamedTextColor.RED))
            return
        }
        when (args[0].lowercase()) {
            "list" -> {
                val world = args.getOrNull(1) ?: (sender as? Player)?.world?.name ?: defaultWorld
                val doors = root.doorService.doorsInWorld(world)
                if (doors.isEmpty()) {
                    sender.sendMessage(Component.text("世界 $world 还没有门", NamedTextColor.YELLOW))
                } else {
                    doors.forEach {
                        sender.sendMessage(Component.text("- ${it.id}  #${it.number}  mode=${it.mode.name.lowercase()}  group=${it.group ?: "-"}", NamedTextColor.GREEN))
                    }
                }
            }
            "info", "test" -> {
                val id = args.getOrNull(1)
                val door = if (id != null) root.doorService.doorById(id) else null
                if (door == null) {
                    sender.sendMessage(Component.text("门不存在：$id", NamedTextColor.RED))
                    return
                }
                val p = door.portal
                sender.sendMessage(Component.text("===== door ${door.id} =====", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("number=${door.number} world=${door.world} mode=${door.mode.name.lowercase()}", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("axis=${p.axis.name.lowercase()} front=${p.front.name.lowercase()} plane=${p.planeCoordinate}", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("transverse=[${p.transverseMin},${p.transverseMax}] y=[${p.yMin},${p.yMax}]", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("region=(${door.region.minX},${door.region.minY},${door.region.minZ})-(${door.region.maxX},${door.region.maxY},${door.region.maxZ})", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("open=${door.openSeconds}s close=${door.closeSeconds}s snapshot=${door.snapshotId ?: "-"}", NamedTextColor.GREEN))
                if (args[0].equals("test", ignoreCase = true)) {
                    val session = root.doorService.activeSessionInfo(door.world)
                    sender.sendMessage(Component.text("当前会话: ${session ?: "无"}", NamedTextColor.YELLOW))
                }
            }
            "trigger" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) {
                    noPermission(sender)
                    return
                }
                val number = args.getOrNull(1)?.toIntOrNull()
                if (number == null) {
                    sender.sendMessage(Component.text("用法: /zr2 door trigger <门号>", NamedTextColor.RED))
                    return
                }
                val world = (sender as? Player)?.world?.name ?: defaultWorld
                val result = root.doorService.triggerDoor(world, number, sender.name)
                sender.sendMessage(Component.text(result.message, if (result.success) NamedTextColor.GREEN else NamedTextColor.RED))
            }
            "add" -> addDoor(sender, args.drop(1))
            "remove" -> removeDoor(sender, args.drop(1))
            "edit" -> editDoor(sender, args.drop(1))
            "reset" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) {
                    noPermission(sender)
                    return
                }
                root.doorService.reload()
                sender.sendMessage(Component.text("已重新加载所有 arena 门配置", NamedTextColor.GREEN))
            }
            "behavior" -> handleDoorBehavior(sender, args.drop(1))
            else -> sender.sendMessage(Component.text("未知子命令", NamedTextColor.RED))
        }
    }

    private fun removeDoor(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        val id = args.firstOrNull() ?: run {
            sender.sendMessage(Component.text("用法: /zr2 door remove <id>", NamedTextColor.RED))
            return
        }
        val arena = root.arenaRepository.all().firstOrNull { it.doorById(id) != null } ?: run {
            sender.sendMessage(Component.text("门不存在：$id", NamedTextColor.RED))
            return
        }
        val door = arena.doorById(id)!!
        door.snapshotId?.let { root.snapshotStore.delete(it) }
        root.arenaRepository.save(arena.copy(doors = arena.doors.filterNot { it.id == id }))
        sender.sendMessage(Component.text("门 '$id' 已删除", NamedTextColor.GREEN))
    }

    private fun editDoor(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        val id = args.firstOrNull() ?: run {
            sender.sendMessage(Component.text("用法: /zr2 door edit <id> [--open N] [--close N] [--group G] [--mode MODE] [--number N]", NamedTextColor.RED))
            return
        }
        val arena = root.arenaRepository.all().firstOrNull { it.doorById(id) != null } ?: run {
            sender.sendMessage(Component.text("门不存在：$id", NamedTextColor.RED))
            return
        }
        val door = arena.doorById(id)!!
        val (options, _) = parseArgs(args.drop(1))
        var updated = door
        options["open"]?.toIntOrNull()?.let { updated = updated.copy(openSeconds = it) }
        options["close"]?.toIntOrNull()?.let { updated = updated.copy(closeSeconds = it) }
        options["group"]?.let { updated = updated.copy(group = it.ifBlank { null }) }
        options["mode"]?.let {
            val mode = cn.oneachina.zombierun.v2.domain.door.DoorMode.entries.firstOrNull { m -> m.name.equals(it, true) }
                ?: run { sender.sendMessage(Component.text("mode 必须是 normal|start|player|zombie", NamedTextColor.RED)); return }
            updated = updated.copy(mode = mode, number = if (mode == cn.oneachina.zombierun.v2.domain.door.DoorMode.NORMAL) updated.number ?: 1 else null)
        }
        options["number"]?.toIntOrNull()?.let { updated = updated.copy(number = it) }
        root.arenaRepository.save(arena.copy(doors = arena.doors.map { if (it.id == id) updated else it }))
        sender.sendMessage(Component.text("门 '$id' 已更新", NamedTextColor.GREEN))
    }

    private fun handleDoorBehavior(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "info" -> {
                val id = args.getOrNull(1) ?: run {
                    sender.sendMessage(Component.text("用法: /zr2 door behavior info <id>", NamedTextColor.RED)); return
                }
                val arena = root.arenaRepository.all().firstOrNull { it.doorById(id) != null } ?: run {
                    sender.sendMessage(Component.text("门不存在：$id", NamedTextColor.RED)); return
                }
                val behavior = arena.doorById(id)?.behavior
                sender.sendMessage(
                    Component.text(
                        if (behavior == null) "门 $id 没有特殊行为" else "门 $id 行为=${behavior.type.name.lowercase()} human=(${behavior.humanTargetX},${behavior.humanTargetY},${behavior.humanTargetZ}) zombie=(${behavior.zombieTargetX},${behavior.zombieTargetY},${behavior.zombieTargetZ}) countdown=${behavior.countdown}",
                        NamedTextColor.GREEN,
                    ),
                )
            }
            "set" -> setDoorBehavior(sender, args.drop(1))
            "remove" -> {
                val id = args.getOrNull(1) ?: run {
                    sender.sendMessage(Component.text("用法: /zr2 door behavior remove <id>", NamedTextColor.RED)); return
                }
                val arena = root.arenaRepository.all().firstOrNull { it.doorById(id) != null } ?: run {
                    sender.sendMessage(Component.text("门不存在：$id", NamedTextColor.RED)); return
                }
                root.arenaRepository.save(
                    arena.copy(doors = arena.doors.map { if (it.id == id) it.copy(behavior = null) else it }),
                )
                sender.sendMessage(Component.text("已移除门 $id 的特殊行为", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("用法: /zr2 door behavior info|set|remove", NamedTextColor.RED))
        }
    }

    private fun setDoorBehavior(sender: CommandSender, args: List<String>) {
        val id = args.firstOrNull() ?: run {
            sender.sendMessage(Component.text("用法: /zr2 door behavior set <id> <elevator|subway|airport> [--human x,y,z] [--zombie x,y,z] [--line name] [--countdown n]", NamedTextColor.RED)); return
        }
        val type = cn.oneachina.zombierun.v2.domain.door.DoorBehaviorType.entries.firstOrNull {
            it.name.equals(args.getOrNull(1), ignoreCase = true)
        } ?: run {
            sender.sendMessage(Component.text("类型必须是 elevator|subway|airport", NamedTextColor.RED)); return
        }
        val arena = root.arenaRepository.all().firstOrNull { it.doorById(id) != null } ?: run {
            sender.sendMessage(Component.text("门不存在：$id", NamedTextColor.RED)); return
        }
        val door = arena.doorById(id)!!
        val (options, _) = parseArgs(args.drop(2))
        fun triple(value: String?): Triple<Double?, Double?, Double?>? {
            if (value == null) return null
            val parts = value.split(',').map { it.trim().toDoubleOrNull() }
            if (parts.size != 3 || parts.any { it == null }) {
                sender.sendMessage(Component.text("坐标格式: x,y,z", NamedTextColor.RED))
                return null
            }
            return Triple(parts[0], parts[1], parts[2])
        }
        val human = triple(options["human"])
        if (human == null && options["human"] != null) return
        val zombie = triple(options["zombie"])
        if (zombie == null && options["zombie"] != null) return
        val behavior = cn.oneachina.zombierun.v2.domain.door.DoorBehavior(
            type = type,
            humanTargetX = human?.first,
            humanTargetY = human?.second,
            humanTargetZ = human?.third,
            zombieTargetX = zombie?.first,
            zombieTargetY = zombie?.second,
            zombieTargetZ = zombie?.third,
            lineName = options["line"],
            countdown = options["countdown"]?.toIntOrNull() ?: 5,
            delayTicks = options["delay"]?.toLongOrNull() ?: 0,
        )
        root.arenaRepository.save(arena.copy(doors = arena.doors.map { if (it.id == id) it.copy(behavior = behavior) else it }))
        sender.sendMessage(Component.text("门 $id 特殊行为已设置：${type.name.lowercase()}", NamedTextColor.GREEN))
    }

    private fun handleLobby(sender: CommandSender) {
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("lobby 需要玩家执行", NamedTextColor.RED)); return
        }
        val wait = root.arenaRepository.byWorld(player.world.name)
            .flatMap { it.respawns }
            .firstOrNull { it.type == RespawnType.WAIT }
        if (wait == null) {
            player.sendMessage(Component.text("当前世界没有配置等待大厅重生点", NamedTextColor.RED))
            return
        }
        root.teleporter.teleport(player.uniqueId, wait.world, wait.x, wait.y, wait.z, wait.yaw, wait.pitch)
        player.sendMessage(Component.text("已返回等待大厅", NamedTextColor.GREEN))
    }

    private fun handleDebug(sender: CommandSender) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        root.logger.debugEnabled = !root.logger.debugEnabled
        sender.sendMessage(Component.text("debug 模式：${if (root.logger.debugEnabled) "开启" else "关闭"}", NamedTextColor.GREEN))
    }

    private fun handlePostool(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("postool 需要玩家执行", NamedTextColor.RED))
            return
        }
        val id = sender.uniqueId
        if (!postoolUsers.remove(id)) {
            postoolUsers.add(id)
            pos1.remove(id); pos2.remove(id)
            sender.inventory.addItem(org.bukkit.inventory.ItemStack(org.bukkit.Material.STICK))
            sender.sendMessage(Component.text("已给你一根选区棒！左键方块=pos1，右键方块=pos2", NamedTextColor.GREEN))
            sender.sendMessage(Component.text("之后可用 /zr2 door add <axis> <front> 省略坐标和 --arena（自动使用当前世界）", NamedTextColor.GRAY))
        } else {
            sender.sendMessage(Component.text("postool 已关闭", NamedTextColor.YELLOW))
        }
    }

    private fun addDoor(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        val parsed = parseArgs(args)
        val arena = resolveArena(sender, parsed.options["arena"])
        if (arena == null) {
            sender.sendMessage(Component.text("用法: /zr2 door add [--arena <名称>] <坐标6个|省略> <axis> <front>（省略 --arena 时自动使用当前世界 arena；/zr2 postool 选区后可省略坐标）", NamedTextColor.RED))
            return
        }

        // postool 选区补齐坐标：无坐标参数时取选区
        val positional = if (parsed.positional.size >= 8) {
            parsed.positional
        } else {
            val p = sender as? Player
            val a = p?.let { pos1[it.uniqueId] }
            val b = p?.let { pos2[it.uniqueId] }
            if (a == null || b == null) {
                sender.sendMessage(Component.text("需要 6 个坐标，或用 /zr2 postool 选区后省略坐标", NamedTextColor.RED))
                return
            }
            listOf(a.blockX, a.blockY, a.blockZ, b.blockX, b.blockY, b.blockZ).map { it.toString() } + parsed.positional
        }

        val doorId = "door_${System.currentTimeMillis()}"
        val result = parseDoorAdd(arena, doorId, parsed.options, positional)
        if (result is DoorAddParseResult.Error) {
            sender.sendMessage(Component.text(result.message, NamedTextColor.RED))
            return
        }
        val door = (result as DoorAddParseResult.Success).door
        val volume = (door.region.maxX - door.region.minX + 1).toLong() *
            (door.region.maxY - door.region.minY + 1).toLong() *
            (door.region.maxZ - door.region.minZ + 1).toLong()
        if (volume > MAX_DOOR_BLOCKS) {
            sender.sendMessage(Component.text("门区域过大：$volume 方块超过上限 $MAX_DOOR_BLOCKS，请缩小选区", NamedTextColor.RED))
            return
        }

        val snapshot = root.blockOps.scanRegion(arena.world, door.region)
        root.snapshotStore.save(door.snapshotId!!, snapshot)
        root.arenaRepository.save(arena.copy(doors = arena.doors + door))
        sender.sendMessage(Component.text("门 '${door.id}' (#${door.number}) 已添加，快照 ${snapshot.size} 个方块", NamedTextColor.GREEN))
    }

    // ---------- button / respawn ----------

    private fun handleButton(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "add" -> addButton(sender, args.drop(1))
            "remove" -> {
                val id = args.getOrNull(1) ?: run {
                    sender.sendMessage(Component.text("用法: /zr2 button remove <id>", NamedTextColor.RED)); return
                }
                val arena = root.arenaRepository.all().firstOrNull { it.buttons.any { b -> b.id == id } } ?: run {
                    sender.sendMessage(Component.text("按钮不存在：$id", NamedTextColor.RED)); return
                }
                root.arenaRepository.save(arena.copy(buttons = arena.buttons.filterNot { it.id == id }))
                sender.sendMessage(Component.text("按钮 '$id' 已删除", NamedTextColor.GREEN))
            }
            "list" -> {
                val world = args.getOrNull(1) ?: (sender as? Player)?.world?.name ?: defaultWorld
                val buttons = root.arenaRepository.byWorld(world).flatMap { it.buttons }
                if (buttons.isEmpty()) {
                    sender.sendMessage(Component.text("世界 $world 还没有按钮", NamedTextColor.YELLOW))
                } else {
                    buttons.forEach {
                        sender.sendMessage(Component.text("- ${it.id}  mode=${it.mode.name.lowercase()} at=(${it.x},${it.y},${it.z}) doors=${it.doorNumbers.joinToString("/")}", NamedTextColor.GREEN))
                    }
                }
            }
            else -> sender.sendMessage(Component.text("用法: /zr2 button add|remove|list", NamedTextColor.RED))
        }
    }

    private fun addButton(sender: CommandSender, args: List<String>) {
        val (options, positional) = parseArgs(args)
        val arena = resolveArena(sender, options["arena"])
        if (arena == null || positional.size < 4) {
            sender.sendMessage(Component.text("用法: /zr2 button add [--arena <名称>] <x> <y> <z> <normal|escape> [门号...]（省略 --arena 时自动使用当前世界 arena）", NamedTextColor.RED))
            return
        }
        val coords = positional.take(3).map { it.toIntOrNull() }
        if (coords.any { it == null }) {
            sender.sendMessage(Component.text("坐标必须是整数", NamedTextColor.RED))
            return
        }
        val mode = ButtonMode.entries.firstOrNull { it.name.equals(positional[3], ignoreCase = true) }
            ?: run {
                sender.sendMessage(Component.text("mode 必须是 normal|escape", NamedTextColor.RED))
                return
            }
        val doorNumbers = positional.drop(4).mapNotNull { it.trim().toIntOrNull() }
        if (mode == ButtonMode.NORMAL && doorNumbers.isEmpty()) {
            sender.sendMessage(Component.text("normal 按钮至少指定一个门号", NamedTextColor.RED))
            return
        }
        val id = "button_${System.currentTimeMillis()}"
        val button = ButtonDefinition(
            id = id,
            world = arena.world,
            x = coords[0]!!,
            y = coords[1]!!,
            z = coords[2]!!,
            mode = mode,
            doorNumbers = doorNumbers,
        )
        root.arenaRepository.save(arena.copy(buttons = arena.buttons + button))
        sender.sendMessage(Component.text("按钮 '$id' 已添加（${mode.name.lowercase()}）", NamedTextColor.GREEN))
    }

    private fun handleRespawn(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "add" -> addRespawn(sender, args.drop(1))
            "remove" -> {
                val id = args.getOrNull(1) ?: run {
                    sender.sendMessage(Component.text("用法: /zr2 respawn remove <id>", NamedTextColor.RED)); return
                }
                val arena = root.arenaRepository.all().firstOrNull { it.respawns.any { r -> r.id == id } } ?: run {
                    sender.sendMessage(Component.text("respawn 不存在：$id", NamedTextColor.RED)); return
                }
                root.arenaRepository.save(arena.copy(respawns = arena.respawns.filterNot { it.id == id }))
                sender.sendMessage(Component.text("respawn '$id' 已删除", NamedTextColor.GREEN))
            }
            "list" -> {
                val world = args.getOrNull(1) ?: (sender as? Player)?.world?.name ?: defaultWorld
                val respawns = root.arenaRepository.byWorld(world).flatMap { it.respawns }
                if (respawns.isEmpty()) {
                    sender.sendMessage(Component.text("世界 $world 还没有重生点", NamedTextColor.YELLOW))
                } else {
                    respawns.forEach {
                        sender.sendMessage(Component.text("- ${it.id}  ${it.type.name.lowercase()} at=(${it.x},${it.y},${it.z}) door=${it.doorNumber ?: "-"}", NamedTextColor.GREEN))
                    }
                }
            }
            else -> sender.sendMessage(Component.text("用法: /zr2 respawn add|remove|list", NamedTextColor.RED))
        }
    }

    private fun addRespawn(sender: CommandSender, args: List<String>) {
        val (options, positional) = parseArgs(args)
        val arena = resolveArena(sender, options["arena"])
        if (arena == null || positional.size < 4) {
            sender.sendMessage(Component.text("用法: /zr2 respawn add [--arena <名称>] <type> <x> <y> <z> [door-number] [yaw] [pitch]（省略 --arena 时自动使用当前世界 arena）", NamedTextColor.RED))
            return
        }
        val type = RespawnType.entries.firstOrNull { it.name.equals(positional[0], ignoreCase = true) }
        if (type == null) {
            sender.sendMessage(Component.text("type 必须是 ${RespawnType.entries.joinToString("|") { it.name.lowercase() }}", NamedTextColor.RED))
            return
        }
        val coords = positional.drop(1).take(3).map { it.toDoubleOrNull() }
        if (coords.any { it == null }) {
            sender.sendMessage(Component.text("坐标必须是数字", NamedTextColor.RED))
            return
        }
        val doorNumber = positional.getOrNull(4)?.toIntOrNull()
        if ((type == RespawnType.DOOR_PLAYER || type == RespawnType.DOOR_ZOMBIE) && doorNumber == null) {
            sender.sendMessage(Component.text("此 respawn 类型需要 door-number", NamedTextColor.RED))
            return
        }
        val id = "respawn_${System.currentTimeMillis()}"
        val respawn = RespawnDefinition(
            id = id,
            world = arena.world,
            type = type,
            x = coords[0]!!,
            y = coords[1]!!,
            z = coords[2]!!,
            yaw = positional.getOrNull(5)?.toFloatOrNull() ?: 0f,
            pitch = positional.getOrNull(6)?.toFloatOrNull() ?: 0f,
            doorNumber = doorNumber,
        )
        root.arenaRepository.save(arena.copy(respawns = arena.respawns + respawn))
        sender.sendMessage(Component.text("respawn '$id' 已添加", NamedTextColor.GREEN))
    }

    // ---------- game ----------

    private fun handleGame(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr2 game list|status|start|end|reset", NamedTextColor.RED))
            return
        }
        val world = args.getOrNull(1) ?: (sender as? Player)?.world?.name ?: defaultWorld
        when (args[0].lowercase()) {
            "list" -> {
                val worlds = root.gameFlow.gameWorlds()
                if (worlds.isEmpty()) {
                    sender.sendMessage(Component.text("没有配置 arena 的世界", NamedTextColor.YELLOW))
                } else {
                    worlds.forEach { w ->
                        val phase = root.gameFlow.phaseOf(w)?.name ?: "-"
                        sender.sendMessage(Component.text("- $w  phase=$phase", NamedTextColor.GREEN))
                    }
                }
            }
            "status" -> {
                val instance = root.gameFlow.instance(world)
                if (instance == null) {
                    sender.sendMessage(Component.text("世界 $world 没有对局实例（需先配置 arena）", NamedTextColor.RED))
                    return
                }
                sender.sendMessage(Component.text("===== game $world =====", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("phase=${instance.phaseSnapshot().name} alpha=${instance.alphaId()} humans=${instance.humanIds().size} zombies=${instance.zombieIds().size}", NamedTextColor.GREEN))
                val flowStage = root.gameFlow.currentStageDoorNumbers(world)?.let { root.gameFlow.currentStageLabel(world) }
                if (flowStage != null) {
                    sender.sendMessage(Component.text("map-flow=${root.gameFlow.mapFlowPhase(world)?.name} stage=${flowStage} doors=${root.gameFlow.currentStageDoorNumbers(world)?.joinToString("/")}", NamedTextColor.GREEN))
                }
                instance.humanIds().forEach { id ->
                    val name = root.worldAccess.player(id)?.name ?: id.toString()
                    sender.sendMessage(Component.text("- HUMAN $name room=${instance.roomOf(id)} kills=${root.gameFlow.killCount(id)}", NamedTextColor.AQUA))
                }
                instance.zombieIds().forEach { id ->
                    val name = root.worldAccess.player(id)?.name ?: id.toString()
                    sender.sendMessage(Component.text("- ZOMBIE $name${if (id == instance.alphaId()) " (母体)" else ""}", NamedTextColor.AQUA))
                }
            }
            "start" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) {
                    noPermission(sender)
                    return
                }
                if (root.gameFlow.forceStart(world)) {
                    sender.sendMessage(Component.text("已强制开始世界 $world 的对局", NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text("无法开始：世界无 arena 或没有在线玩家", NamedTextColor.RED))
                }
            }
            "end" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) {
                    noPermission(sender)
                    return
                }
                val winner = when (args.getOrNull(2)?.lowercase()) {
                    "human" -> cn.oneachina.zombierun.v2.domain.game.GameTeam.HUMAN
                    "zombie" -> cn.oneachina.zombierun.v2.domain.game.GameTeam.ZOMBIE_MAIN
                    else -> {
                        sender.sendMessage(Component.text("用法: /zr2 game end <世界> <human|zombie>", NamedTextColor.RED))
                        return
                    }
                }
                if (root.gameFlow.endGame(world, winner)) {
                    sender.sendMessage(Component.text("对局已结束（$winner）", NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text("世界 $world 没有对局实例", NamedTextColor.RED))
                }
            }
            "reset" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) {
                    noPermission(sender)
                    return
                }
                if (root.gameFlow.reset(world)) {
                    sender.sendMessage(Component.text("世界 $world 对局已重置", NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text("世界 $world 没有对局实例", NamedTextColor.RED))
                }
            }
            else -> sender.sendMessage(Component.text("未知子命令", NamedTextColor.RED))
        }
    }

    // ---------- weapon ----------

    private fun handleWeapon(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr2 weapon list|info|add|remove|give|random", NamedTextColor.RED))
            return
        }
        when (args[0].lowercase()) {
            "list" -> {
                val weapons = root.weaponService.all()
                if (weapons.isEmpty()) {
                    sender.sendMessage(Component.text("还没有武器配置", NamedTextColor.YELLOW))
                } else {
                    weapons.forEach {
                        sender.sendMessage(Component.text("- ${it.id}  ${it.displayName}  type=${it.type}  category=${it.category.name.lowercase()}  price=${it.price}  enabled=${it.enabled}", NamedTextColor.GREEN))
                    }
                }
            }
            "info" -> {
                val weapon = root.weaponService.byId(args.getOrNull(1) ?: "")
                if (weapon == null) {
                    sender.sendMessage(Component.text("武器不存在：${args.getOrNull(1)}", NamedTextColor.RED))
                    return
                }
                sender.sendMessage(Component.text("===== weapon ${weapon.id} =====", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("displayName=${weapon.displayName} type=${weapon.type} category=${weapon.category.name.lowercase()} price=${weapon.price} enabled=${weapon.enabled}", NamedTextColor.GREEN))
                val available = root.weaponIntegration.isAvailable(weapon.type)
                sender.sendMessage(Component.text("QA available=$available", if (available) NamedTextColor.GREEN else NamedTextColor.RED))
            }
            "add" -> addWeapon(sender, args.drop(1))
            "remove" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) {
                    noPermission(sender)
                    return
                }
                val id = args.getOrNull(1) ?: run {
                    sender.sendMessage(Component.text("用法: /zr2 weapon remove <id>", NamedTextColor.RED))
                    return
                }
                if (root.weaponService.remove(id)) {
                    sender.sendMessage(Component.text("武器 '$id' 已删除", NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text("武器不存在：$id", NamedTextColor.RED))
                }
            }
            "give" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) {
                    noPermission(sender)
                    return
                }
                val id = args.getOrNull(1) ?: run {
                    sender.sendMessage(Component.text("用法: /zr2 weapon give <id>", NamedTextColor.RED))
                    return
                }
                val player = sender as? Player ?: run {
                    sender.sendMessage(Component.text("give 命令需要玩家执行", NamedTextColor.RED))
                    return
                }
                root.weaponService.giveWeaponWithAmmo(player.uniqueId, id)
            }
            "random" -> {
                val player = sender as? Player ?: run {
                    sender.sendMessage(Component.text("random 命令需要玩家执行", NamedTextColor.RED))
                    return
                }
                val category = WeaponCategory.entries.firstOrNull {
                    it.name.equals(args.getOrNull(1), ignoreCase = true)
                }
                val weapon = root.weaponService.giveRandom(player.uniqueId, category)
                if (weapon == null) sender.sendMessage(Component.text("没有可用武器", NamedTextColor.RED))
            }
            "select" -> {
                val player = sender as? Player ?: run {
                    sender.sendMessage(Component.text("select 命令需要玩家执行", NamedTextColor.RED))
                    return
                }
                val id = args.getOrNull(1) ?: run {
                    sender.sendMessage(Component.text("用法: /zr2 weapon select <id>", NamedTextColor.RED))
                    return
                }
                if (!root.weaponService.selectWeapon(player.uniqueId, id)) {
                    sender.sendMessage(Component.text("武器不存在或未启用：$id", NamedTextColor.RED))
                }
            }
            "unselect" -> {
                val player = sender as? Player ?: run {
                    sender.sendMessage(Component.text("unselect 命令需要玩家执行", NamedTextColor.RED))
                    return
                }
                root.weaponService.unselectWeapon(player.uniqueId)
            }
            else -> sender.sendMessage(Component.text("未知子命令", NamedTextColor.RED))
        }
    }

    private fun addWeapon(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        if (args.size < 4) {
            sender.sendMessage(Component.text("用法: /zr2 weapon add <id> <type> <category> <price> [display-name]", NamedTextColor.RED))
            return
        }
        val id = args[0]
        if (root.weaponService.byId(id) != null) {
            sender.sendMessage(Component.text("武器 id '$id' 已存在", NamedTextColor.RED))
            return
        }
        val type = args[1]
        val category = WeaponCategory.entries.firstOrNull {
            it.name.equals(args[2], ignoreCase = true)
        } ?: run {
            sender.sendMessage(Component.text("category 必须是 ${WeaponCategory.entries.joinToString("|") { it.name.lowercase() }}", NamedTextColor.RED))
            return
        }
        val price = args[3].toDoubleOrNull() ?: run {
            sender.sendMessage(Component.text("价格必须是数字", NamedTextColor.RED))
            return
        }
        val name = args.getOrNull(4) ?: id
        root.weaponService.add(WeaponDefinition(id, name, type, category, price))
        sender.sendMessage(Component.text("武器 '$id' 已保存", NamedTextColor.GREEN))
    }

    // ---------- player profile / economy ----------

    private fun handleProfile(sender: CommandSender, args: List<String>) {
        val target = if (args.isNullOrEmpty()) {
            sender as? Player ?: run {
                sender.sendMessage(Component.text("控制台必须指定玩家名", NamedTextColor.RED))
                return
            }
        } else {
            Bukkit.getPlayerExact(args[0]) ?: run {
                sender.sendMessage(Component.text("玩家不在线：${args[0]}", NamedTextColor.RED))
                return
            }
        }
        val profile = root.playerDataService.profileOf(target.uniqueId)
        sender.sendMessage(Component.text("===== ${target.name} 资料 =====", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("等级 ${profile.level}  经验 ${profile.xp}  硬币 ${profile.coins}  称号 ${profile.title ?: "-"}", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("门数 ${profile.doorPasses}  击杀 ${profile.zombieKills}  感染 ${profile.totalInfections}", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("场次 ${profile.gamesPlayed}  人类胜利 ${profile.humanWins}  已解锁称号 ${profile.unlockedTitles.size}", NamedTextColor.GREEN))
    }

    private fun handleCoins(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /zr2 coins add|give|spend", NamedTextColor.RED))
            return
        }
        when (args[0].lowercase()) {
            "add" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
                val amount = args.getOrNull(1)?.toIntOrNull() ?: return badNumber(sender)
                val player = sender as? Player ?: run { sender.sendMessage(Component.text("add 需要玩家执行", NamedTextColor.RED)); return }
                val profile = root.playerDataService.addCoins(player.uniqueId, amount)
                sender.sendMessage(Component.text("已添加 $amount 硬币，当前 ${profile.coins}", NamedTextColor.GREEN))
            }
            "give" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(1)}", NamedTextColor.RED)); return
                }
                val amount = args.getOrNull(2)?.toIntOrNull() ?: return badNumber(sender)
                val profile = root.playerDataService.addCoins(target.uniqueId, amount)
                sender.sendMessage(Component.text("已给 ${target.name} $amount 硬币，当前 ${profile.coins}", NamedTextColor.GREEN))
            }
            "remove" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(1)}", NamedTextColor.RED)); return
                }
                val amount = args.getOrNull(2)?.toIntOrNull() ?: return badNumber(sender)
                val profile = root.playerDataService.removeCoins(target.uniqueId, amount)
                sender.sendMessage(Component.text("已扣除 ${target.name} $amount 硬币，当前 ${profile.coins}", NamedTextColor.GREEN))
            }
            "set" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(1)}", NamedTextColor.RED)); return
                }
                val amount = args.getOrNull(2)?.toIntOrNull() ?: return badNumber(sender)
                val profile = root.playerDataService.setCoins(target.uniqueId, amount)
                sender.sendMessage(Component.text("已设置 ${target.name} 硬币为 ${profile.coins}", NamedTextColor.GREEN))
            }
            "get" -> {
                if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(1)}", NamedTextColor.RED)); return
                }
                val profile = root.playerDataService.profileOf(target.uniqueId)
                sender.sendMessage(Component.text("${target.name} 当前硬币：${profile.coins}", NamedTextColor.GREEN))
            }
            "spend" -> {
                val player = sender as? Player ?: run { sender.sendMessage(Component.text("spend 需要玩家执行", NamedTextColor.RED)); return }
                val amount = args.getOrNull(1)?.toIntOrNull() ?: return badNumber(sender)
                val profile = root.playerDataService.spendCoins(player.uniqueId, amount)
                if (profile == null) sender.sendMessage(Component.text("硬币不足", NamedTextColor.RED))
                else sender.sendMessage(Component.text("已花费 $amount，剩余 ${profile.coins}", NamedTextColor.GREEN))
            }
            "transfer" -> {
                val player = sender as? Player ?: run { sender.sendMessage(Component.text("transfer 需要玩家执行", NamedTextColor.RED)); return }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(1)}", NamedTextColor.RED)); return
                }
                val amount = args.getOrNull(2)?.toIntOrNull() ?: return badNumber(sender)
                if (amount <= 0) {
                    sender.sendMessage(Component.text("转账金额必须为正数", NamedTextColor.RED))
                    return
                }
                if (target.uniqueId == player.uniqueId) {
                    sender.sendMessage(Component.text("不能给自己转账", NamedTextColor.RED))
                    return
                }
                if (root.playerDataService.transferCoins(player.uniqueId, target.uniqueId, amount)) {
                    sender.sendMessage(Component.text("已转账 $amount 硬币给 ${target.name}", NamedTextColor.GREEN))
                    target.sendMessage(Component.text("收到 ${player.name} 转账 $amount 硬币", NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text("转账失败：硬币不足", NamedTextColor.RED))
                }
            }
            "top" -> {
                val limit = (args.getOrNull(1)?.toIntOrNull() ?: 10).coerceIn(1, 100)
                // 异步查询排行榜，避免命令线程同步扫全表
                java.util.concurrent.CompletableFuture.supplyAsync {
                    root.playerDataService.topCoins(limit)
                }.thenAccept { top ->
                    Bukkit.getGlobalRegionScheduler().run(plugin) { _ ->
                        if (top.isEmpty()) {
                            sender.sendMessage(Component.text("暂无排行榜数据", NamedTextColor.YELLOW))
                        } else {
                            sender.sendMessage(Component.text("===== 金币排行榜 Top $limit =====", NamedTextColor.GREEN))
                            top.forEachIndexed { index, (id, coins) ->
                                val name = root.worldAccess.player(id)?.name ?: Bukkit.getOfflinePlayer(id).name ?: id.toString()
                                sender.sendMessage(Component.text("${index + 1}. $name  $coins 硬币", NamedTextColor.GREEN))
                            }
                        }
                    }
                }
            }
            else -> sender.sendMessage(Component.text("未知子命令", NamedTextColor.RED))
        }
    }

    private fun handleXp(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
        when (args.getOrNull(0)?.lowercase()) {
            "add" -> {
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(1)}", NamedTextColor.RED)); return
                }
                val amount = args.getOrNull(2)?.toIntOrNull() ?: return badNumber(sender)
                val profile = root.playerDataService.addXp(target.uniqueId, amount)
                sender.sendMessage(Component.text("已给 ${target.name} $amount 经验，当前等级 ${profile.level}", NamedTextColor.GREEN))
            }
            "set" -> {
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(1)}", NamedTextColor.RED)); return
                }
                val amount = args.getOrNull(2)?.toIntOrNull() ?: return badNumber(sender)
                val profile = root.playerDataService.setXp(target.uniqueId, amount)
                sender.sendMessage(Component.text("已设置 ${target.name} 经验为 ${profile.xp}", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("用法: /zr2 xp add|set <玩家> <数量>", NamedTextColor.RED))
        }
    }

    private fun handleLevel(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
        val offset = if (args.firstOrNull()?.equals("set", ignoreCase = true) == true) 1 else 0
        val target = Bukkit.getPlayerExact(args.getOrNull(offset) ?: "") ?: run {
            sender.sendMessage(Component.text("用法: /zr2 level set <玩家> <等级>", NamedTextColor.RED)); return
        }
        val level = args.getOrNull(offset + 1)?.toIntOrNull() ?: return badNumber(sender)
        val profile = root.playerDataService.setLevel(target.uniqueId, level)
        sender.sendMessage(Component.text("已设置 ${target.name} 等级为 ${profile.level}", NamedTextColor.GREEN))
    }

    private fun handleReset(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
        val target = Bukkit.getPlayerExact(args.getOrNull(0) ?: "") ?: run {
            sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(0)}", NamedTextColor.RED)); return
        }
        root.playerDataService.resetPlayer(target.uniqueId)
        sender.sendMessage(Component.text("已重置 ${target.name} 的玩家数据", NamedTextColor.GREEN))
    }

    private fun handleTitle(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
        when (args.getOrNull(0)?.lowercase()) {
            "set" -> {
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(1)}", NamedTextColor.RED)); return
                }
                val title = args.drop(2).joinToString(" ").ifBlank { null }
                root.playerDataService.setTitle(target.uniqueId, title)
                sender.sendMessage(Component.text("已设置 ${target.name} 称号：${title ?: "无"}", NamedTextColor.GREEN))
            }
            "clear" -> {
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("玩家不在线：${args.getOrNull(1)}", NamedTextColor.RED)); return
                }
                root.playerDataService.setTitle(target.uniqueId, null)
                sender.sendMessage(Component.text("已清除 ${target.name} 称号", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("用法: /zr2 title set|clear <玩家> [称号]", NamedTextColor.RED))
        }
    }

    private fun badNumber(sender: CommandSender) {
        sender.sendMessage(Component.text("数量必须是整数", NamedTextColor.RED))
    }

    // ---------- menu ----------

    private fun handleMenu(sender: CommandSender, args: List<String>) {
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("menu 命令需要玩家执行", NamedTextColor.RED)); return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "profile" -> root.guiService.openProfile(player)
            "shop" -> root.guiService.openShop(player)
            "tasks" -> root.guiService.openTasks(player)
            "titles" -> root.guiService.openTitles(player)
            else -> sender.sendMessage(Component.text("用法: /zr2 menu profile|shop|tasks|titles", NamedTextColor.RED))
        }
    }

    // ---------- task ----------

    private fun handleTask(sender: CommandSender, args: List<String>) {
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("task 命令需要玩家执行", NamedTextColor.RED)); return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "list" -> {
                sender.sendMessage(Component.text("===== 任务列表 =====", NamedTextColor.GREEN))
                root.taskService.progressOf(player.uniqueId).forEach { (task, progress) ->
                    val status = when {
                        progress.claimed -> "§7已领取"
                        progress.progress >= task.target -> "§a可领取"
                        else -> "${progress.progress}/${task.target}"
                    }
                    sender.sendMessage(
                        Component.text("[${task.period.name.lowercase()}] ${task.description} - $status（奖励 ${task.rewardCoins} 币/${task.rewardXp} 经验）"),
                    )
                }
            }
            "claim" -> {
                val taskId = args.getOrNull(1) ?: run {
                    sender.sendMessage(Component.text("用法: /zr2 task claim <任务id>", NamedTextColor.RED)); return
                }
                sender.sendMessage(Component.text(root.taskService.claim(player.uniqueId, taskId), NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("用法: /zr2 task list|claim <任务id>", NamedTextColor.RED))
        }
    }

    // ---------- map-flow 编辑 ----------

    private fun handleMapFlow(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "list" -> {
                val flows = root.arenaRepository.all().filter { it.mapFlow != null }
                if (flows.isEmpty()) {
                    sender.sendMessage(Component.text("没有配置 map-flow 的 arena", NamedTextColor.YELLOW))
                } else {
                    flows.forEach { a ->
                        val f = a.mapFlow!!
                        sender.sendMessage(Component.text("- ${a.name}  stages=${f.stages.size} finish=${f.finish.type.name}", NamedTextColor.GREEN))
                    }
                }
            }
            "info" -> {
                val arena = root.arenaRepository.byName(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("arena 不存在", NamedTextColor.RED)); return
                }
                val f = arena.mapFlow ?: run {
                    sender.sendMessage(Component.text("${arena.name} 未配置 map-flow", NamedTextColor.YELLOW)); return
                }
                sender.sendMessage(Component.text("===== map-flow ${arena.name} =====", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("min-players=${f.minPlayers} start=${f.startDelaySeconds}s max=${f.maxDurationSeconds}s mother-release=${f.motherReleaseDelaySeconds}s", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("reward: human ${f.rewardCoinsHuman}/${f.rewardXpHuman} zombie ${f.rewardCoinsZombie}/${f.rewardXpZombie} starter=${f.starterWeaponId ?: "-"}", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("finish: ${f.finish.type.name} door=${f.finish.doorNumber ?: "-"}", NamedTextColor.GREEN))
                f.stages.forEachIndexed { index, s ->
                    val next = s.nextStageId ?: "终点"
                    sender.sendMessage(Component.text("  ${index + 1}. ${s.id} [${s.label}] doors=${s.doorNumbers.joinToString("/")} -> $next", NamedTextColor.AQUA))
                }
            }
            "init" -> {
                val arena = root.arenaRepository.byName(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("arena 不存在", NamedTextColor.RED)); return
                }
                val numbered = arena.doors.mapNotNull { it.number }.sorted()
                val stages = if (numbered.isEmpty()) {
                    listOf(MapFlowStage("s1", "阶段1", listOf(1)))
                } else {
                    numbered.mapIndexed { index, n ->
                        MapFlowStage("s${index + 1}", "阶段${index + 1}", listOf(n), if (index == numbered.lastIndex) null else "s${index + 2}")
                    }
                }
                val flow = MapFlowDefinition(
                    arenaName = arena.name,
                    world = arena.world,
                    minPlayers = 1,
                    startDelaySeconds = 10,
                    maxDurationSeconds = 600,
                    stages = stages,
                    finish = MapFlowFinish(FinishType.DOOR, numbered.lastOrNull() ?: 1),
                )
                root.arenaRepository.save(arena.copy(mapFlow = flow))
                sender.sendMessage(Component.text("已为 ${arena.name} 初始化 map-flow（${stages.size} 个阶段）", NamedTextColor.GREEN))
            }
            "set" -> handleMapFlowSet(sender, args.drop(1))
            "stage" -> handleMapFlowStage(sender, args.drop(1))
            "finish" -> handleMapFlowFinish(sender, args.drop(1))
            "remove" -> {
                val arena = root.arenaRepository.byName(args.getOrNull(1) ?: "") ?: run {
                    sender.sendMessage(Component.text("arena 不存在", NamedTextColor.RED)); return
                }
                if (arena.mapFlow == null) {
                    sender.sendMessage(Component.text("${arena.name} 未配置 map-flow", NamedTextColor.YELLOW))
                    return
                }
                root.arenaRepository.save(arena.copy(mapFlow = null))
                sender.sendMessage(Component.text("已移除 ${arena.name} 的 map-flow", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(
                Component.text("用法: /zr2 mapflow list|info|init|set|stage|finish|remove", NamedTextColor.RED),
            )
        }
    }

    private fun handleMapFlowSet(sender: CommandSender, args: List<String>) {
        if (args.size < 3) {
            sender.sendMessage(Component.text("用法: /zr2 mapflow set <arena> <key> <value>", NamedTextColor.RED))
            return
        }
        val arena = root.arenaRepository.byName(args[0]) ?: run {
            sender.sendMessage(Component.text("arena 不存在", NamedTextColor.RED)); return
        }
        val flow = arena.mapFlow ?: run {
            sender.sendMessage(Component.text("${arena.name} 未配置 map-flow，先用 /zr2 mapflow init", NamedTextColor.YELLOW)); return
        }
        val key = args[1].lowercase()
        val value = args[2]
        val updated: MapFlowDefinition = when (key) {
            "min-players" -> flow.copy(minPlayers = value.toIntOrNull() ?: run { badNumber(sender); return })
            "start-delay-seconds" -> flow.copy(startDelaySeconds = value.toIntOrNull() ?: run { badNumber(sender); return })
            "max-duration-seconds" -> flow.copy(maxDurationSeconds = value.toIntOrNull() ?: run { badNumber(sender); return })
            "mother-release-delay-seconds" -> flow.copy(motherReleaseDelaySeconds = value.toIntOrNull() ?: run { badNumber(sender); return })
            "reward-coins-human" -> flow.copy(rewardCoinsHuman = value.toIntOrNull() ?: run { badNumber(sender); return })
            "reward-xp-human" -> flow.copy(rewardXpHuman = value.toIntOrNull() ?: run { badNumber(sender); return })
            "reward-coins-zombie" -> flow.copy(rewardCoinsZombie = value.toIntOrNull() ?: run { badNumber(sender); return })
            "reward-xp-zombie" -> flow.copy(rewardXpZombie = value.toIntOrNull() ?: run { badNumber(sender); return })
            "starter-weapon" -> flow.copy(starterWeaponId = value.ifBlank { null })
            else -> {
                sender.sendMessage(Component.text("未知 key: $key", NamedTextColor.RED))
                return
            }
        }
        root.arenaRepository.save(arena.copy(mapFlow = updated))
        sender.sendMessage(Component.text("已更新 ${arena.name} map-flow $key = $value", NamedTextColor.GREEN))
    }

    private fun handleMapFlowStage(sender: CommandSender, args: List<String>) {
        if (args.size < 2) {
            sender.sendMessage(Component.text("用法: /zr2 mapflow stage add|set|next|remove <arena> <id> [doorNumbers] [nextId]", NamedTextColor.RED))
            return
        }
        val arena = root.arenaRepository.byName(args[1]) ?: run {
            sender.sendMessage(Component.text("arena 不存在", NamedTextColor.RED)); return
        }
        val flow = arena.mapFlow ?: run {
            sender.sendMessage(Component.text("${arena.name} 未配置 map-flow，先用 /zr2 mapflow init", NamedTextColor.YELLOW)); return
        }
        when (args[0].lowercase()) {
            "add" -> {
                if (args.size < 4) {
                    sender.sendMessage(Component.text("用法: /zr2 mapflow stage add <arena> <id> <doorNumbers...> [label]", NamedTextColor.RED))
                    return
                }
                val id = args[2]
                val doors = args.drop(3).takeWhile { it.toIntOrNull() != null }.map { it.toInt() }
                val label = args.drop(3 + doors.size).joinToString(" ").ifBlank { id }
                if (doors.isEmpty()) { badNumber(sender); return }
                if (flow.stages.any { it.id == id }) {
                    sender.sendMessage(Component.text("阶段 $id 已存在", NamedTextColor.RED))
                    return
                }
                val updated = flow.copy(stages = flow.stages + MapFlowStage(id, label, doors))
                root.arenaRepository.save(arena.copy(mapFlow = updated))
                sender.sendMessage(Component.text("已添加阶段 $id（${doors.joinToString("/")}）", NamedTextColor.GREEN))
            }
            "set" -> {
                if (args.size < 4) {
                    sender.sendMessage(Component.text("用法: /zr2 mapflow stage set <arena> <id> <doorNumbers...>", NamedTextColor.RED))
                    return
                }
                val id = args[2]
                val existing = flow.stages.firstOrNull { it.id == id } ?: run {
                    sender.sendMessage(Component.text("阶段 $id 不存在", NamedTextColor.RED)); return
                }
                val doors = args.drop(3).takeWhile { it.toIntOrNull() != null }.map { it.toInt() }
                if (doors.isEmpty()) { badNumber(sender); return }
                val updated = flow.copy(
                    stages = flow.stages.map { if (it.id == id) existing.copy(doorNumbers = doors) else it },
                )
                root.arenaRepository.save(arena.copy(mapFlow = updated))
                sender.sendMessage(Component.text("已更新阶段 $id 门号=${doors.joinToString("/")}", NamedTextColor.GREEN))
            }
            "next" -> {
                if (args.size < 3) {
                    sender.sendMessage(Component.text("用法: /zr2 mapflow stage next <arena> <id> <nextId|none>", NamedTextColor.RED))
                    return
                }
                val id = args[2]
                val existing = flow.stages.firstOrNull { it.id == id } ?: run {
                    sender.sendMessage(Component.text("阶段 $id 不存在", NamedTextColor.RED)); return
                }
                val next = args.getOrNull(3)?.takeIf { it.lowercase() != "none" }
                if (next != null && flow.stages.none { it.id == next }) {
                    sender.sendMessage(Component.text("下一阶段 $next 不存在", NamedTextColor.RED))
                    return
                }
                val updated = flow.copy(
                    stages = flow.stages.map { if (it.id == id) existing.copy(nextStageId = next) else it },
                )
                root.arenaRepository.save(arena.copy(mapFlow = updated))
                sender.sendMessage(Component.text("已设置阶段 $id 的下一阶段=${next ?: "终点"}", NamedTextColor.GREEN))
            }
            "remove" -> {
                val id = args.getOrNull(2) ?: run {
                    sender.sendMessage(Component.text("用法: /zr2 mapflow stage remove <arena> <id>", NamedTextColor.RED)); return
                }
                if (flow.stages.size <= 1) {
                    sender.sendMessage(Component.text("至少保留一个阶段", NamedTextColor.RED))
                    return
                }
                val updated = flow.copy(stages = flow.stages.filterNot { it.id == id })
                if (updated.stages.size == flow.stages.size) {
                    sender.sendMessage(Component.text("阶段 $id 不存在", NamedTextColor.RED))
                    return
                }
                root.arenaRepository.save(arena.copy(mapFlow = updated))
                sender.sendMessage(Component.text("已移除阶段 $id", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("未知 stage 子命令", NamedTextColor.RED))
        }
    }

    private fun handleMapFlowFinish(sender: CommandSender, args: List<String>) {
        if (args.size < 2) {
            sender.sendMessage(Component.text("用法: /zr2 mapflow finish <arena> door <门号> | /zr2 mapflow finish <arena> extraction", NamedTextColor.RED))
            return
        }
        val arena = root.arenaRepository.byName(args[0]) ?: run {
            sender.sendMessage(Component.text("arena 不存在", NamedTextColor.RED)); return
        }
        val flow = arena.mapFlow ?: run {
            sender.sendMessage(Component.text("${arena.name} 未配置 map-flow，先用 /zr2 mapflow init", NamedTextColor.YELLOW)); return
        }
        val finish = when (args.getOrNull(1)?.lowercase()) {
            "door" -> {
                val n = args.getOrNull(2)?.toIntOrNull() ?: run { badNumber(sender); return }
                MapFlowFinish(FinishType.DOOR, n)
            }
            "extraction" -> MapFlowFinish(FinishType.EXTRACTION, null)
            else -> {
                sender.sendMessage(Component.text("finish 类型必须是 door 或 extraction", NamedTextColor.RED))
                return
            }
        }
        root.arenaRepository.save(arena.copy(mapFlow = flow.copy(finish = finish)))
        sender.sendMessage(Component.text("已设置 ${arena.name} 终点=${finish.type.name} door=${finish.doorNumber ?: "-"}", NamedTextColor.GREEN))
    }

    // ---------- v1 migration ----------

    private fun handleV1(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "migrate" -> {
                val report = root.v1MigrationService.migrate()
                sender.sendMessage(Component.text("===== v1 配置迁移报告 =====", NamedTextColor.GREEN))
                val worldName = report.world ?: "-"
                sender.sendMessage(Component.text("world=$worldName doors=${report.doorsMigrated} buttons=${report.buttonsMigrated} respawns=${report.respawnsMigrated} | snapshots imported=${report.snapshotsImported} attached=${report.snapshotsAttached}", NamedTextColor.GREEN))
                report.skipped.forEach { message ->
                    sender.sendMessage(Component.text("跳过: $message", NamedTextColor.YELLOW))
                }
            }
            "migrate-data" -> {
                val overwrite = args.any { it.equals("--overwrite", true) }
                val report = root.v1MigrationService.migrateData(overwrite)
                sender.sendMessage(Component.text("===== v1 数据迁移报告 =====", NamedTextColor.GREEN))
                sender.sendMessage(
                    Component.text(
                        "players=${report.playersMigrated} skipped=${report.playersSkipped} coins=${report.totalCoins} titles=${report.titlesImported}",
                        NamedTextColor.GREEN,
                    ),
                )
                report.skipped.forEach { message ->
                    sender.sendMessage(Component.text("跳过: $message", NamedTextColor.YELLOW))
                }
            }
            else -> sender.sendMessage(Component.text("用法: /zr2 v1 migrate | /zr2 v1 migrate-data [--overwrite]", NamedTextColor.RED))
        }
    }

    // ---------- tab ----------

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("help", "version", "reload", "arena", "door", "button", "respawn", "game", "weapon", "profile", "coins", "xp", "level", "reset", "title", "menu", "task", "mapflow", "v1", "lobby", "debug", "postool")
                .filter { it.startsWith(args[0].lowercase()) }
        }
        return when (args[0].lowercase()) {
            "arena" -> when (args.size) {
                2 -> listOf("list", "info", "create", "remove").filter { it.startsWith(args[1].lowercase()) }
                3 -> if (args[1].equals("info", true) || args[1].equals("remove", true)) arenaNames(args[2]) else emptyList()
                else -> emptyList()
            }
            "door" -> when (args.size) {
                2 -> listOf("list", "info", "test", "trigger", "add", "remove", "edit", "reset", "behavior").filter { it.startsWith(args[1].lowercase()) }
                3 -> when (args[1].lowercase()) {
                    "info", "test", "remove", "edit" -> root.arenaRepository.all().flatMap { it.doors }.map { it.id }.filter { it.startsWith(args[2], true) }
                    "trigger" -> root.doorService.doorsInWorld((sender as? Player)?.world?.name ?: defaultWorld).mapNotNull { it.number?.toString() }.filter { it.startsWith(args[2]) }
                    "behavior" -> listOf("info", "set", "remove").filter { it.startsWith(args[2].lowercase()) }
                    else -> emptyList()
                }
                4 -> if (args[1].equals("behavior", true)) root.arenaRepository.all().flatMap { it.doors }.map { it.id }.filter { it.startsWith(args[3], true) } else emptyList()
                else -> emptyList()
            }
            "button" -> when (args.size) {
                2 -> listOf("add", "remove", "list").filter { it.startsWith(args[1].lowercase()) }
                3 -> if (args[1].equals("remove", true)) root.arenaRepository.all().flatMap { it.buttons }.map { it.id }.filter { it.startsWith(args[2], true) } else emptyList()
                else -> emptyList()
            }
            "respawn" -> when (args.size) {
                2 -> listOf("add", "remove", "list").filter { it.startsWith(args[1].lowercase()) }
                3 -> if (args[1].equals("remove", true)) root.arenaRepository.all().flatMap { it.respawns }.map { it.id }.filter { it.startsWith(args[2], true) } else emptyList()
                else -> emptyList()
            }
            "game" -> when (args.size) {
                2 -> listOf("list", "status", "start", "end", "reset").filter { it.startsWith(args[1].lowercase()) }
                3 -> when (args[1].lowercase()) {
                    "status", "start", "end", "reset" -> root.gameFlow.gameWorlds().filter { it.startsWith(args[2], true) }
                    else -> emptyList()
                }
                4 -> if (args[1].equals("end", true)) listOf("human", "zombie").filter { it.startsWith(args[3].lowercase()) } else emptyList()
                else -> emptyList()
            }
            "weapon" -> when (args.size) {
                2 -> listOf("list", "info", "add", "remove", "give", "random", "select", "unselect").filter { it.startsWith(args[1].lowercase()) }
                3 -> when (args[1].lowercase()) {
                    "info", "remove", "give", "select" -> root.weaponService.all().map { it.id }.filter { it.startsWith(args[2], true) }
                    "random" -> listOf("gun", "melee", "special").filter { it.startsWith(args[2].lowercase()) }
                    else -> emptyList()
                }
                4 -> if (args[1].equals("add", true)) listOf("gun", "melee", "special").filter { it.startsWith(args[3].lowercase()) } else emptyList()
                else -> emptyList()
            }
            "coins" -> when (args.size) {
                2 -> listOf("add", "give", "remove", "set", "get", "spend", "transfer", "top").filter { it.startsWith(args[1].lowercase()) }
                3 -> if (args[1] in listOf("give", "remove", "set", "get", "transfer")) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], true) } else emptyList()
                else -> emptyList()
            }
            "title" -> when (args.size) {
                2 -> listOf("set", "clear").filter { it.startsWith(args[1].lowercase()) }
                else -> emptyList()
            }
            "xp" -> when (args.size) {
                2 -> listOf("add", "set").filter { it.startsWith(args[1].lowercase()) }
                3 -> if (args[1] in listOf("add", "set")) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], true) } else emptyList()
                else -> emptyList()
            }
            "level" -> if (args.size == 2) listOf("set").filter { it.startsWith(args[1].lowercase()) } else if (args.size == 3 && args[1].equals("set", true)) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], true) } else emptyList()
            "reset" -> if (args.size == 2) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) } else emptyList()
            "menu" -> if (args.size == 2) listOf("profile", "shop", "tasks", "titles").filter { it.startsWith(args[1].lowercase()) } else emptyList()
            "task" -> if (args.size == 2) listOf("list", "claim").filter { it.startsWith(args[1].lowercase()) } else if (args.size == 3 && args[1].equals("claim", true)) root.taskService.allTasks().map { it.id }.filter { it.startsWith(args[2], true) } else emptyList()
            "mapflow" -> when (args.size) {
                2 -> listOf("list", "info", "init", "set", "stage", "finish", "remove").filter { it.startsWith(args[1].lowercase()) }
                3 -> if (args[1] in listOf("info", "init", "set", "stage", "finish", "remove")) arenaNames(args[2]) else emptyList()
                4 -> if (args[1].equals("set", true)) listOf("min-players", "start-delay-seconds", "max-duration-seconds", "mother-release-delay-seconds", "reward-coins-human", "reward-xp-human", "reward-coins-zombie", "reward-xp-zombie", "starter-weapon").filter { it.startsWith(args[3].lowercase()) } else if (args[1].equals("finish", true)) listOf("door", "extraction").filter { it.startsWith(args[3].lowercase()) } else emptyList()
                5 -> if (args[1].equals("stage", true)) listOf("add", "set", "next", "remove").filter { it.startsWith(args[3].lowercase()) } else emptyList()
                else -> emptyList()
            }
            "v1" -> if (args.size == 2 || (args.size == 3 && args[2].equals("--overwrite", true))) listOf("migrate", "migrate-data").filter { it.startsWith(args[1].lowercase()) } else emptyList()
            "profile" -> emptyList()
            else -> emptyList()
        }
    }

    private fun arenaNames(prefix: String): List<String> =
        root.arenaRepository.all().map { it.name }.filter { it.startsWith(prefix, true) }

    /** 解析 arena：显式 --arena 优先；省略时按玩家当前世界智能推断。 */
    private fun resolveArena(sender: CommandSender, arenaName: String?): ArenaDefinition? {
        if (arenaName != null) return root.arenaRepository.byName(arenaName)
        val player = sender as? Player ?: return null
        return root.arenaRepository.byWorld(player.world.name).firstOrNull()
    }

    private fun noPermission(sender: CommandSender) {
        sender.sendMessage(Component.text("你没有权限执行此命令", NamedTextColor.RED))
    }

    companion object {
        /** 门区域最大方块数，防止同步扫描超大区域卡服。 */
        private const val MAX_DOOR_BLOCKS = 10_000L
    }
}
