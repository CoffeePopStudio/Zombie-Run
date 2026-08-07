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
import org.bukkit.World
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DoorManager(private val plugin: ZombieRun) {

    private val doors: ConcurrentHashMap<String, Door> = ConcurrentHashMap()

    // 由按钮触发线程（区域线程）写入、全局调度任务读写，跨线程需要可见性保证
    // 按世界隔离：同一世界同时只有一扇门会话
    private val activeSessions: ConcurrentHashMap<String, Session> = ConcurrentHashMap()

    private val endtimes: ConcurrentHashMap<String, Double> = ConcurrentHashMap()
    private val doorcloses: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    private val doorTasks: ConcurrentHashMap<String, CopyOnWriteArrayList<ScheduledTask>> = ConcurrentHashMap()
    private val transferTasks = ConcurrentHashMap<Player, ScheduledTask>()

    private val doorGroups = ConcurrentHashMap<String, List<Door>>()

    // ---- Session ----

    private class Session(
        val worldName: String,
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

    fun getDoorByNumber(world: String, number: Int): Door? = doors.values.firstOrNull { it.world == world && it.doorNumber == number }

    fun getDoorByName(name: String): Door? = doors[name]

    fun getAllDoors(): Collection<Door> = doors.values

    fun getDoorsInWorld(world: String): List<Door> = doors.values.filter { it.world == world }

    fun hasDoorsInWorld(world: String): Boolean = doors.values.any { it.world == world }

    fun getDoorGroups(): Map<String, List<Door>> = doorGroups

    // ==================== 按钮触发 ====================

    fun triggerDoor(doorNumber: Int, player: Player? = null, world: String = player?.world?.name ?: plugin.configManager.getWorldName()) {
        val door = getDoorByNumber(world, doorNumber) ?: return

        // 起始门：立即开
        if (door.mode == Door.DoorMode.START) {
            openDoorImmediately(doorNumber, world)
            player?.sendMessage(Component.text("起始门已开启！", NamedTextColor.GREEN))
            return
        }

        if (door.mode == Door.DoorMode.PLAYER || door.mode == Door.DoorMode.ZOMBIE) {
            player?.sendMessage(Component.text("此门不能通过按钮开启！", NamedTextColor.RED))
            return
        }

        // 守卫：该世界有活跃 session
        if (activeSessions[world] != null) {
            player?.sendMessage(Component.text("需要等上一道门关闭才可以开这道门！", NamedTextColor.RED))
            return
        }

        // 找同组所有门（同世界）
        val groupDoors = if (!door.group.isNullOrBlank()) {
            (doorGroups[door.group] ?: listOf(door)).filter { it.world == world }
        } else {
            listOf(door)
        }

        // 标记激活
        groupDoors.forEach { it.isActive = true }

        val session = Session(world, groupDoors, countdown = door.openTime.toDouble())
        activeSessions[world] = session

        val doorNums = groupDoors.map { it.doorNumber }.toSet().joinToString(", ")
        val playerName = player?.name ?: "控制台"
        plugin.gameManager.getWorldPlayers(world).forEach { p ->
            p.sendMessage(Component.text()
                .append(Component.text(playerName, NamedTextColor.AQUA))
                .append(Component.text(" 开启了 ", NamedTextColor.GREEN))
                .append(Component.text("$doorNums 号大门！", NamedTextColor.GREEN))
                .build())
        }

        DebugLogger.door("[${world}] ${playerName} 触发了 ${doorNums} 号大门（开:${door.openTime}s / 关:${door.closeTime}s）")

        // 亮按钮
        groupDoors.forEach { d ->
            plugin.buttonManager.getButtonByDoorNumber(d.world, d.doorNumber)?.let {
                plugin.buttonManager.setButtonLit(it)
            }
        }

        startCountdown(session)
    }

    // ==================== 统一倒计时 ====================

    private fun startCountdown(session: Session) {
        val lastDisplay = intArrayOf(-1)
        val worldName = session.worldName

        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus(worldName) != GameManager.GameStatus.RUNNING) {
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
                        plugin.gameManager.getWorldPlayers(worldName).forEach { p ->
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

                        plugin.gameManager.getWorldPlayers(worldName).forEach { p ->
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
                            5, 3, 2, 1 -> plugin.gameManager.getWorldPlayers(worldName).forEach { p ->
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
        addDoorTask(worldName, task)
    }

    private fun addDoorTask(worldName: String, task: ScheduledTask) {
        doorTasks.computeIfAbsent(worldName) { CopyOnWriteArrayList() }.add(task)
    }

    // ==================== 开门 ====================

    private fun openDoorBlocks(door: Door) {
        val world = plugin.worldService.getWorldOrFirst(door.world)
        door.open(world)
        val center = door.getCenterLocation(world)
        Bukkit.getRegionScheduler().execute(plugin, center) {
            door.openBlocks(world)
        }

        val doorNum = door.doorNumber
        DebugLogger.door("[${door.world}] ${doorNum} 号大门已开启")

        val soundLoc = plugin.gameManager.getWorldPlayers(door.world).firstOrNull()?.location ?: world.spawnLocation
        world.playSound(soundLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 2f)
        world.playSound(soundLoc, Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.5f)
        world.playSound(soundLoc, Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 0.5f)
        world.playSound(soundLoc, Sound.BLOCK_BELL_USE, 1f, 0.5f)

        plugin.gameManager.getWorldPlayers(door.world).forEach { p ->
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

    fun openDoorImmediately(doorNumber: Int, world: String, broadcast: Boolean = true) {
        val door = getDoorByNumber(world, doorNumber) ?: return
        openDoorBlocks(door)
    }

    fun openDoorImmediatelyByName(name: String, broadcast: Boolean = true) {
        val door = doors[name] ?: return
        openDoorBlocks(door)
    }

    // ==================== 关门（核心判定） ====================

    private fun closeAllDoors(session: Session) {
        val worldName = session.worldName
        val allDoors = session.doors
        val primaryDoor = allDoors.first()
        val doorNum = primaryDoor.doorNumber
        val world: World = plugin.worldService.getWorldOrFirst(primaryDoor.world)

        // 还原方块
        allDoors.forEach { d ->
            d.close(world)
            val center = d.getCenterLocation(world)
            Bukkit.getRegionScheduler().execute(plugin, center) {
                d.closeBlocks(world)
            }
        }

        doorcloses[worldName] = doorNum

        val soundLoc = plugin.gameManager.getWorldPlayers(worldName).firstOrNull()?.location ?: world.spawnLocation
        world.playSound(soundLoc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.5f)
        world.playSound(soundLoc, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f)
        world.playSound(soundLoc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1f, 1f)

        // 判定玩家是否通过：记录过穿越，或当前已站在门前侧区域（兜底开门瞬间被挤到内侧/漏记的情况）
        val passedPlayers = mutableListOf<Player>()
        val behindPlayers = mutableListOf<Player>()

        plugin.gameManager.getWorldPlayers(worldName).forEach { p ->
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

        DebugLogger.door("[${worldName}] ${doorNum} 号大门已关闭 | 通过: ${passedPlayers.map { it.name }} | 落后: ${behindPlayers.map { it.name }}")

        // 特殊行为传送
        if (primaryDoor.hasSpecialBehavior()) {
            val behavior = primaryDoor.specialBehavior!!
            DebugLogger.door("[${worldName}] ${doorNum} 号大门 特殊行为: ${behavior::class.simpleName}，通过: ${passedPlayers.map { it.name }}")

            if (passedPlayers.isNotEmpty()) {
                val task = behavior.execute(
                    SpecialDoorBehavior.ExecuteContext(
                        plugin = plugin,
                        door = primaryDoor,
                        players = passedPlayers,
                        world = world,
                        doorTasks = doorTasks.computeIfAbsent(worldName) { CopyOnWriteArrayList() }
                    )
                )
                if (task != null) {
                    addDoorTask(worldName, task)
                }
            }
        }

        activeSessions.remove(worldName)
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
        val world = plugin.worldService.getWorldOrFirst(door.world)
        door.close(world)
        val center = door.getCenterLocation(world)
        Bukkit.getRegionScheduler().execute(plugin, center) {
            door.closeBlocks(world)
        }
    }

    fun getDoorsInGroup(group: String): List<Door> = doorGroups[group] ?: emptyList()

    fun tryRecordPlayerCrossing(player: Player, from: org.bukkit.Location, to: org.bukkit.Location) {
        if (plugin.gameManager.getGameStatus(player.world.name) != GameManager.GameStatus.RUNNING) return
        val session = activeSessions[player.world.name] ?: return
        if (session.phase != Session.Phase.CLOSING) return
        if (session.crossedPlayers.contains(player.uniqueId)) return

        session.doors.firstOrNull { it.crossedBy(from, to) }?.let { door ->
            session.crossedPlayers.add(player.uniqueId)
            DebugLogger.door("[${player.world.name}] ${player.name} 穿越了 ${door.doorNumber} 号门")
        }
    }

    fun getNextDoorNumber(world: String): Int {
        val existing = doors.values
            .filter { it.mode == Door.DoorMode.NORMAL && it.world == world }
            .map { it.doorNumber }
            .toSet()
        var next = 1
        while (next in existing) next++
        return next
    }

    // ==================== 直升机撤离 ====================

    fun startHelicopterEscape(world: String) {
        if (endtimes.getOrDefault(world, -1.0) >= 0) return
        endtimes[world] = 30.0
        plugin.gameManager.getWorldPlayers(world).forEach { p ->
            p.sendMessage(Component.text()
                .append(Component.text("直升机已启动！", NamedTextColor.RED))
                .append(Component.newline())
                .append(Component.text("人类将在 30 秒后撤离！", NamedTextColor.RED))
                .append(Component.newline())
                .build())
        }

        val lastDisplay = doubleArrayOf(-1.0)
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus(world) != GameManager.GameStatus.RUNNING) {
                endtimes[world] = -1.0
                schedTask.cancel()
                return@runAtFixedRate
            }

            val endtime = endtimes.getOrDefault(world, -1.0)
            if (endtime > 0) {
                val currentDisplay = Math.floor(endtime * 10) / 10
                if (currentDisplay != lastDisplay[0]) {
                    val displayStr = if (currentDisplay % 1 == 0.0) currentDisplay.toInt().toString() else String.format("%.1f", currentDisplay)
                    plugin.gameManager.getWorldPlayers(world).forEach { player ->
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
                endtimes[world] = endtime - 0.1
            } else {
                plugin.gameManager.getWorldPlayers(world).forEach { player ->
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
                plugin.gameManager.endGame(plugin.gameManager.getGame(world), GameManager.Team.HUMAN)
                schedTask.cancel()
            }
        }, 1L, 2L)
        addDoorTask(world, task)
    }

    fun getEndtime(world: String): Double = endtimes.getOrDefault(world, -1.0)

    // ==================== 重置 ====================

    fun reset(world: String) {
        doorTasks.remove(world)?.forEach { it.cancel() }
        transferTasks.values.forEach { it.cancel() }
        transferTasks.clear()

        plugin.buttonManager.resetAllButtons()

        val worldObj = plugin.worldService.getWorldOrFirst(world)
        getDoorsInWorld(world).forEach { door ->
            if (door.isOpen) {
                door.close(worldObj)
                val center = door.getCenterLocation(worldObj)
                Bukkit.getRegionScheduler().execute(plugin, center) {
                    door.closeBlocks(worldObj)
                }
            }
        }

        activeSessions.remove(world)
        endtimes[world] = -1.0
        doorcloses[world] = 0
    }

    fun reset() {
        doors.keys.mapNotNull { doors[it]?.world }.distinct().forEach { reset(it) }
        doorTasks.clear()
        activeSessions.clear()
        endtimes.clear()
        doorcloses.clear()
    }
}
