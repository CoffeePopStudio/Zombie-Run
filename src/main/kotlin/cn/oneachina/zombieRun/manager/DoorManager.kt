package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Door
import cn.oneachina.zombieRun.model.SpecialDoorBehavior
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DoorManager(private val plugin: ZombieRun) {

    // ---- DoorSession: 封装一扇门的开→关生命周期 ----
    private class DoorSession(
        val door: Door,
        var opentime: Double,
        var closetime: Double,
        var forbiddenUntil: Long
    )

    private val doors: ConcurrentHashMap<String, Door> = ConcurrentHashMap()

    // ---- 多 session：每扇门独立生命周期 ----
    private val activeSessions = ConcurrentHashMap<Int, DoorSession>()

    var endtime: Double = -1.0
        private set
    var doorclose: Int = 0
        private set

    private val doorTasks = CopyOnWriteArrayList<ScheduledTask>()
    private val transferTasks = ConcurrentHashMap<Player, ScheduledTask>()

    // ---- 门组索引：group -> list of doors ----
    private val doorGroups = ConcurrentHashMap<String, List<Door>>()

    // ==================== 数据加载 ====================

    fun loadDoors() {
        doors.clear()
        doorGroups.clear()
        plugin.configManager.loadDoors().forEach {
            doors[it.name] = it
            if (!it.group.isNullOrBlank()) {
                doorGroups.merge(it.group, listOf(it)) { old, _ -> old + it }
            }
        }
        plugin.doorZoneManager.initialize(doors.values)
        plugin.logger.info("已加载 ${doors.size} 扇门，${doorGroups.size} 个门组")
    }

    fun getDoorByNumber(number: Int): Door? {
        return doors.values.find { it.doorNumber == number }
    }

    // ==================== 即时开门（无倒计时） ====================

    fun openDoorImmediately(doorNumber: Int, broadcast: Boolean = true) {
        val door = getDoorByNumber(doorNumber) ?: return
        openDoor(door, broadcast)
    }

    fun openDoorImmediatelyByName(name: String, broadcast: Boolean = true) {
        val door = doors[name] ?: return
        openDoor(door, broadcast)
    }

    // ==================== 按钮触发 ====================

    fun triggerDoor(doorNumber: Int, player: Player? = null) {
        triggerDoor(doorNumber, player, guardActive = true)
    }

    fun triggerDoor(doorNumber: Int, player: Player?, guardActive: Boolean) {
        val door = getDoorByNumber(doorNumber) ?: return

        if (door.mode == Door.DoorMode.START) {
            openDoorImmediately(doorNumber)
            player?.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a起始门已开启！"))
            return
        }

        if (door.mode == Door.DoorMode.PLAYER || door.mode == Door.DoorMode.ZOMBIE) {
            player?.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c此门不能通过按钮开启！"))
            return
        }

        // 门组联动：找出同组所有门
        val doorsToTrigger = if (!door.group.isNullOrBlank()) {
            doorGroups[door.group] ?: listOf(door)
        } else {
            listOf(door)
        }

        if (guardActive) {
            // 守卫检查：任一目标门有活跃 session 则阻止
            val blocking = doorsToTrigger.firstNotNullOfOrNull { dn ->
                activeSessions[dn.doorNumber]?.let { s ->
                    if (System.currentTimeMillis() < s.forbiddenUntil) "你急啥急？！"
                    else if (s.opentime >= 0 || s.closetime >= 0) "你需要等上一道门关闭才可以开这道门！"
                    else null
                }
            }
            if (blocking != null) {
                player?.sendMessage(Component.text(blocking, NamedTextColor.RED))
                return
            }
        }

        // 为每扇门创建独立 session，同步开/关
        val now = System.currentTimeMillis()
        doorsToTrigger.forEach { d ->
            val session = DoorSession(
                door = d,
                opentime = d.delay.toDouble(),
                closetime = -1.0,
                forbiddenUntil = now + 3000L  // 触发时即设冷却
            )
            activeSessions[d.doorNumber] = session

            plugin.buttonManager.getButtonByDoorNumber(d.doorNumber)?.let {
                plugin.buttonManager.setButtonLit(it)
            }
        }

        val doorNums = doorsToTrigger.map { it.doorNumber }.joinToString(", ")
        val playerName = player?.name ?: "控制台"
        Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§b$playerName §a开启了 $doorNums 号大门！"))

        // 同步启动所有门的开门倒计时
        doorsToTrigger.forEach { d ->
            startOpenCountdown(activeSessions[d.doorNumber]!!)
        }
    }

    // ==================== 开门倒计时 ====================

    private fun startOpenCountdown(session: DoorSession) {
        val door = session.door
        val lastDisplay = intArrayOf(-1)
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                session.opentime = -1.0
                schedTask.cancel()
                return@runAtFixedRate
            }

            if (session.opentime > 0) {
                val currentDisplay = session.opentime.toInt()
                if (currentDisplay != lastDisplay[0]) {
                    val title = Title.title(
                        Component.empty(),
                        Component.text()
                            .append(Component.text("${door.doorNumber} 号大门即将开启于 ", NamedTextColor.GREEN))
                            .append(Component.text(currentDisplay.toString(), NamedTextColor.LIGHT_PURPLE))
                            .append(Component.text(" ……", NamedTextColor.GREEN))
                            .build(),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                    )
                    Bukkit.getOnlinePlayers().forEach { player ->
                        player.showTitle(title)
                        player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.2f, 2f)
                    }
                    lastDisplay[0] = currentDisplay
                }
                session.opentime -= 1.0
            } else {
                openDoor(door, true, session)
                session.opentime = -1.0
                schedTask.cancel()
            }
        }, 1L, 20L)
        doorTasks.add(task)
    }

    // ==================== 开门 ====================

    private fun openDoor(door: Door, broadcast: Boolean, session: DoorSession? = null) {
        val doorNum = door.doorNumber
        val world = Bukkit.getWorlds().first()
        door.open(world)
        val center = door.getCenterLocation(world)
        Bukkit.getRegionScheduler().execute(plugin, center) {
            door.openBlocks(world)
        }

        if (broadcast) {
            val soundLoc = Bukkit.getOnlinePlayers().firstOrNull()?.location ?: world.spawnLocation
            world.playSound(soundLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 2f)
            world.playSound(soundLoc, Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.5f)
            world.playSound(soundLoc, Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 0.5f)
            world.playSound(soundLoc, Sound.BLOCK_BELL_USE, 1f, 0.5f)

            Bukkit.getOnlinePlayers().forEach { player ->
                player.showTitle(Title.title(
                    Component.text("$doorNum 号大门开启", NamedTextColor.GREEN),
                    Component.text("请立即前往下一区域", NamedTextColor.GREEN)
                ))
            }
        }

        if (doorNum < 9 && door.mode != Door.DoorMode.START &&
            door.mode != Door.DoorMode.PLAYER && door.mode != Door.DoorMode.ZOMBIE) {
            val s = session ?: return
            s.closetime = door.closeTime.toDouble()
            startCloseCountdown(s)
        }
    }

    // ==================== 关门倒计时 ====================

    private fun startCloseCountdown(session: DoorSession) {
        val door = session.door
        val doorNum = door.doorNumber
        val lastDisplay = doubleArrayOf(-1.0)
        val sb = door.specialBehavior
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                session.closetime = -1.0
                schedTask.cancel()
                return@runAtFixedRate
            }

            if (session.closetime > 0) {
                val currentDisplay = if (session.closetime % 1 == 0.0) session.closetime.toInt().toDouble() else Math.floor(session.closetime * 10) / 10

                val humansBehind = Bukkit.getOnlinePlayers().count { player ->
                    val team = plugin.gameManager.getPlayerTeam(player)
                    if (team != GameManager.Team.HUMAN) return@count false
                    val room = plugin.gameManager.getPlayerRoom(player)
                    room < doorNum
                }

                if (session.closetime > 3.1 && humansBehind == 0) {
                    val enteredMsg = when (sb) {
                        is SpecialDoorBehavior.Subway -> "所有人类都已上车，${sb.lineName}即将发车……"
                        else -> "所有人类都已进入，大门即将关闭……"
                    }
                    Bukkit.broadcast(Component.text(enteredMsg, NamedTextColor.GREEN))
                    session.closetime = 3.1
                }

                if (currentDisplay != lastDisplay[0]) {
                    val displayStr = if (currentDisplay % 1 == 0.0) currentDisplay.toInt().toString() else String.format("%.1f", currentDisplay)

                    val (behindMsg, aheadMsg) = when (sb) {
                        is SpecialDoorBehavior.Subway -> Pair(
                            "${sb.lineName}列车将在 {sec} 秒后发车，请立即上车！",
                            "${sb.lineName}列车将在 {sec} 秒后发车……"
                        )
                        else -> Pair(
                            "$doorNum 号大门即将关闭，请立即进入！",
                            "$doorNum 号大门将在 {sec} 秒后关闭……"
                        )
                    }

                    Bukkit.getOnlinePlayers().forEach { player ->
                        val room = plugin.gameManager.getPlayerRoom(player)
                        val title = if (room < doorNum) {
                            Title.title(
                                Component.text(displayStr, NamedTextColor.RED),
                                Component.text(behindMsg.replace("{sec}", displayStr), NamedTextColor.GOLD),
                                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                            )
                        } else {
                            Title.title(
                                Component.empty(),
                                Component.text()
                                    .append(Component.text(aheadMsg.replace("{sec}", displayStr), NamedTextColor.GRAY))
                                    .build(),
                                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                            )
                        }
                        player.showTitle(title)
                    }
                    lastDisplay[0] = currentDisplay
                }

                when (session.closetime.toInt()) {
                    5, 3, 2, 1 -> {
                        Bukkit.getOnlinePlayers().forEach { player ->
                            player.playSound(player.location, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.1f, 2f)
                        }
                    }
                }

                session.closetime -= 0.1
            } else {
                closeDoor(door)
                session.closetime = -1.0
                schedTask.cancel()
            }
        }, 1L, 2L)
        doorTasks.add(task)
    }

    // ==================== 关门 ====================

    private fun closeDoor(door: Door) {
        val doorNum = door.doorNumber
        val world = Bukkit.getWorlds().first()
        door.close(world)
        val center = door.getCenterLocation(world)
        Bukkit.getRegionScheduler().execute(plugin, center) {
            door.closeBlocks(world)
        }

        doorclose = doorNum

        val soundLoc = Bukkit.getOnlinePlayers().firstOrNull()?.location ?: world.spawnLocation
        world.playSound(soundLoc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.5f)
        world.playSound(soundLoc, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f)
        world.playSound(soundLoc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1f, 1f)

        val isGroupSpecialDoor = door.group != null && door.hasSpecialBehavior()

        if (isGroupSpecialDoor) {
            // 组门 + 特殊行为：不单独做 room 判定，统一交给 handleSpecialDoorClose
            Bukkit.getOnlinePlayers().forEach { player ->
                if (door.containsLocation(player.location)) {
                    // 卡在门方块里 — 特殊行为触发时会一并处理
                }
            }
        } else {
            val hasSpecial = door.hasSpecialBehavior()
            Bukkit.getOnlinePlayers().forEach { player ->
                val room = plugin.gameManager.getPlayerRoom(player)
                val isInsideDoor = door.containsLocation(player.location)

                if (isInsideDoor) {
                    if (!hasSpecial) {
                        plugin.respawnManager.teleportPlayerByDoorClose(player, doorNum)
                    }
                } else if (room < doorNum) {
                    startTransferCountdown(player, doorNum)
                }
            }
        }

        // 门组：仅当同组门全部关闭时才触发特殊行为
        if (door.hasSpecialBehavior() && allGroupDoorsClosed(door)) {
            handleSpecialDoorClose(door)
        }

        // session 生命周期结束
        activeSessions.remove(doorNum)
    }

    /** 检查组内所有门是否均已关闭 */
    private fun allGroupDoorsClosed(door: Door): Boolean {
        val group = door.group ?: return true
        val groupDoors = doorGroups[group] ?: return true
        return groupDoors.none { it.isOpen }
    }

    // ==================== 落后传送倒计时 ====================

    private fun startTransferCountdown(player: Player, doorNumber: Int) {
        // 取消旧任务避免泄露
        transferTasks.remove(player)?.cancel()

        var countdown = plugin.balanceConfig.transferCountdownSec
        val taskId = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (countdown > 0) {
                player.showTitle(Title.title(
                    LegacyComponentSerializer.legacySection().deserialize("§c$countdown"),
                    LegacyComponentSerializer.legacySection().deserialize("§4大门已关闭，请等待传送")
                ))
                countdown--
            } else {
                plugin.respawnManager.teleportPlayerByDoorClose(player, doorNumber)
                transferTasks.remove(player)
                schedTask.cancel()
            }
        }, 1L, 20L)
        transferTasks[player] = taskId
    }

    // ==================== 特殊行为 ====================

    fun getDoorByName(name: String): Door? = doors[name]

    private fun handleSpecialDoorClose(door: Door) {
        val behavior = door.specialBehavior ?: return
        val world = Bukkit.getWorlds().first()
        val groupDoors = if (!door.group.isNullOrBlank()) doorGroups[door.group] ?: listOf(door) else listOf(door)
        val groupDoorNumbers = groupDoors.map { it.doorNumber }.toSet()
        // 收集"已上车"的玩家：在任一扇门区域内，或已经通过同组任一扇门（playerRoom 被更新）
        val players = Bukkit.getOnlinePlayers().filter { player ->
            groupDoors.any { it.containsLocation(player.location) } ||
            plugin.gameManager.getPlayerRoom(player) in groupDoorNumbers
        }
        if (players.isEmpty()) return

        val task = behavior.execute(
            SpecialDoorBehavior.ExecuteContext(
                plugin = plugin,
                door = door,
                players = players,
                world = world,
                doorTasks = doorTasks
            )
        )
        if (task != null) {
            doorTasks.add(task)
        }
    }

    // ==================== 直升机撤离 ====================

    fun startHelicopterEscape() {
        if (endtime >= 0) return
        endtime = plugin.balanceConfig.helicopterCountdownSec.toDouble()
        Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§c\n直升机已启动！\n人类将在 30 秒后撤离！\n"))

        val lastDisplay = doubleArrayOf(-1.0)
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                endtime = -1.0
                schedTask.cancel()
                return@runAtFixedRate
            }

            if (endtime > 0) {
                val currentDisplay = if (endtime % 1 == 0.0) endtime.toInt().toDouble() else Math.floor(endtime * 10) / 10

                if (currentDisplay != lastDisplay[0]) {
                    val displayStr = if (currentDisplay % 1 == 0.0) currentDisplay.toInt().toString() else String.format("%.1f", currentDisplay)
                    Bukkit.getOnlinePlayers().forEach { player ->
                        player.showTitle(Title.title(
                            Component.empty(),
                            LegacyComponentSerializer.legacySection().deserialize("§e游戏将于 §d$displayStr §e秒后结束！")
                        ))
                        player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.2f, 2f)
                    }
                    lastDisplay[0] = currentDisplay
                }
                endtime -= 0.1
            } else {
                Bukkit.getOnlinePlayers().forEach { player ->
                    player.showTitle(Title.title(
                        LegacyComponentSerializer.legacySection().deserialize("§c游戏结束"),
                        LegacyComponentSerializer.legacySection().deserialize("§b人类 §a成功逃离！")
                    ))
                    if (plugin.gameManager.getPlayerTeam(player) == GameManager.Team.HUMAN) {
                        plugin.coinManager.addCoins(player.uniqueId, 200)
                        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§6+ 200 硬币！ (作为人类活到最后)"))
                    }
                }
                endHelicopterEscape()
                schedTask.cancel()
            }
        }, 1L, 2L)
        doorTasks.add(task)
    }

    private fun endHelicopterEscape() {
        plugin.gameManager.endGame(GameManager.Team.HUMAN)
    }

    // ==================== 重置 ====================

    fun reset() {
        doorTasks.forEach { it.cancel() }
        doorTasks.clear()
        transferTasks.values.forEach { it.cancel() }
        transferTasks.clear()

        plugin.buttonManager.resetAllButtons()

        val worlds = Bukkit.getWorlds()
        if (worlds.isNotEmpty()) {
            val world = worlds.first()
            doors.values.forEach { door ->
                if (door.isOpen) {
                    door.close(world)
                    val center = door.getCenterLocation(world)
                    Bukkit.getRegionScheduler().execute(plugin, center) {
                        door.closeBlocks(world)
                    }
                }
            }
        }

        activeSessions.clear()
        endtime = -1.0
        doorclose = 0
    }

    // ==================== CRUD ====================

    fun getAllDoors(): Collection<Door> = doors.values

    fun addDoor(door: Door) {
        doors[door.name] = door
        plugin.doorZoneManager.addDoor(door)
    }

    fun removeDoor(name: String) {
        val door = doors[name]
        if (door != null) {
            plugin.doorZoneManager.removeDoor(door)
        }
        doors.remove(name)
    }

    fun resetDoor(name: String) {
        val door = doors[name] ?: return
        val world = Bukkit.getWorlds().first()
        door.close(world)
        val center = door.getCenterLocation(world)
        Bukkit.getRegionScheduler().execute(plugin, center) {
            door.closeBlocks(world)
        }
    }

    fun onPlayerEnterDoor(player: Player, doorNumber: Int) {
        val door = getDoorByNumber(doorNumber)
        if (door != null) {
            player.sendMessage("你进入了 ${doorNumber} 号门区域")
        }
    }

    fun onPlayerLeaveDoor(player: Player, doorNumber: Int) {
        // 当前无需额外处理，保留入口供未来扩展
    }
}
