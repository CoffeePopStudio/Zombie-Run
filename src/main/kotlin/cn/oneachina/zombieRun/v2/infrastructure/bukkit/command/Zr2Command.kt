package cn.oneachina.zombierun.v2.infrastructure.bukkit.command

import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonMode
import cn.oneachina.zombierun.v2.domain.arena.RespawnDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnType
import cn.oneachina.zombierun.v2.domain.door.BlockRegion
import cn.oneachina.zombierun.v2.domain.door.DoorDefinition
import cn.oneachina.zombierun.v2.domain.door.DoorMode
import cn.oneachina.zombierun.v2.domain.door.Portal
import cn.oneachina.zombierun.v2.domain.door.PortalAxis
import cn.oneachina.zombierun.v2.domain.door.PortalFront
import cn.oneachina.zombierun.v2.plugin.V2CompositionRoot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class Zr2Command(
    private val root: V2CompositionRoot,
    private val defaultWorld: String,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            help(sender)
            return true
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
            else -> {
                sender.sendMessage(Component.text("未知子命令：${args[0]}，输入 /zr2 help 查看帮助", NamedTextColor.RED))
            }
        }
        return true
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
        root.doorService.reload()
        sender.sendMessage(Component.text("已重载 v2 arena 配置", NamedTextColor.GREEN))
    }

    private fun help(sender: CommandSender) {
        sender.sendMessage(Component.text("===== ZombieRun v2 =====", NamedTextColor.GREEN))
        sender.sendMessage(Component.text("/zr2 version", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 arena list | info <名称> | create <名称> [世界] | remove <名称>", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 door list [世界] | info <id> | test <id> | trigger <门号>", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 door add --arena <名称> <x1> <y1> <z1> <x2> <y2> <z2> <axis> <front> [--number N] [--group G] [--open N] [--close N]", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 button add --arena <名称> <x> <y> <z> normal <门号>", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 respawn add --arena <名称> <type> <x> <y> <z> [door-number] [yaw] [pitch]", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 game list | status <世界> | start <世界> | end <世界> <human|zombie> | reset <世界>", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 reload", NamedTextColor.YELLOW))
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
            else -> sender.sendMessage(Component.text("未知子命令", NamedTextColor.RED))
        }
    }

    private fun addDoor(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        val (options, positional) = parseArgs(args)
        val arenaName = options["arena"]
        val arena = arenaName?.let { root.arenaRepository.byName(it) }
        if (arena == null) {
            sender.sendMessage(Component.text("用法: /zr2 door add --arena <名称> <x1> <y1> <z1> <x2> <y2> <z2> <axis> <front>", NamedTextColor.RED))
            return
        }
        if (positional.size < 8) {
            sender.sendMessage(Component.text("需要 6 个坐标 + axis + front", NamedTextColor.RED))
            return
        }
        val coords = positional.take(6).map { it.toIntOrNull() }
        if (coords.any { it == null }) {
            sender.sendMessage(Component.text("坐标必须是整数", NamedTextColor.RED))
            return
        }
        val axis = PortalAxis.entries.firstOrNull { it.name.equals(positional[6], ignoreCase = true) }
        val front = PortalFront.entries.firstOrNull { it.name.equals(positional[7], ignoreCase = true) }
        if (axis == null || front == null) {
            sender.sendMessage(Component.text("axis 必须是 x|z，front 必须是 positive|negative", NamedTextColor.RED))
            return
        }

        val minX = minOf(coords[0]!!, coords[3]!!)
        val minY = minOf(coords[1]!!, coords[4]!!)
        val minZ = minOf(coords[2]!!, coords[5]!!)
        val maxX = maxOf(coords[0]!!, coords[3]!!)
        val maxY = maxOf(coords[1]!!, coords[4]!!)
        val maxZ = maxOf(coords[2]!!, coords[5]!!)
        val region = BlockRegion(minX, minY, minZ, maxX, maxY, maxZ)

        val number = options["number"]?.toIntOrNull()
            ?: (arena.doors.mapNotNull { it.number }.maxOrNull() ?: 0) + 1
        if (arena.doors.any { it.number == number }) {
            sender.sendMessage(Component.text("门号 $number 已存在", NamedTextColor.RED))
            return
        }
        val id = "door_${System.currentTimeMillis()}"
        val snapshotId = "${arena.name}_$id"
        val plane = if (axis == PortalAxis.X) (minX + maxX) / 2.0 else (minZ + maxZ) / 2.0
        val transverseMin = if (axis == PortalAxis.X) minZ.toDouble() else minX.toDouble()
        val transverseMax = if (axis == PortalAxis.X) maxZ.toDouble() else maxX.toDouble()

        val door = DoorDefinition(
            id = id,
            world = arena.world,
            number = number,
            mode = DoorMode.NORMAL,
            group = options["group"],
            openSeconds = options["open"]?.toIntOrNull() ?: 15,
            closeSeconds = options["close"]?.toIntOrNull() ?: 15,
            portal = Portal(
                axis = axis,
                front = front,
                planeCoordinate = plane,
                transverseMin = transverseMin,
                transverseMax = transverseMax,
                yMin = minY.toDouble(),
                yMax = maxY.toDouble(),
            ),
            region = region,
            snapshotId = snapshotId,
            fallbackMaterial = "STONE",
        )

        val snapshot = root.blockOps.scanRegion(arena.world, region)
        root.snapshotStore.save(snapshotId, snapshot)
        root.arenaRepository.save(arena.copy(doors = arena.doors + door))
        sender.sendMessage(Component.text("门 '$id' (#$number) 已添加，快照 ${snapshot.size} 个方块", NamedTextColor.GREEN))
    }

    // ---------- button / respawn ----------

    private fun handleButton(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        if (args.isEmpty() || args[0].lowercase() != "add") {
            sender.sendMessage(Component.text("用法: /zr2 button add --arena <名称> <x> <y> <z> normal <门号>", NamedTextColor.RED))
            return
        }
        val (options, positional) = parseArgs(args.drop(1))
        val arena = options["arena"]?.let { root.arenaRepository.byName(it) }
        if (arena == null || positional.size < 5) {
            sender.sendMessage(Component.text("用法: /zr2 button add --arena <名称> <x> <y> <z> normal <门号>", NamedTextColor.RED))
            return
        }
        val coords = positional.take(3).map { it.toIntOrNull() }
        if (coords.any { it == null }) {
            sender.sendMessage(Component.text("坐标必须是整数", NamedTextColor.RED))
            return
        }
        if (!positional[3].equals("normal", ignoreCase = true)) {
            sender.sendMessage(Component.text("v2 M1 仅支持 normal 按钮", NamedTextColor.RED))
            return
        }
        val doorNumbers = positional.drop(4).mapNotNull { it.trim().toIntOrNull() }
        if (doorNumbers.isEmpty()) {
            sender.sendMessage(Component.text("至少指定一个门号", NamedTextColor.RED))
            return
        }
        val id = "button_${System.currentTimeMillis()}"
        val button = ButtonDefinition(
            id = id,
            world = arena.world,
            x = coords[0]!!,
            y = coords[1]!!,
            z = coords[2]!!,
            mode = ButtonMode.NORMAL,
            doorNumbers = doorNumbers,
        )
        root.arenaRepository.save(arena.copy(buttons = arena.buttons + button))
        sender.sendMessage(Component.text("按钮 '$id' 已添加", NamedTextColor.GREEN))
    }

    private fun handleRespawn(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        if (args.isEmpty() || args[0].lowercase() != "add") {
            sender.sendMessage(Component.text("用法: /zr2 respawn add --arena <名称> <type> <x> <y> <z> [door-number] [yaw] [pitch]", NamedTextColor.RED))
            return
        }
        val (options, positional) = parseArgs(args.drop(1))
        val arena = options["arena"]?.let { root.arenaRepository.byName(it) }
        if (arena == null || positional.size < 4) {
            sender.sendMessage(Component.text("用法: /zr2 respawn add --arena <名称> <type> <x> <y> <z> [door-number] [yaw] [pitch]", NamedTextColor.RED))
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

    // ---------- tab ----------

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("help", "version", "reload", "arena", "door", "button", "respawn", "game")
                .filter { it.startsWith(args[0].lowercase()) }
        }
        return when (args[0].lowercase()) {
            "arena" -> when (args.size) {
                2 -> listOf("list", "info", "create", "remove").filter { it.startsWith(args[1].lowercase()) }
                3 -> if (args[1].equals("info", true) || args[1].equals("remove", true)) arenaNames(args[2]) else emptyList()
                else -> emptyList()
            }
            "door" -> when (args.size) {
                2 -> listOf("list", "info", "test", "trigger", "add").filter { it.startsWith(args[1].lowercase()) }
                3 -> when (args[1].lowercase()) {
                    "info", "test" -> root.arenaRepository.all().flatMap { it.doors }.map { it.id }.filter { it.startsWith(args[2], true) }
                    "trigger" -> root.doorService.doorsInWorld((sender as? Player)?.world?.name ?: defaultWorld).mapNotNull { it.number?.toString() }.filter { it.startsWith(args[2]) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
            "button" -> if (args.size == 2) listOf("add").filter { it.startsWith(args[1].lowercase()) } else emptyList()
            "respawn" -> if (args.size == 2) listOf("add").filter { it.startsWith(args[1].lowercase()) } else emptyList()
            "game" -> when (args.size) {
                2 -> listOf("list", "status", "start", "end", "reset").filter { it.startsWith(args[1].lowercase()) }
                3 -> when (args[1].lowercase()) {
                    "status", "start", "end", "reset" -> root.gameFlow.gameWorlds().filter { it.startsWith(args[2], true) }
                    else -> emptyList()
                }
                4 -> if (args[1].equals("end", true)) listOf("human", "zombie").filter { it.startsWith(args[3].lowercase()) } else emptyList()
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun arenaNames(prefix: String): List<String> =
        root.arenaRepository.all().map { it.name }.filter { it.startsWith(prefix, true) }

    private fun parseArgs(args: List<String>): Pair<Map<String, String>, List<String>> {
        val options = LinkedHashMap<String, String>()
        val positional = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val token = args[i]
            if (token.startsWith("--") && i + 1 < args.size) {
                options[token.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                positional.add(token)
                i++
            }
        }
        return options to positional
    }

    private fun noPermission(sender: CommandSender) {
        sender.sendMessage(Component.text("你没有权限执行此命令", NamedTextColor.RED))
    }
}
