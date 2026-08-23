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
import cn.oneachina.zombierun.v2.domain.weapon.WeaponCategory
import cn.oneachina.zombierun.v2.domain.weapon.WeaponDefinition
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
) : CommandExecutor, TabCompleter {

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
            "weapon" -> handleWeapon(sender, args.drop(1))
            "profile" -> handleProfile(sender, args.drop(1))
            "coins" -> handleCoins(sender, args.drop(1))
            "xp" -> handleXp(sender, args.drop(1))
            "title" -> handleTitle(sender, args.drop(1))
            "menu" -> handleMenu(sender, args.drop(1))
            "task" -> handleTask(sender, args.drop(1))
            "v1" -> handleV1(sender, args.drop(1))
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
        root.weaponService.reload()
        sender.sendMessage(Component.text("已重载 v2 arena + weapon 配置", NamedTextColor.GREEN))
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
        sender.sendMessage(Component.text("/zr2 weapon list | info <id> | add <id> <type> <category> <price> [name] | remove <id> | give <id> | random [category]", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 profile [玩家] | coins add|give|spend | xp add | title set|clear", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 menu profile|shop", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 task list|claim <任务id>", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/zr2 v1 migrate", NamedTextColor.YELLOW))
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
                root.weaponService.giveWeapon(player.uniqueId, id)
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
        sender.sendMessage(Component.text("门数 ${profile.doorPasses}  击杀 ${profile.zombieKills}", NamedTextColor.GREEN))
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
            "spend" -> {
                val player = sender as? Player ?: run { sender.sendMessage(Component.text("spend 需要玩家执行", NamedTextColor.RED)); return }
                val amount = args.getOrNull(1)?.toIntOrNull() ?: return badNumber(sender)
                val profile = root.playerDataService.spendCoins(player.uniqueId, amount)
                if (profile == null) sender.sendMessage(Component.text("硬币不足", NamedTextColor.RED))
                else sender.sendMessage(Component.text("已花费 $amount，剩余 ${profile.coins}", NamedTextColor.GREEN))
            }
            else -> sender.sendMessage(Component.text("未知子命令", NamedTextColor.RED))
        }
    }

    private fun handleXp(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("zombie.run.v2.admin")) { noPermission(sender); return }
        if (args.size < 2) {
            sender.sendMessage(Component.text("用法: /zr2 xp add <玩家> <数量>", NamedTextColor.RED))
            return
        }
        val target = Bukkit.getPlayerExact(args[0]) ?: run {
            sender.sendMessage(Component.text("玩家不在线：${args[0]}", NamedTextColor.RED)); return
        }
        val amount = args[1].toIntOrNull() ?: return badNumber(sender)
        val profile = root.playerDataService.addXp(target.uniqueId, amount)
        sender.sendMessage(Component.text("已给 ${target.name} $amount 经验，当前等级 ${profile.level}", NamedTextColor.GREEN))
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
            else -> sender.sendMessage(Component.text("用法: /zr2 menu profile|shop", NamedTextColor.RED))
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

    // ---------- v1 migration ----------

    private fun handleV1(sender: CommandSender, args: List<String>) {
        if (args.getOrNull(0)?.lowercase() != "migrate") {
            sender.sendMessage(Component.text("用法: /zr2 v1 migrate", NamedTextColor.RED))
            return
        }
        if (!sender.hasPermission("zombie.run.v2.admin")) {
            noPermission(sender)
            return
        }
        val report = root.v1MigrationService.migrate()
        sender.sendMessage(Component.text("===== v1 迁移报告 =====", NamedTextColor.GREEN))
        val worldName = report.world ?: "-"
        sender.sendMessage(Component.text("world=$worldName doors=${report.doorsMigrated} buttons=${report.buttonsMigrated} respawns=${report.respawnsMigrated}", NamedTextColor.GREEN))
        report.skipped.forEach { message ->
            sender.sendMessage(Component.text("跳过: $message", NamedTextColor.YELLOW))
        }
    }

    // ---------- tab ----------

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("help", "version", "reload", "arena", "door", "button", "respawn", "game", "weapon", "profile", "coins", "xp", "title", "menu", "task", "v1")
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
            "weapon" -> when (args.size) {
                2 -> listOf("list", "info", "add", "remove", "give", "random").filter { it.startsWith(args[1].lowercase()) }
                3 -> when (args[1].lowercase()) {
                    "info", "remove", "give" -> root.weaponService.all().map { it.id }.filter { it.startsWith(args[2], true) }
                    "random" -> listOf("gun", "melee", "special").filter { it.startsWith(args[2].lowercase()) }
                    else -> emptyList()
                }
                4 -> if (args[1].equals("add", true)) listOf("gun", "melee", "special").filter { it.startsWith(args[3].lowercase()) } else emptyList()
                else -> emptyList()
            }
            "coins" -> when (args.size) {
                2 -> listOf("add", "give", "spend").filter { it.startsWith(args[1].lowercase()) }
                else -> emptyList()
            }
            "title" -> when (args.size) {
                2 -> listOf("set", "clear").filter { it.startsWith(args[1].lowercase()) }
                else -> emptyList()
            }
            "xp" -> if (args.size == 2) listOf("add").filter { it.startsWith(args[1].lowercase()) } else emptyList()
            "menu" -> if (args.size == 2) listOf("profile", "shop").filter { it.startsWith(args[1].lowercase()) } else emptyList()
            "task" -> if (args.size == 2) listOf("list", "claim").filter { it.startsWith(args[1].lowercase()) } else if (args.size == 3 && args[1].equals("claim", true)) root.taskService.allTasks().map { it.id }.filter { it.startsWith(args[2], true) } else emptyList()
            "v1" -> if (args.size == 2) listOf("migrate").filter { it.startsWith(args[1].lowercase()) } else emptyList()
            "profile" -> emptyList()
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
