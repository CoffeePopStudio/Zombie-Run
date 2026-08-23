package cn.oneachina.zombierun.v2.infrastructure.bukkit.gui

import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.application.weapon.WeaponService
import cn.oneachina.zombierun.v2.support.V2Logger
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * v2 轻量 GUI：按玩家注册打开的菜单，点击回调直接执行，关闭即清理。
 * 不引入额外 GUI 依赖。
 */
class GuiService(
    private val playerData: PlayerDataService,
    private val weapons: WeaponService,
    private val logger: V2Logger,
) : Listener {

    private val slots = ConcurrentHashMap<UUID, Map<Int, (Player, InventoryClickEvent) -> Unit>>()

    private class Holder(val menuId: String) : InventoryHolder {
        lateinit var backingInventory: Inventory
        override fun getInventory(): Inventory = backingInventory
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.inventory.holder
        if (holder !is Holder) return
        event.isCancelled = true
        val actions = slots[player.uniqueId] ?: return
        actions[event.slot]?.invoke(player, event)
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder
        if (holder is Holder) {
            slots.remove(event.player.uniqueId)
        }
    }

    fun openProfile(player: Player) {
        val profile = playerData.profileOf(player.uniqueId)
        val holder = Holder("profile")
        val inv = Bukkit.createInventory(holder, 27, Component.text("我的资料"))
        holder.backingInventory = inv
        inv.setItem(
            11,
            icon(
                Material.PLAYER_HEAD,
                player.name,
                listOf(
                    "等级: ${profile.level}",
                    "经验: ${profile.xp}",
                    "硬币: ${profile.coins}",
                    "称号: ${profile.title ?: "无"}",
                    "门数: ${profile.doorPasses}",
                    "击杀: ${profile.zombieKills}",
                ),
            ),
        )
        register(player, inv, "profile", mapOf(26 to { p, _ -> p.closeInventory() }))
    }

    fun openShop(player: Player) {
        val items = weapons.all()
        val holder = Holder("shop")
        val inv = Bukkit.createInventory(holder, minOf(54, (items.size / 9 + 1) * 9).coerceAtLeast(9), Component.text("武器商店"))
        holder.backingInventory = inv
        val actions = mutableMapOf<Int, (Player, InventoryClickEvent) -> Unit>()
        items.forEachIndexed { index, weapon ->
            val slot = index
            if (slot >= 54) return@forEachIndexed
            inv.setItem(
                slot,
                icon(
                    Material.DIAMOND_SWORD,
                    weapon.displayName,
                    listOf(
                        "类型: ${weapon.type}",
                        "分类: ${weapon.category.name.lowercase()}",
                        "价格: ${weapon.price.toInt()} 硬币",
                        if (weapon.enabled) "§a点击购买" else "§c暂不可购买",
                    ),
                ),
            )
            actions[slot] = { p, _ ->
                if (!weapon.enabled) {
                    p.sendMessage(Component.text("该武器不可购买", NamedTextColor.RED))
                } else {
                    val price = weapon.price.toInt()
                    val afterSpend = playerData.spendCoins(p.uniqueId, price)
                    if (afterSpend == null) {
                        p.sendMessage(Component.text("硬币不足", NamedTextColor.RED))
                    } else {
                        val ok = weapons.giveWeapon(p.uniqueId, weapon.id)
                        if (ok) p.sendMessage(Component.text("购买成功：${weapon.displayName}", NamedTextColor.GREEN))
                    }
                }
            }
        }
        register(player, inv, "shop", actions)
    }

    private fun register(player: Player, inv: Inventory, menuId: String, actions: Map<Int, (Player, InventoryClickEvent) -> Unit>) {
        slots[player.uniqueId] = actions
        player.openInventory(inv)
        logger.debug("gui", "opened $menuId for ${player.name}")
    }

    private fun icon(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(Component.text(name))
        meta.lore(lore.map { Component.text(it) })
        item.itemMeta = meta
        return item
    }
}