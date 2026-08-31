package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.*
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GameListener(
    private val plugin: ZombieRun,
    val taskTracker: PlayerTaskTracker
) : Listener {

    // postool 选区
    private val pos1 = ConcurrentHashMap<UUID, Location>()
    private val pos2 = ConcurrentHashMap<UUID, Location>()

    fun getPos1(player: Player): Location? = pos1[player.uniqueId]
    fun getPos2(player: Player): Location? = pos2[player.uniqueId]

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        plugin.gameManager.addPlayer(player)
        plugin.coinManager.loadPlayerAsync(player.uniqueId, player.name)
        plugin.progressionManager.loadPlayerAsync(player.uniqueId)
        plugin.staminaManager.addPlayer(player)
        plugin.shopGUI.onAutoOpen(player)

        when (plugin.gameManager.getGameStatus()) {
            GameManager.GameStatus.WAITING, GameManager.GameStatus.ENDED -> {
                plugin.gameManager.setPlayerTeam(player, GameManager.Team.SPECTATOR)
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
                    // 中途加入：布防复活到人类前方更远的僵尸点
                    plugin.respawnManager.teleportZombieByProgress(
                        player, plugin.gameManager.getHumanProgress(), ahead = true
                    )
                }, 1L)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        taskTracker.clearAll(player.uniqueId)
        pos1.remove(player.uniqueId)
        pos2.remove(player.uniqueId)
        plugin.staminaManager.removePlayer(player)
        plugin.coinManager.savePlayer(player.uniqueId, player.name)
        plugin.gameManager.removePlayer(player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val to = event.to
        val player = event.player
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        if (player.gameMode == GameMode.SPECTATOR) return
        val team = plugin.gameManager.getPlayerTeam(player)
        if (team == GameManager.Team.SPECTATOR) return
        plugin.doorManager.tryRecordPlayerCrossing(player, event.from, to)
        checkBlackWool(player)
    }

    /**
     * 传送只更新门侧边状态，不记为“穿越”。
     * 这样可以避免系统传送/命令传送被采样兜底误判成通过门。
     */
    @EventHandler(ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val to = event.to
        val player = event.player
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        if (player.gameMode == GameMode.SPECTATOR) return
        val team = plugin.gameManager.getPlayerTeam(player)
        if (team == GameManager.Team.SPECTATOR) return
        plugin.doorManager.updatePlayerSideAfterTeleport(player, to)
    }

    /** 定期兜底检测黑色羊毛，防止 Move 事件漏触发或玩家站着不动 */
    fun startBlackWoolTask() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { _ ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return@runAtFixedRate
            Bukkit.getOnlinePlayers().forEach { p ->
                // 死亡等待复活/旁观中的玩家 gameMode 是 SPECTATOR，不能触发黑羊毛
                if (p.gameMode == GameMode.SPECTATOR) return@forEach
                val team = plugin.gameManager.getPlayerTeam(p)
                if (team == GameManager.Team.SPECTATOR) return@forEach
                checkBlackWool(p)
            }
        }, 1L, 4L)
    }

    private fun checkBlackWool(player: Player) {
        // 旁观/死亡等待复活中的玩家不触发
        if (player.gameMode == GameMode.SPECTATOR) return
        val loc = player.location
        // 完整方块铺在地面时，玩家脚下、脚下方块、以及脚底略微嵌入的位置都要检查
        val woolBlocks = listOf(
            loc.block,
            loc.clone().subtract(0.0, 1.0, 0.0).block,
            loc.clone().subtract(0.0, 0.5, 0.0).block,
            loc.clone().subtract(0.0, 0.1, 0.0).block
        )
        for (block in woolBlocks) {
            if (block.type == Material.BLACK_WOOL) {
                // 人类和僵尸都杀
                plugin.healthManager.damage(player, 10000.0)
                return
            }
        }
    }

    // ==================== postool 交互 ====================

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item
        val block = event.clickedBlock

        // postool: 木棍左键=pos1，右键=pos2
        if (item != null && item.type == Material.STICK && plugin.isPostoolActive(player)) {
            event.isCancelled = true
            if (block == null) return

            when (event.action) {
                Action.LEFT_CLICK_BLOCK -> {
                    pos1[player.uniqueId] = block.location
                    player.sendMessage(Component.text("pos1 已设为 (${block.x}, ${block.y}, ${block.z})", NamedTextColor.YELLOW))
                }
                Action.RIGHT_CLICK_BLOCK -> {
                    pos2[player.uniqueId] = block.location
                    player.sendMessage(Component.text("pos2 已设为 (${block.x}, ${block.y}, ${block.z})", NamedTextColor.YELLOW))
                }
                else -> {}
            }
            return
        }

        // 按钮交互
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (block == null) return

        if (block.type == Material.REDSTONE_LAMP || block.type == Material.LEVER) {
            val button = plugin.buttonManager.getButton(block.x, block.y, block.z)
            if (button != null) {
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
                            plugin.doorManager.triggerDoor(doorNumbers.first(), player)
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
                            plugin.buttonManager.setButtonLit(button)
                        }
                    }
                }
                event.isCancelled = true
            }
        }
    }

    // ==================== 方块/物品限制 ====================

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) {
            event.isCancelled = true
            // postool 玩家可以打破
            if (plugin.isPostoolActive(event.player)) {
                event.isCancelled = false
            }
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
            // postool 玩家允许 F 键
            if (plugin.isPostoolActive(event.player)) {
                event.isCancelled = false
            }
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
