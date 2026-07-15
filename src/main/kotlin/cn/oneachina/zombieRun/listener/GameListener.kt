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

        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        val team = plugin.gameManager.getPlayerTeam(player)

        if (team == GameManager.Team.SPECTATOR) return
        val currentDoorNumber = detectCurrentDoorNumber(player)
        handleDoorZoneEvents(player, currentDoorNumber)
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
        val loc = player.location
        val feetY = loc.blockY
        val woolBlocks = listOf(
            loc.block,
            loc.clone().subtract(0.0, 1.0, 0.0).block,
            loc.clone().subtract(0.0, 0.5, 0.0).block
        )
        for (block in woolBlocks) {
            if (block.type == Material.BLACK_WOOL) {
                if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.SPECTATOR) {
                    player.health = 0.0
                }
                return
            }
        }
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
                        val doorNumbers = button.getAllDoorNumbers()
                        if (doorNumbers.isEmpty()) {
                            player.sendMessage(Component.text("此按钮配置错误：未指定门号", NamedTextColor.RED))
                        } else {
                            doorNumbers.forEach { plugin.doorManager.triggerDoor(it, player) }
                        }
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

    @EventHandler
    fun onAsyncChat(event: io.papermc.paper.event.player.AsyncChatEvent) {
        event.isCancelled = true
        val player = event.player
        val team = plugin.gameManager.getPlayerTeam(player)
        val rawMsg = PlainTextComponentSerializer.plainText().serialize(event.message())
        val msg = rawMsg.replace("&", "")

        val title = plugin.titleManager.getPlayerTitle(player)
        val gameStatus = plugin.gameManager.getGameStatus()
        val titlePrefix = if (title.isNotEmpty() && gameStatus == GameManager.GameStatus.RUNNING) {
            Component.text("[$title] ", NamedTextColor.GOLD)
        } else {
            Component.empty()
        }
        val prefix = titlePrefix.append(when {
            gameStatus == GameManager.GameStatus.ENDED -> Component.text("[结束] ", NamedTextColor.RED)
            team == GameManager.Team.HUMAN -> Component.text("[人类] ", NamedTextColor.AQUA)
            team == GameManager.Team.ZOMBIE -> Component.text("[僵尸] ", NamedTextColor.DARK_GREEN)
            team == GameManager.Team.ZOMBIE_MAIN -> Component.text("[母体] ", NamedTextColor.LIGHT_PURPLE)
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

