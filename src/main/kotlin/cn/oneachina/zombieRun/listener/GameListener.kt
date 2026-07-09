package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import cn.oneachina.zombieRun.model.Button
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.*
import com.destroystokyo.paper.event.player.PlayerJumpEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GameListener(
    private val plugin: ZombieRun,
    val taskTracker: PlayerTaskTracker
) : Listener {

    private val playerCurrentDoorZones = ConcurrentHashMap<UUID, Int>()
    private val playerDoorEntryPoints = ConcurrentHashMap<UUID, Pair<Int, Double>>()
    private val activeTpSessions = ConcurrentHashMap<String, TpSession>()

    private data class TpSession(
        val button: Button,
        val teleportedPlayers: MutableSet<UUID>
    ) {
        var countdownTask: ScheduledTask? = null
        var forceDelayTask: ScheduledTask? = null
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        plugin.gameManager.addPlayer(player)
        plugin.coinManager.loadPlayer(player.uniqueId, player.name)
        plugin.staminaManager.addPlayer(player)
        plugin.shopGUI.onAutoOpen(player)

        when (plugin.gameManager.getGameStatus()) {
            GameManager.GameStatus.WAITING, GameManager.GameStatus.ENDED -> {
                plugin.gameManager.setPlayerTeam(player, GameManager.Team.HUMAN)
                player.gameMode = GameMode.ADVENTURE
                player.clearActivePotionEffects()
                player.inventory.clear()
                player.health = 20.0
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
                    plugin.respawnManager.teleportToPlayerInitialRespawn(player)
                }, 1L)
            }
            GameManager.GameStatus.STARTING -> {
                plugin.gameManager.setPlayerTeam(player, GameManager.Team.HUMAN)
                player.gameMode = GameMode.ADVENTURE
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
                    plugin.respawnManager.teleportToPlayerInitialRespawn(player)
                }, 1L)
            }
            GameManager.GameStatus.RUNNING -> {
                plugin.gameManager.setPlayerTeam(player, GameManager.Team.ZOMBIE)
                player.gameMode = GameMode.ADVENTURE
                plugin.staminaManager.applyZombieEffects(player)
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
                    plugin.respawnManager.teleportToZombieRespawn(player)
                }, 1L)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        taskTracker.clearAll(player.uniqueId)
        playerCurrentDoorZones.remove(player.uniqueId)
        playerDoorEntryPoints.remove(player.uniqueId)
        plugin.staminaManager.removePlayer(player)
        plugin.coinManager.savePlayer(player.uniqueId, player.name)
        plugin.gameManager.removePlayer(player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val to = event.to
        val player = event.player
        if (event.from.blockX == to.blockX &&
            event.from.blockY == to.blockY &&
            event.from.blockZ == to.blockZ) return

        plugin.staminaManager.setMoving(player, true)
        plugin.staminaManager.setSprinting(player, player.isSprinting)

        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        val team = plugin.gameManager.getPlayerTeam(player)

        if (team == GameManager.Team.HUMAN &&
            player.isSprinting &&
            !plugin.staminaManager.canSprintOrJump(player)
        ) {
            player.isSprinting = false
            plugin.staminaManager.setSprinting(player, false)
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c体力耗尽！请等待体力恢复到100才能疾跑。"))
        }

        if (team == GameManager.Team.SPECTATOR) return
        val currentDoorNumber = detectCurrentDoorNumber(player)
        handleDoorZoneEvents(player, currentDoorNumber)
        handleTpSessionCheck(player, currentDoorNumber)
        handleBlackWoolDamage(player)
    }

    private fun detectCurrentDoorNumber(player: Player): Int {
        val location = player.location
        val x = location.x
        val y = location.y
        val z = location.z

        val minX = (x - 2).toInt()
        val minZ = (z - 2).toInt()
        val maxX = (x + 2).toInt()
        val maxZ = (z + 2).toInt()
        val doors = plugin.doorZoneManager.getDoorsInArea(minX, minZ, maxX, maxZ).filter { it.doorNumber >= 1 && it.isOpen }

        for (door in doors) {
            if (x >= door.minX - 0.5 && x <= door.maxX + 0.5 &&
                y >= door.minY - 1.0 && y <= door.maxY + 1.0 &&
                z >= door.minZ - 0.5 && z <= door.maxZ + 0.5) {
                return door.doorNumber
            }
        }
        return -1
    }

    private fun handleDoorZoneEvents(player: Player, currentDoorNumber: Int) {
        val playerId = player.uniqueId
        val previousDoorNumber = playerCurrentDoorZones[playerId]

        if (previousDoorNumber != null && previousDoorNumber != currentDoorNumber) {
            plugin.doorManager.onPlayerLeaveDoor(player, previousDoorNumber)

            val entryPoint = playerDoorEntryPoints[playerId]
            if (entryPoint != null && entryPoint.first == previousDoorNumber) {
                val currentRoom = plugin.gameManager.getPlayerRoom(player)
                if (previousDoorNumber > currentRoom) {
                    plugin.gameManager.setPlayerRoom(player, previousDoorNumber)
                    player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已通过 ${previousDoorNumber} 号门，房间号更新为 ${previousDoorNumber}！"))
                    plugin.progressionListener.onPassDoor(player)
                }
                playerDoorEntryPoints.remove(playerId)
            }
        }

        if (currentDoorNumber != -1 && previousDoorNumber != currentDoorNumber) {
            plugin.doorManager.onPlayerEnterDoor(player, currentDoorNumber)

            val location = player.location
            val door = plugin.doorManager.getDoorByNumber(currentDoorNumber)
            if (door != null) {
                val entryPosition = if (door.maxX - door.minX > door.maxZ - door.minZ) {
                    location.x
                } else {
                    location.z
                }
                playerDoorEntryPoints[playerId] = Pair(currentDoorNumber, entryPosition)
            }
        }

        if (currentDoorNumber == -1) {
            playerCurrentDoorZones.remove(playerId)
        } else {
            playerCurrentDoorZones[playerId] = currentDoorNumber
        }
    }

    private fun handleBlackWoolDamage(player: Player) {
        val block = player.location.block
        if (block.type == Material.BLACK_WOOL) {
            if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.SPECTATOR) {
                player.damage(1000.0)
            }
        }
    }

    private fun handleTpSessionCheck(player: Player, currentDoorNumber: Int) {
        if (currentDoorNumber == -1) return
        for ((_, session) in activeTpSessions) {
            val areaDoorNumber = session.button.areaDoorNumber ?: continue
            if (currentDoorNumber == areaDoorNumber && player.uniqueId !in session.teleportedPlayers) {
                tpPlayerToTarget(session, player)
            }
        }
    }

    private fun tpPlayerToTarget(session: TpSession, player: Player) {
        val team = plugin.gameManager.getPlayerTeam(player)
        val target = session.button.getTargetForTeam(team) ?: return
        val (tx, ty, tz) = target
        val loc = org.bukkit.Location(player.world, tx + 0.5, ty.toDouble(), tz + 0.5)

        player.teleportAsync(loc)
        player.showTitle(Title.title(
            Component.text("传送完成", NamedTextColor.GREEN),
            Component.text("你已被传送！", NamedTextColor.GREEN),
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
        ))

        val areaDoorNumber = session.button.areaDoorNumber
        if (areaDoorNumber != null) {
            val currentRoom = plugin.gameManager.getPlayerRoom(player)
            if (areaDoorNumber > currentRoom) {
                plugin.gameManager.setPlayerRoom(player, areaDoorNumber)
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已传送到 ${areaDoorNumber} 号区域！"))
            }
        }

        session.teleportedPlayers.add(player.uniqueId)
    }

    private fun startTpForceDelay(session: TpSession, forceDelay: Int) {
        Bukkit.getOnlinePlayers().forEach { p ->
            val alreadyTp = p.uniqueId in session.teleportedPlayers
            if (!alreadyTp) {
                p.showTitle(Title.title(
                    Component.text("$forceDelay 秒后将强制传送", NamedTextColor.RED),
                    Component.text("请立即进入传送区域！", NamedTextColor.GOLD),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                ))
            }
        }

        val task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
                if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                    activeTpSessions.remove(session.button.name)
                    return@runDelayed
                }
                Bukkit.getOnlinePlayers().forEach { player ->
                    if (player.uniqueId !in session.teleportedPlayers) {
                        tpPlayerToTarget(session, player)
                    }
                }
                activeTpSessions.remove(session.button.name)
            }, (forceDelay * 20L).coerceAtLeast(1L))
        session.forceDelayTask = task
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return

        if (block.type == Material.REDSTONE_LAMP || block.type == Material.LEVER) {
            val button = plugin.buttonManager.getButton(block.x, block.y, block.z)
            if (button != null) {
                val player = event.player
                val team = plugin.gameManager.getPlayerTeam(player)

                when {
                    button.isNormal() -> {
                        if (team != GameManager.Team.HUMAN) {
                            player.sendMessage(Component.text("只有人类可以操作按钮！", NamedTextColor.RED))
                            event.isCancelled = true
                            return
                        }
                        val doorNumber = button.doorNumber
                        if (doorNumber != null) {
                            plugin.doorManager.triggerDoor(doorNumber, player)
                        } else {
                            player.sendMessage(Component.text("此按钮配置错误：未指定门号", NamedTextColor.RED))
                        }
                    }
                    button.isTp() -> {
                        val areaDoorNumber = button.areaDoorNumber
                        if (areaDoorNumber == null) {
                            player.sendMessage(Component.text("此按钮未配置 areaDoorNumber，无法使用", NamedTextColor.RED))
                            return
                        }
                        if (activeTpSessions.containsKey(button.name)) {
                            player.sendMessage(Component.text("此传送点已被激活，请等待", NamedTextColor.RED))
                            return
                        }
                        if (button.getTargetForTeam(team) == null) {
                            player.sendMessage(Component.text("此按钮没有为你所在队伍配置传送目标", NamedTextColor.RED))
                            return
                        }

                        val countdown = plugin.configManager.getTpButtonCountdown()
                        val forceDelay = plugin.configManager.getTpButtonForceDelay()
                        val teleportedPlayers = ConcurrentHashMap.newKeySet<UUID>()
                        val session = TpSession(button, teleportedPlayers)
                        activeTpSessions[button.name] = session

                        plugin.buttonManager.setButtonLit(button)

                        Bukkit.getOnlinePlayers().forEach { p ->
                            p.showTitle(Title.title(
                                Component.empty(),
                                Component.text("传送将在 $countdown 秒后执行，请前往 ${areaDoorNumber} 号门区域", NamedTextColor.YELLOW),
                                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                            ))
                        }

                        var remaining = countdown
                        val countdownTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task ->
                                if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                                    activeTpSessions.remove(button.name)
                                    task.cancel()
                                    return@runAtFixedRate
                                }
                                if (remaining > 0) {
                                    if (remaining % 5 == 0 || remaining <= 3) {
                                        Bukkit.getOnlinePlayers().forEach { p ->
                                            val alreadyTp = p.uniqueId in teleportedPlayers
                                            val msgStr = if (alreadyTp) "§a已传送" else "§c$remaining 秒"
                                        p.showTitle(Title.title(
                                            Component.empty(),
                                            LegacyComponentSerializer.legacySection().deserialize("§e已传送: ${teleportedPlayers.size} | 剩余: $msgStr"),
                                                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(700), Duration.ofMillis(300))
                                            ))
                                        }
                                    }
                                    remaining--
                                } else {
                                    task.cancel()
                                    startTpForceDelay(session, forceDelay)
                                }
                            }, 1L, 20L)
                        session.countdownTask = countdownTask
                    }
                    button.isEscape() -> {
                        if (team != GameManager.Team.HUMAN) {
                            player.sendMessage(Component.text("只有人类可以操作按钮！", NamedTextColor.RED))
                            event.isCancelled = true
                            return
                        }
                        if (plugin.doorManager.endtime < 0) {
                            plugin.doorManager.startHelicopterEscape()
                            block.type = Material.AIR
                        }
                    }
                }
                event.isCancelled = true
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.whoClicked is Player) {
            val player = event.whoClicked as Player
            if (player.gameMode != GameMode.CREATIVE) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerSwapHandItems(event: PlayerSwapHandItemsEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerToggleSprint(event: PlayerToggleSprintEvent) {
        val player = event.player
        plugin.staminaManager.setSprinting(player, event.isSprinting)
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return
        if (event.isSprinting && !plugin.staminaManager.canSprintOrJump(player)) {
            event.isCancelled = true
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c体力耗尽！请等待体力恢复到100才能疾跑。"))
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerJump(event: PlayerJumpEvent) {
        val player = event.player
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return
        if (!plugin.staminaManager.canSprintOrJump(player)) {
            event.isCancelled = true
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c体力耗尽！请等待体力恢复到100才能跳跃。"))
            return
        }
        plugin.staminaManager.addJumpCount(player)
    }

    @EventHandler
    fun onAsyncChat(event: io.papermc.paper.event.player.AsyncChatEvent) {
        event.isCancelled = true
        val player = event.player
        val team = plugin.gameManager.getPlayerTeam(player)
        val rawMsg = PlainTextComponentSerializer.plainText().serialize(event.message())
        val msg = rawMsg.replace("&", "")

        val title = plugin.titleManager.getPlayerTitle(player)
        val titlePrefix = if (title.isNotEmpty() && plugin.gameManager.getGameStatus() == GameManager.GameStatus.RUNNING) {
            Component.text("[$title] ", NamedTextColor.GOLD)
        } else {
            Component.empty()
        }
        val prefix = titlePrefix.append(when (team) {
            GameManager.Team.HUMAN -> Component.text("[人类] ", NamedTextColor.AQUA)
            GameManager.Team.ZOMBIE -> Component.text("[僵尸] ", NamedTextColor.DARK_GREEN)
            GameManager.Team.ZOMBIE_MAIN -> Component.text("[母体] ", NamedTextColor.LIGHT_PURPLE)
            else -> Component.text("[等待] ", NamedTextColor.GRAY)
        })

        val messageComponent = Component.text()
            .append(prefix)
            .append(Component.text(player.name, NamedTextColor.WHITE))
            .append(Component.text(" >> ", NamedTextColor.GOLD))
            .append(Component.text(msg, NamedTextColor.WHITE))
            .build()

        Bukkit.getGlobalRegionScheduler().run(plugin, { _ ->
            Bukkit.broadcast(messageComponent)
        })
    }
}

