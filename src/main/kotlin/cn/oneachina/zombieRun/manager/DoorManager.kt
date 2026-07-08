package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Door
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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

    private val doors: ConcurrentHashMap<String, Door> = ConcurrentHashMap()

    var opentime: Double = -1.0
        private set
    var closetime: Double = -1.0
        private set
    var endtime: Double = -1.0
        private set
    var forbidden: Boolean = false
        private set
    var doorclose: Int = 0
        private set

    private var currentDoorNumber: Int = 0
    private val doorTasks = CopyOnWriteArrayList<ScheduledTask>()
    private val transferTasks = ConcurrentHashMap<Player, ScheduledTask>()

    fun loadDoors() {
        doors.clear()
        plugin.configManager.loadDoors().forEach { doors[it.name] = it }
        plugin.doorZoneManager.initialize(doors.values)
        plugin.logger.info("已加载 ${doors.size} 扇门")
    }

    fun getDoorByNumber(number: Int): Door? {
        return doors.values.find { it.doorNumber == number }
    }

    fun openDoorImmediately(doorNumber: Int, broadcast: Boolean = true) {
        val door = getDoorByNumber(doorNumber) ?: return
        currentDoorNumber = doorNumber
        openDoor(door, broadcast)
    }

    fun openDoorImmediatelyByName(name: String, broadcast: Boolean = true) {
        val door = doors[name] ?: return
        currentDoorNumber = door.doorNumber
        openDoor(door, broadcast)
    }

    fun triggerDoor(doorNumber: Int, player: Player? = null) {
        val door = getDoorByNumber(doorNumber) ?: return

        if (door.mode == Door.DoorMode.START) {
            openDoorImmediately(doorNumber)
            player?.sendMessage(Component.text("§a起始门已开启！"))
            return
        }

        if (door.mode == Door.DoorMode.PLAYER || door.mode == Door.DoorMode.ZOMBIE) {
            player?.sendMessage(Component.text("§c此门不能通过按钮开启！"))
            return
        }

        if (forbidden) {
            player?.sendMessage(Component.text("你急啥急？！", NamedTextColor.RED))
            return
        }
        if (opentime >= 0 || closetime >= 0) {
            player?.sendMessage(Component.text("你需要等上一道门关闭才可以开这道门！", NamedTextColor.RED))
            return
        }

        currentDoorNumber = doorNumber

        val button = plugin.buttonManager.getButtonByDoorNumber(doorNumber)
        button?.let {
            plugin.buttonManager.setButtonLit(it)
        }

        val playerName = player?.name ?: "控制台"
        Bukkit.broadcast(Component.text("§b$playerName §a开启了 $doorNumber 号大门！"))

        opentime = door.delay.toDouble()
        startOpenCountdown(door)
    }

    private fun startOpenCountdown(door: Door) {
        var lastDisplay = -1
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                opentime = -1.0
                schedTask.cancel()
                return@runAtFixedRate
            }

            if (opentime > 0) {
                val currentDisplay = opentime.toInt()
                if (currentDisplay != lastDisplay) {
                    val title = Title.title(
                        Component.empty(),
                        Component.text()
                            .append(Component.text("大门即将开启于 ", NamedTextColor.GREEN))
                            .append(Component.text(currentDisplay.toString(), NamedTextColor.LIGHT_PURPLE))
                            .append(Component.text(" ……", NamedTextColor.GREEN))
                            .build(),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                    )
                    Bukkit.getOnlinePlayers().forEach { player ->
                        player.showTitle(title)
                        player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.2f, 2f)
                    }
                    lastDisplay = currentDisplay
                }
                opentime -= 1.0
            } else {
                openDoor(door, true)
                opentime = -1.0
                schedTask.cancel()
            }
        }, 0L, 20L)
        doorTasks.add(task)
    }

    private fun startCloseCountdown(door: Door) {
        var lastDisplay = -1.0
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                closetime = -1.0
                schedTask.cancel()
                return@runAtFixedRate
            }

            if (closetime > 0) {
                val currentDisplay = if (closetime % 1 == 0.0) closetime.toInt().toDouble() else Math.floor(closetime * 10) / 10

                val humansBehind = Bukkit.getOnlinePlayers().count { player ->
                    val team = plugin.gameManager.getPlayerTeam(player)
                    if (team != GameManager.Team.HUMAN) return@count false
                    val room = plugin.gameManager.getPlayerRoom(player)
                    room < currentDoorNumber
                }

                if (closetime > 3.1 && humansBehind == 0) {
                    Bukkit.broadcast(Component.text("所有人类都已进入，大门即将关闭……", NamedTextColor.GREEN))
                    closetime = 3.1
                }

                if (currentDisplay != lastDisplay) {
                    val displayStr = if (currentDisplay % 1 == 0.0) currentDisplay.toInt().toString() else String.format("%.1f", currentDisplay)

                    Bukkit.getOnlinePlayers().forEach { player ->
                        val room = plugin.gameManager.getPlayerRoom(player)
                        val title = if (room < currentDoorNumber) {
                            Title.title(
                                Component.text(displayStr, NamedTextColor.RED),
                                Component.text("$currentDoorNumber 号大门即将关闭，请立即进入！", NamedTextColor.GOLD),
                                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                            )
                        } else {
                            Title.title(
                                Component.empty(),
                                Component.text()
                                    .append(Component.text("$currentDoorNumber 号大门将在 ", NamedTextColor.GRAY))
                                    .append(Component.text(displayStr, NamedTextColor.RED))
                                    .append(Component.text(" 秒后关闭……", NamedTextColor.GRAY))
                                    .build(),
                                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                            )
                        }
                        player.showTitle(title)
                    }
                    lastDisplay = currentDisplay
                }

                when (closetime.toInt()) {
                    5, 3, 2, 1 -> {
                        Bukkit.getOnlinePlayers().forEach { player ->
                            player.playSound(player.location, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.1f, 2f)
                        }
                    }
                }

                closetime -= 0.1
            } else {
                closeDoor(door)
                closetime = -1.0
                schedTask.cancel()
            }
        }, 0L, 2L)
        doorTasks.add(task)
    }

    private fun openDoor(door: Door, broadcast: Boolean) {
        val world = Bukkit.getWorlds().first()
        door.open(world)

        forbidden = true
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ -> forbidden = false }, 60L)

        if (broadcast) {
            val soundLoc = Bukkit.getOnlinePlayers().firstOrNull()?.location ?: world.spawnLocation
            world.playSound(soundLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 2f)
            world.playSound(soundLoc, Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.5f)
            world.playSound(soundLoc, Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 0.5f)
            world.playSound(soundLoc, Sound.BLOCK_BELL_USE, 1f, 0.5f)

            Bukkit.getOnlinePlayers().forEach { player ->
                player.showTitle(Title.title(
                    Component.text("$currentDoorNumber 号大门开启", NamedTextColor.GREEN),
                    Component.text("请立即前往下一区域", NamedTextColor.GREEN)
                ))
            }
        }

        if (currentDoorNumber < 9 && door.mode != Door.DoorMode.START &&
            door.mode != Door.DoorMode.PLAYER && door.mode != Door.DoorMode.ZOMBIE) {
            closetime = door.closeTime.toDouble()
            startCloseCountdown(door)
        }
    }

    private fun closeDoor(door: Door) {
        val world = Bukkit.getWorlds().first()
        door.close(world)

        doorclose = currentDoorNumber

        val soundLoc = Bukkit.getOnlinePlayers().firstOrNull()?.location ?: world.spawnLocation
        world.playSound(soundLoc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.5f)
        world.playSound(soundLoc, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f)
        world.playSound(soundLoc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1f, 1f)

        Bukkit.getOnlinePlayers().forEach { player ->
            val room = plugin.gameManager.getPlayerRoom(player)
            if (room < currentDoorNumber) {
                startTransferCountdown(player)
            } else if (room == currentDoorNumber) {
                plugin.respawnManager.teleportPlayerByDoorClose(player, doorclose)
            }
        }

        if (door.needsSpecialTeleport()) {
            handleSpecialDoorClose(door)
        }
    }

    private fun startTransferCountdown(player: Player) {
        var countdown = 10
        val taskId = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (countdown > 0) {
                player.showTitle(Title.title(
                    Component.text("§c$countdown"),
                    Component.text("§4大门已关闭，请等待传送")
                ))
                countdown--
            } else {
                plugin.respawnManager.teleportPlayerByDoorClose(player, doorclose)
                transferTasks.remove(player)
                schedTask.cancel()
            }
        }, 0L, 20L)
        transferTasks[player] = taskId
    }

    private fun handleSpecialDoorClose(door: Door) {
        val world = Bukkit.getWorlds().first()
        when (door.doorNumber) {
            6 -> {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
                    Bukkit.getOnlinePlayers().forEach { player ->
                        if (plugin.gameManager.getPlayerRoom(player) == 6) {
                            player.showTitle(Title.title(
                                Component.text("§a感谢乘坐机场专线"),
                                Component.text("§e请拿好你的行李，有序下车")
                            ))
                            player.teleport(world.getBlockAt(110, 35, 112).location.add(0.5, 0.0, 0.5))
                        }
                    }
                }, 60L)
            }
            7 -> {
                var count = 5
                val elevatorTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
                    if (count > 0) {
                        Bukkit.getOnlinePlayers().forEach { player ->
                            if (plugin.gameManager.getPlayerRoom(player) == 7) {
                                player.showTitle(Title.title(
                                    Component.text("§a$count"),
                                    Component.text("§e电梯即将到达……")
                                ))
                            }
                        }
                        count--
                    } else {
                        Bukkit.getOnlinePlayers().forEach { player ->
                            if (plugin.gameManager.getPlayerRoom(player) == 7) {
                                player.showTitle(Title.title(
                                    Component.text("§a电梯已到达"),
                                    Component.text("§e祝您旅途愉快")
                                ))
                                player.teleport(player.location.add(0.0, 13.0, 0.0))
                            }
                        }
                        schedTask.cancel()
                    }
                }, 0L, 20L)
                doorTasks.add(elevatorTask)
            }
        }
    }

    fun startHelicopterEscape() {
        if (endtime >= 0) return
        endtime = 30.0
        Bukkit.broadcast(Component.text("§c\n直升机已启动！\n人类将在 30 秒后撤离！\n"))

        var lastDisplay = -1.0
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { schedTask ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                endtime = -1.0
                schedTask.cancel()
                return@runAtFixedRate
            }

            if (endtime > 0) {
                val currentDisplay = if (endtime % 1 == 0.0) endtime.toInt().toDouble() else Math.floor(endtime * 10) / 10

                if (currentDisplay != lastDisplay) {
                    val displayStr = if (currentDisplay % 1 == 0.0) currentDisplay.toInt().toString() else String.format("%.1f", currentDisplay)
                    Bukkit.getOnlinePlayers().forEach { player ->
                        player.showTitle(Title.title(
                            Component.text(""),
                            Component.text("§e游戏将于 §d$displayStr §e秒后结束！")
                        ))
                        player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.2f, 2f)
                    }
                    lastDisplay = currentDisplay
                }
                endtime -= 0.1
            } else {
                Bukkit.getOnlinePlayers().forEach { player ->
                    player.showTitle(Title.title(
                        Component.text("§c游戏结束"),
                        Component.text("§b人类 §a成功逃离！")
                    ))
                    if (plugin.gameManager.getPlayerTeam(player) == GameManager.Team.HUMAN) {
                        plugin.coinManager.addCoins(player.uniqueId, 200)
                        player.sendMessage(Component.text("§6+ 200 硬币！ (作为人类活到最后)"))
                    }
                }
                endHelicopterEscape()
                schedTask.cancel()
            }
        }, 0L, 2L)
        doorTasks.add(task)
    }

    private fun endHelicopterEscape() {
        plugin.gameManager.endGame(GameManager.Team.HUMAN)
    }

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
                }
            }
        }

        opentime = -1.0
        closetime = -1.0
        endtime = -1.0
        forbidden = false
        doorclose = 0
        currentDoorNumber = 0
    }

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

    fun onPlayerEnterDoor(player: Player, doorNumber: Int) {
        val door = getDoorByNumber(doorNumber)
        if (door != null) {
            player.sendMessage("你进入了 ${doorNumber} 号门区域")
        }
    }

    fun onPlayerLeaveDoor(player: Player, doorNumber: Int) {
        val door = getDoorByNumber(doorNumber)
    }
}
