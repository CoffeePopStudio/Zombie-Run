package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Door
import cn.oneachina.zombieRun.model.SpecialDoorBehavior
import cn.oneachina.zombieRun.util.DebugLogger
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DoorManager(private val plugin: ZombieRun) {

    private val doors: ConcurrentHashMap<String, Door> = ConcurrentHashMap()

    // 由按钮触发线程（区域线程）写入、全局调度任务读写，跨线程需要可见性保证
    @Volatile
    private var activeSession: Session? = null

    var endtime: Double = -1.0
        private set
    var doorclose: Int = 0
        private set

    private val doorTasks = CopyOnWriteArrayList<ScheduledTask>()
    private val transferTasks = ConcurrentHashMap<Player, ScheduledTask>()

    private val doorGroups = ConcurrentHashMap<String, List<Door>>()

    // ---- Session ----

    private class Session(
        val doors: List<Door>,
        var countdown: Double,  // >0 = opening, <0 = closing (abs = seconds)
        var phase: Phase = Phase.OPENING
    ) {
        enum class Phase { OPENING, CLOSING }
        /** 开门期间穿越过门的玩家 */
        val crossedPlayers: MutableSet<UUID> = mutableSetOf()
    }

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

    fun getDoorByNumber(number: Int): Door? = doors.values.firstOrNull { it.doorNumber == number }

    fun getDoorByName(name: String): Door? = doors[name]

    fun getAllDoors(): Collection<Door> = doors.values

    fun getDoorGroups(): Map<String, List<Door>> = doorGroups

    // ==================== 按钮触发 ====================

    fun triggerDoor(doorNumber: Int, player: Player? = null) {
        val door = getDoorByNumber(doorNumber) ?: return

        // 起始门：立即开
        if (door.mode == Door.DoorMode.START) {
            openDoorImmediately(doorNumber)
            player?.sendMessage(Component.text("起始门已开启！", NamedTextColor.GREEN))
            return
        }

        if (door.mode == Door.DoorMode.PLAYER || door.mode == Door.DoorMode.ZOMBIE) {
            player?.sendMessage(Component.text("此门不能通过按钮开启！", NamedTextColor.RED))
            return
        }

        // 守卫：有活跃 session
        if (activeSession != null) {
            player?.sendMessage(Component.text("需要等上一道门关闭才可以开这道门！", NamedTextColor.RED))
            return
        }

        // 找同组所有门
        val groupDoors = if (!door.group.isNullOrBlank()) {
            doorGroups[door.group] ?: listOf(door)
        } else {
            listOf(door)
        }

        // 标记激活
        groupDoors.forEach { it.isActive = true }

        val session = Session(groupDoors, countdown = door.openTime.toDouble())
        activeSession = session

        val doorNums = groupDoors.map { it.doorNumber }.toSet().joinToString(", ")
        val playerName = player?.name ?: "控制台"
        Bukkit.broadcast(Component.text()
            .append(Component.text(playerName, NamedTextColor.AQUA))
            .append(Component.text(" 开启了 ", NamedTextColor.GREEN))
            .append(Component.text("$doorNums 号大门！", NamedTextColor.GREEN))
            .build())

        DebugLogger.door("${playerName} 触发了 ${doorNums} 号大门（开:${door.openTime}s / 关:${door.closeTime}s）")

        // 亮按钮
        groupDoors.forEach { d ->
            plugin.buttonManager.getButtonByDoorNumber(d.doorNumber)?.let {
                plugin.buttonManager.setButtonLit(it)
            }
        }

        startCountdown(session)
    }

    // ==================== 统一倒计时 ====================

    private fun startCountdown(session: Session) {
        val lastDisplay = intArrayOf(-1)

        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                session.countdown = -1.0
                schedTask.cancel()
                return@runAtFixedRate
            }

            if (session.phase == Session.Phase.OPENING) {
                // 开门前倒计时
                if (session.countdown > 0) {
                    val current = session.countdown.toInt()
                    if (current != lastDisplay[0]) {
                        val doorNum = session.doors.first().doorNumber
                        val sb = session.doors.first().specialBehavior
                        val label = when (sb) {
                            is SpecialDoorBehavior.Subway -> "${sb.lineName}"
                            else -> "$doorNum 号大门"
                        }
                        val title = Title.title(
                            Component.text("$current", NamedTextColor.LIGHT_PURPLE),
                            Component.text("$label 即将开启……", NamedTextColor.GREEN),
                            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                        )
                        Bukkit.getOnlinePlayers().forEach { p ->
                            p.showTitle(title)
                            p.playSound(p.location, Sound.BLOCK_DISPENSER_FAIL, 0.2f, 2f)
                        }
                        lastDisplay[0] = current
                    }
                    session.countdown -= 1.0
                } else {
                    // 开门
                    session.doors.forEach { d -> openDoorBlocks(d) }
                    session.countdown = -(session.doors.first().closeTime.toDouble())
                    session.phase = Session.Phase.CLOSING
                }
            } else {
                // 关门倒计时（countdown < 0）
                val remaining = -session.countdown
                if (remaining > 0) {
                    val current = remaining.toInt()
                    if (current != lastDisplay[0]) {
                        val doorNum = session.doors.first().doorNumber
                        val sb = session.doors.first().specialBehavior

                        val behindMsg: String
                        val aheadMsg: String

                        when (sb) {
                            is SpecialDoorBehavior.Subway -> {
                                behindMsg = "${sb.lineName}列车将在 $current 秒后发车，请立即上车！"
                                aheadMsg = "${sb.lineName}列车将在 $current 秒后发车……"
                            }
                            else -> {
                                behindMsg = "$doorNum 号大门将在 $current 秒后关闭，请立即进入！"
                                aheadMsg = "$doorNum 号大门将在 $current 秒后关闭……"
                            }
                        }

                        Bukkit.getOnlinePlayers().forEach { p ->
                            val room = plugin.gameManager.getPlayerRoom(p)
                            val inFront = session.doors.any { it.isPlayerPastDoor(p.location) }
                            val title = if (!inFront) {
                                Title.title(
                                    Component.text("$current", NamedTextColor.RED),
                                    Component.text(behindMsg, NamedTextColor.GOLD),
                                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                                )
                            } else {
                                Title.title(
                                    Component.empty(),
                                    Component.text(aheadMsg, NamedTextColor.GRAY),
                                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                                )
                            }
                            p.showTitle(title)
                        }

                        when (current) {
                            5, 3, 2, 1 -> Bukkit.getOnlinePlayers().forEach { p ->
                                p.playSound(p.location, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.1f, 2f)
                            }
                        }
                        lastDisplay[0] = current
                    }
                    session.countdown += 1.0

                } else {
                    // 关门
                    closeAllDoors(session)
                    schedTask.cancel()
                }
            }
        }, 1L, 20L)
        doorTasks.add(task)
    }

    // ==================== 开门 ====================

    private fun openDoorBlocks(door: Door) {
        val world = Bukkit.getWorlds().first()
        door.open(world)
        val center = door.getCenterLocation(world)
        Bukkit.getRegionScheduler().execute(plugin, center) {
            door.openBlocks(world)
        }

        val doorNum = door.doorNumber
        DebugLogger.door("${doorNum} 号大门已开启")

        val soundLoc = Bukkit.getOnlinePlayers().firstOrNull()?.location ?: world.spawnLocation
        world.playSound(soundLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 2f)
        world.playSound(soundLoc, Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.5f)
        world.playSound(soundLoc, Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 0.5f)
        world.playSound(soundLoc, Sound.BLOCK_BELL_USE, 1f, 0.5f)

        Bukkit.getOnlinePlayers().forEach { p ->
            val label = when (door.specialBehavior) {
                is SpecialDoorBehavior.Subway -> "${(door.specialBehavior as SpecialDoorBehavior.Subway).lineName} 进站"
                else -> "$doorNum 号大门开启"
            }
            p.showTitle(Title.title(
                Component.text(label, NamedTextColor.GREEN),
                Component.text("请立即前往下一区域", NamedTextColor.GREEN)
            ))
        }
    }

    fun openDoorImmediately(doorNumber: Int, broadcast: Boolean = true) {
        val door = getDoorByNumber(doorNumber) ?: return
        openDoorBlocks(door)
    }

    fun openDoorImmediatelyByName(name: String, broadcast: Boolean = true) {
        val door = doors[name] ?: return
        openDoorBlocks(door)
    }

    // ==================== 关门（核心判定） ====================

    private fun closeAllDoors(session: Session) {
        val world = Bukkit.getWorlds().first()
        val allDoors = session.doors
        val primaryDoor = allDoors.first()
        val doorNum = primaryDoor.doorNumber

        // 还原方块
        allDoors.forEach { d ->
            d.close(world)
            val center = d.getCenterLocation(world)
            Bukkit.getRegionScheduler().execute(plugin, center) {
                d.closeBlocks(world)
            }
        }

        doorclose = doorNum

        val soundLoc = Bukkit.getOnlinePlayers().firstOrNull()?.location ?: world.spawnLocation
        world.playSound(soundLoc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.5f)
        world.playSound(soundLoc, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f)
        world.playSound(soundLoc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1f, 1f)

        // 判定玩家是否通过：记录过穿越，或当前已站在门前侧区域（兜底开门瞬间被挤到内侧/漏记的情况）
        val passedPlayers = mutableListOf<Player>()
        val behindPlayers = mutableListOf<Player>()

        Bukkit.getOnlinePlayers().forEach { p ->
            if (plugin.gameManager.getPlayerTeam(p) == GameManager.Team.SPECTATOR) return@forEach
            val crossed = session.crossedPlayers.contains(p.uniqueId)
            val pastDoor = session.doors.any { it.isPlayerPastDoor(p.location) }
            if (crossed || pastDoor) {
                passedPlayers.add(p)
            } else {
                behindPlayers.add(p)
            }
        }

        // 更新 room + 过门任务/XP
        passedPlayers.forEach { p ->
            plugin.progressionListener.onPassDoor(p)
            val current = plugin.gameManager.getPlayerRoom(p)
            if (doorNum > current) {
                plugin.gameManager.setPlayerRoom(p, doorNum)
            }
        }

        // 落后传送倒计时（人类和僵尸分别处理）
        behindPlayers.forEach { p ->
            when (plugin.gameManager.getPlayerTeam(p)) {
                GameManager.Team.HUMAN -> startTransferCountdown(p, doorNum)
                else -> startZombieTransferCountdown(p, doorNum)
            }
        }

        DebugLogger.door("${doorNum} 号大门已关闭 | 通过: ${passedPlayers.map { it.name }} | 落后: ${behindPlayers.map { it.name }}")

        // 特殊行为传送
        if (primaryDoor.hasSpecialBehavior()) {
            val behavior = primaryDoor.specialBehavior!!
            DebugLogger.door("${doorNum} 号大门 特殊行为: ${behavior::class.simpleName}，通过: ${passedPlayers.map { it.name }}")

            if (passedPlayers.isNotEmpty()) {
                val task = behavior.execute(
                    SpecialDoorBehavior.ExecuteContext(
                        plugin = plugin,
                        door = primaryDoor,
                        players = passedPlayers,
                        world = world,
                        doorTasks = doorTasks
                    )
                )
                if (task != null) {
                    doorTasks.add(task)
                }
            }
        }

        activeSession = null
    }

    // ==================== 落后传送 ====================

    private fun startTransferCountdown(player: Player, doorNumber: Int) {
        transferTasks.remove(player)?.cancel()

        var countdown = 10
        val taskId = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (countdown > 0) {
                player.showTitle(Title.title(
                    Component.text("$countdown", NamedTextColor.RED),
                    Component.text("大门已关闭，请等待传送", NamedTextColor.DARK_RED)
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

    private fun startZombieTransferCountdown(player: Player, doorNumber: Int) {
        transferTasks.remove(player)?.cancel()

        var countdown = 10
        val taskId = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (countdown > 0) {
                player.showTitle(Title.title(
                    Component.text("$countdown", NamedTextColor.RED),
                    Component.text("大门已关闭，请等待传送", NamedTextColor.DARK_RED)
                ))
                countdown--
            } else {
                plugin.respawnManager.teleportZombieByDoorClose(player, doorNumber)
                transferTasks.remove(player)
                schedTask.cancel()
            }
        }, 1L, 20L)
        transferTasks[player] = taskId
    }

    // ==================== CRUD ====================

    fun addDoor(door: Door) {
        doors[door.name] = door
        plugin.doorZoneManager.addDoor(door)
        if (!door.group.isNullOrBlank()) {
            doorGroups.merge(door.group, listOf(door)) { old, new -> old + new }
        }
    }

    fun removeDoor(name: String) {
        val door = doors.remove(name) ?: return
        plugin.doorZoneManager.removeDoor(door)
        if (!door.group.isNullOrBlank()) {
            doorGroups[door.group]?.let { group ->
                val updated = group.filter { it.name != name }
                if (updated.isEmpty()) {
                    doorGroups.remove(door.group)
                } else {
                    doorGroups[door.group] = updated
                }
            }
        }
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

    fun getDoorsInGroup(group: String): List<Door> = doorGroups[group] ?: emptyList()

    fun tryRecordPlayerCrossing(player: Player, from: org.bukkit.Location, to: org.bukkit.Location) {
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        val session = activeSession ?: return
        if (session.phase != Session.Phase.CLOSING) return
        if (session.crossedPlayers.contains(player.uniqueId)) return

        session.doors.firstOrNull { it.crossedBy(from, to) }?.let { door ->
            session.crossedPlayers.add(player.uniqueId)
            DebugLogger.door("${player.name} 穿越了 ${door.doorNumber} 号门")
        }
    }

    fun getNextDoorNumber(): Int {
        val existing = doors.values
            .filter { it.mode == Door.DoorMode.NORMAL }
            .map { it.doorNumber }
            .toSet()
        var next = 1
        while (next in existing) next++
        return next
    }

    // ==================== 直升机撤离 ====================

    fun startHelicopterEscape() {
        if (endtime >= 0) return
        endtime = 30.0
        Bukkit.broadcast(Component.text()
            .append(Component.text("直升机已启动！", NamedTextColor.RED))
            .append(Component.newline())
            .append(Component.text("人类将在 30 秒后撤离！", NamedTextColor.RED))
            .append(Component.newline())
            .build())

        val lastDisplay = doubleArrayOf(-1.0)
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                endtime = -1.0
                schedTask.cancel()
                return@runAtFixedRate
            }

            if (endtime > 0) {
                val currentDisplay = Math.floor(endtime * 10) / 10
                if (currentDisplay != lastDisplay[0]) {
                    val displayStr = if (currentDisplay % 1 == 0.0) currentDisplay.toInt().toString() else String.format("%.1f", currentDisplay)
                    Bukkit.getOnlinePlayers().forEach { player ->
                        player.showTitle(Title.title(
                        Component.empty(),
                        Component.text()
                            .append(Component.text("游戏将于 ", NamedTextColor.YELLOW))
                            .append(Component.text(displayStr, NamedTextColor.LIGHT_PURPLE))
                            .append(Component.text(" 秒后结束！", NamedTextColor.YELLOW))
                            .build()
                    ))
                        player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.2f, 2f)
                    }
                    lastDisplay[0] = currentDisplay
                }
                endtime -= 0.1
            } else {
                Bukkit.getOnlinePlayers().forEach { player ->
                    player.showTitle(Title.title(
                        Component.text("游戏结束", NamedTextColor.RED),
                        Component.text("人类成功逃离！", NamedTextColor.AQUA)
                    ))
                    if (plugin.gameManager.getPlayerTeam(player) == GameManager.Team.HUMAN) {
                        val coins = plugin.economyConfig.surviveHumanCoins
                        plugin.coinManager.addCoins(player.uniqueId, coins)
                        player.sendMessage(Component.text("+ $coins 硬币！ (作为人类活到最后)", NamedTextColor.GOLD))
                    }
                }
                plugin.gameManager.endGame(GameManager.Team.HUMAN)
                schedTask.cancel()
            }
        }, 1L, 2L)
        doorTasks.add(task)
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

        activeSession = null
        endtime = -1.0
        doorclose = 0
    }
}
