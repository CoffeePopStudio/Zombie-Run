package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent

/**
 * 保护类监听：对局世界的建造/物品/聊天防护。
 *
 * - 方块破坏/放置、丢物品：非 CREATIVE 一律拦截（对局地图保护）
 * - 僵尸不能拾取物品
 * - 聊天接管：按队伍前缀广播
 * - 物品栏：v2 自有 GUI 面板内点击由 GuiService 拦截；这里只拦 shift/数字键
 *   把背包物品塞进 v2 GUI 的路径，玩家背包本身可自由整理
 */
class V2ProtectionListener(
    private val plugin: org.bukkit.plugin.java.JavaPlugin,
    private val gameFlow: GameFlowService,
) : Listener {

    // ==================== 方块保护 ====================

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) event.isCancelled = true
    }

    // ==================== 物品保护 ====================

    @EventHandler(ignoreCancelled = true)
    fun onDropItem(event: PlayerDropItemEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val world = player.world.name
        val team = gameFlow.teamOf(world, player.uniqueId) ?: return
        if (team == GameTeam.ZOMBIE || team == GameTeam.ZOMBIE_MAIN) {
            event.isCancelled = true
        }
    }

    /** 阻止把玩家背包物品 shift/数字键塞进 v2 自有 GUI 面板。 */
    @EventHandler(ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val top = player.openInventory.topInventory
        val topHolder = top.holder
        // 只针对 v2 自有 GUI：点击上层格子交给 GuiService；点击下层时拦截 shift 类动作
        if (topHolder == null) return
        if (event.clickedInventory === top) return // 上层点击由 GuiService 处理/取消
        val click = event.click
        if (click.isShiftClick || click == org.bukkit.event.inventory.ClickType.NUMBER_KEY ||
            click == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val top = player.openInventory.topInventory ?: return
        if (top.holder == null) return
        // 拖拽任何一格落在 v2 面板内即取消
        if (event.rawSlots.any { it < top.size }) event.isCancelled = true
    }

    // ==================== 副手切换 ====================

    @EventHandler(ignoreCancelled = true)
    fun onSwapHandItems(event: PlayerSwapHandItemsEvent) {
        // 非潜行 F 一律取消（防误触副手）；潜行+F 开商店逻辑在 V2GameListener
        if (!event.player.isSneaking) {
            event.isCancelled = true
        }
    }

    // ==================== 聊天接管 ====================

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        event.isCancelled = true
        val player = event.player
        val world = player.world.name
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).replace("&", "")
        val team = gameFlow.teamOf(world, player.uniqueId)
        val prefix = when (team) {
            GameTeam.HUMAN -> Component.text("[人类] ", NamedTextColor.AQUA)
            GameTeam.ZOMBIE -> Component.text("[僵尸] ", NamedTextColor.DARK_GREEN)
            GameTeam.ZOMBIE_MAIN -> Component.text("[母体] ", NamedTextColor.LIGHT_PURPLE)
            GameTeam.SPECTATOR -> Component.text("[观战] ", NamedTextColor.GRAY)
            null -> Component.text("[等待] ", NamedTextColor.GRAY)
        }
        val message = Component.text()
            .append(prefix)
            .append(Component.text(player.name, NamedTextColor.WHITE))
            .append(Component.text(" >> ", NamedTextColor.GOLD))
            .append(Component.text(raw, NamedTextColor.WHITE))
            .build()
        Bukkit.getGlobalRegionScheduler().run(plugin, { _ -> Bukkit.broadcast(message) })
    }
}
