package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.*
import com.destroystokyo.paper.event.player.PlayerJumpEvent
import org.bukkit.scheduler.BukkitRunnable
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GameListener(private val plugin: ZombieRun) : Listener {

    private val playerTasks = ConcurrentHashMap<UUID, MutableList<Int>>()
    private val playerCurrentDoorZones = ConcurrentHashMap<UUID, Int>()
    private val playerDoorEntryPoints = ConcurrentHashMap<UUID, Pair<Int, Double>>()

    private fun registerTask(taskId: Int, player: Player) {
        playerTasks.computeIfAbsent(player.uniqueId) { mutableListOf() }.add(taskId)
    }

    private fun unregisterTask(taskId: Int, playerId: UUID) {
        val tasks = playerTasks[playerId] ?: return
        tasks.remove(taskId)
        if (tasks.isEmpty()) {
            playerTasks.remove(playerId)
        }
    }

    private fun clearPlayerTasks(playerId: UUID) {
        playerTasks[playerId]?.forEach { Bukkit.getScheduler().cancelTask(it) }
        playerTasks.remove(playerId)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        plugin.gameManager.addPlayer(player)
        plugin.staminaManager.addPlayer(player)

        when (plugin.gameManager.getGameStatus()) {
            GameManager.GameStatus.WAITING, GameManager.GameStatus.ENDED -> {
                plugin.gameManager.setPlayerTeam(player, GameManager.Team.HUMAN)
                plugin.respawnManager.teleportToPlayerInitialRespawn(player)
                player.gameMode = GameMode.ADVENTURE
                player.clearActivePotionEffects()
                player.inventory.clear()
                player.health = 20.0
            }
            GameManager.GameStatus.STARTING -> {
                plugin.gameManager.setPlayerTeam(player, GameManager.Team.HUMAN)
                plugin.respawnManager.teleportToPlayerInitialRespawn(player)
                player.gameMode = GameMode.ADVENTURE
            }
            GameManager.GameStatus.RUNNING -> {
                plugin.gameManager.setPlayerTeam(player, GameManager.Team.ZOMBIE)
                plugin.respawnManager.teleportToZombieRespawn(player)
                player.gameMode = GameMode.ADVENTURE
                plugin.staminaManager.applyZombieEffects(player)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        clearPlayerTasks(player.uniqueId)
        playerCurrentDoorZones.remove(player.uniqueId)
        playerDoorEntryPoints.remove(player.uniqueId)
        plugin.staminaManager.removePlayer(player)
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
            player.sendMessage("§c体力耗尽！请等待体力恢复到100才能疾跑。")
        }

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
                    player.sendMessage("§a已通过 ${previousDoorNumber} 号门，房间号更新为 ${previousDoorNumber}！")
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
                        val target = button.getTargetForTeam(team)
                        if (target == null) {
                            player.sendMessage(Component.text("此按钮没有为你所在队伍配置传送目标", NamedTextColor.RED))
                            return
                        }
                        val (tx, ty, tz) = target
                        val world = player.world
                        val loc = org.bukkit.Location(world, tx + 0.5, ty.toDouble(), tz + 0.5)

                        val title = Title.title(
                            Component.empty(),
                            Component.text("传送将在10秒后执行...", NamedTextColor.YELLOW),
                            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                        )
                        player.showTitle(title)
                        plugin.buttonManager.setButtonLit(button)

                        var taskId = -1
                        val task = object : BukkitRunnable() {
                            override fun run() {
                                player.teleport(loc)
                                val teleportTitle = Title.title(
                                    Component.text("传送完成", NamedTextColor.GREEN),
                                    Component.text("你已被传送！", NamedTextColor.GREEN),
                                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                                )
                                player.showTitle(teleportTitle)

                                button.doorNumbers?.forEach { doorNumber ->
                                    plugin.doorManager.triggerDoor(doorNumber, player, true)
                                }

                                if (button.areaDoorNumber != null) {
                                    val currentRoom = plugin.gameManager.getPlayerRoom(player)
                                    if (button.areaDoorNumber > currentRoom) {
                                        plugin.gameManager.setPlayerRoom(player, button.areaDoorNumber)
                                        player.sendMessage("§a已传送到 ${button.areaDoorNumber} 号区域！")
                                    }
                                }
                                unregisterTask(taskId, player.uniqueId)
                            }
                        }
                        taskId = task.runTaskLater(plugin, 200L).taskId
                        registerTask(taskId, player)
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

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        val victim = event.entity as? Player ?: return

        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return

        val attackerTeam = plugin.gameManager.getPlayerTeam(attacker)
        val victimTeam = plugin.gameManager.getPlayerTeam(victim)

        if (attackerTeam == GameManager.Team.HUMAN &&
            (victimTeam == GameManager.Team.ZOMBIE || victimTeam == GameManager.Team.ZOMBIE_MAIN)) {
            victim.velocity = victim.velocity.setY(-0.3)
            return
        }

        if ((attackerTeam == GameManager.Team.ZOMBIE || attackerTeam == GameManager.Team.ZOMBIE_MAIN) &&
            victimTeam == GameManager.Team.HUMAN) {
            event.damage = 3.0
            return
        }

        if (attackerTeam == victimTeam) {
            event.isCancelled = true
        }
    }

    private fun infectPlayer(attacker: Player, victim: Player) {
        plugin.miscManager.addInfection(attacker)
        plugin.miscManager.addCoins(attacker, 50)

        val attackerName = if (plugin.gameManager.getPlayerTeam(attacker) == GameManager.Team.ZOMBIE_MAIN) "§5${attacker.name}" else "§2${attacker.name}"
        Bukkit.broadcast(Component.text("$attackerName §c感染了 §b${victim.name}"))

        victim.inventory.clear()
        plugin.gameManager.setPlayerTeam(victim, GameManager.Team.ZOMBIE)
        victim.gameMode = GameMode.SPECTATOR

        var countdown = 5
        var taskId = -1
        val task = object : BukkitRunnable() {
            override fun run() {
                if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                    unregisterTask(taskId, victim.uniqueId)
                    cancel()
                    return
                }
                if (countdown > 0) {
                    val title = Title.title(
                        Component.text("§c$countdown"),
                        Component.text("§2你已死亡，等待部署"),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO)
                    )
                    victim.showTitle(title)
                    countdown--
                } else {
                    victim.gameMode = GameMode.ADVENTURE
                    plugin.staminaManager.applyZombieEffects(victim)
                    plugin.respawnManager.teleportToZombieRespawn(victim)
                    victim.sendMessage(Component.text("你现在是僵尸！阻止人类前进！", NamedTextColor.DARK_GREEN))
                    unregisterTask(taskId, victim.uniqueId)
                    cancel()
                }
            }
        }
        taskId = task.runTaskTimer(plugin, 0L, 20L).taskId
        registerTask(taskId, victim)
    }

    private fun scheduleZombieRespawn(victim: Player, message: Component) {
        var taskId = -1
        val task = object : BukkitRunnable() {
            override fun run() {
                unregisterTask(taskId, victim.uniqueId)
                if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                    return
                }
                victim.gameMode = GameMode.ADVENTURE
                plugin.staminaManager.applyZombieEffects(victim)
                plugin.respawnManager.teleportToZombieRespawn(victim)
                victim.sendMessage(message)
            }
        }
        taskId = task.runTaskLater(plugin, 100L).taskId
        registerTask(taskId, victim)
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        event.isCancelled = true
        val victim = event.entity
        val killer = victim.killer

        event.drops.clear()
        event.deathMessage(null)

        when (val victimTeam = plugin.gameManager.getPlayerTeam(victim)) {
            GameManager.Team.HUMAN -> {
                if (killer != null && plugin.gameManager.getPlayerTeam(killer) in setOf(GameManager.Team.ZOMBIE, GameManager.Team.ZOMBIE_MAIN)) {
                    infectPlayer(killer, victim)
                } else {
                    plugin.gameManager.setPlayerTeam(victim, GameManager.Team.ZOMBIE)
                    victim.gameMode = GameMode.SPECTATOR
                    scheduleZombieRespawn(victim, Component.text("你已死亡并变为僵尸！", NamedTextColor.DARK_GREEN))
                }
            }
            GameManager.Team.ZOMBIE, GameManager.Team.ZOMBIE_MAIN -> {
                if (killer != null && plugin.gameManager.getPlayerTeam(killer) == GameManager.Team.HUMAN) {
                    plugin.miscManager.addKill(killer)
                    val reward = if (victimTeam == GameManager.Team.ZOMBIE_MAIN) 150 else 50
                    plugin.miscManager.addCoins(killer, reward)
                    val teamColor = if (victimTeam == GameManager.Team.ZOMBIE_MAIN) "§5" else "§2"
                    Bukkit.broadcast(Component.text("§b${killer.name} §f击杀了 $teamColor${victim.name}"))
                } else {
                    Bukkit.broadcast(Component.text("§2${victim.name} §f死亡了"))
                }
                victim.gameMode = GameMode.SPECTATOR
                scheduleZombieRespawn(victim, Component.text("你已复活为僵尸！", NamedTextColor.DARK_GREEN))
            }
            else -> {
                victim.inventory.clear()
                plugin.gameManager.setPlayerTeam(victim, GameManager.Team.ZOMBIE)
                victim.gameMode = GameMode.SPECTATOR
                scheduleZombieRespawn(victim, Component.text("你已死亡并变为僵尸！", NamedTextColor.DARK_GREEN))
            }
        }
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity !is Player) return

        if (event.cause == EntityDamageEvent.DamageCause.FALL) {
            event.isCancelled = true
            return
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
    fun onPlayerPickupItem(event: EntityPickupItemEvent) {
        val player = if (event.entity is Player) event.entity as? Player else return
        val team = plugin.gameManager.getPlayerTeam(player)
        if (team == GameManager.Team.ZOMBIE || team == GameManager.Team.ZOMBIE_MAIN) {
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
            player.sendMessage("§c体力耗尽！请等待体力恢复到100才能疾跑。")
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerJump(event: PlayerJumpEvent) {
        val player = event.player
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return
        if (!plugin.staminaManager.canSprintOrJump(player)) {
            event.isCancelled = true
            player.sendMessage("§c体力耗尽！请等待体力恢复到100才能跳跃。")
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

        val prefix = when (team) {
            GameManager.Team.HUMAN -> Component.text("[人类] ", NamedTextColor.AQUA)
            GameManager.Team.ZOMBIE -> Component.text("[僵尸] ", NamedTextColor.DARK_GREEN)
            GameManager.Team.ZOMBIE_MAIN -> Component.text("[母体] ", NamedTextColor.LIGHT_PURPLE)
            else -> Component.text("[等待] ", NamedTextColor.GRAY)
        }

        val messageComponent = Component.text()
            .append(prefix)
            .append(Component.text(player.name, NamedTextColor.WHITE))
            .append(Component.text(" >> ", NamedTextColor.GOLD))
            .append(Component.text(msg, NamedTextColor.WHITE))
            .build()

        Bukkit.getScheduler().runTask(plugin, Runnable {
            Bukkit.broadcast(messageComponent)
        })
    }
}
