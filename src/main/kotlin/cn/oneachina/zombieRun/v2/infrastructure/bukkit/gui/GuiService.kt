package cn.oneachina.zombierun.v2.infrastructure.bukkit.gui

import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.application.task.TaskService
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
    private val tasks: TaskService,
    private val logger: V2Logger,
    private val inventoryFactory: (InventoryHolder, Int, Component) -> Inventory =
        { holder, size, title -> Bukkit.createInventory(holder, size, title) },
    private val iconFactory: (Material, String, List<String>) -> ItemStack =
        { material, name, lore ->
            val item = ItemStack(material)
            val meta = item.itemMeta
            if (meta != null) {
                meta.displayName(Component.text(name))
                meta.lore(lore.map { Component.text(it) })
                item.itemMeta = meta
            }
            item
        },
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
        val inv = inventoryFactory(holder, 27, Component.text("我的资料"))
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
        val inv = inventoryFactory(holder, minOf(54, (items.size / 9 + 1) * 9).coerceAtLeast(9), Component.text("武器商店"))
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
                        if (ok) {
                            p.sendMessage(Component.text("购买成功：${weapon.displayName}", NamedTextColor.GREEN))
                        } else {
                            // 发枪失败（外部武器系统不可用）回滚扣款
                            playerData.addCoins(p.uniqueId, price)
                            p.sendMessage(Component.text("武器发放失败，已退还 $price 硬币", NamedTextColor.RED))
                        }
                    }
                }
            }
        }
        register(player, inv, "shop", actions)
    }

    fun openTasks(player: Player) {
        val entries = tasks.progressOf(player.uniqueId)
        if (entries.isEmpty()) {
            player.sendMessage(Component.text("当前没有可用任务，请管理员在 tasks.yml 配置", NamedTextColor.YELLOW))
            return
        }
        val holder = Holder("tasks")
        val rows = ((entries.size + 8) / 9).coerceAtMost(6)
        val inv = inventoryFactory(holder, rows * 9, Component.text("任务"))
        holder.backingInventory = inv
        val actions = mutableMapOf<Int, (Player, InventoryClickEvent) -> Unit>()

        entries.forEachIndexed { index, (task, progress) ->
            val slot = index
            if (slot >= rows * 9) return@forEachIndexed
            val status = when {
                progress.claimed -> "§7已领取"
                progress.progress >= task.target -> "§a可领取"
                else -> "${progress.progress}/${task.target}"
            }
            val iconType = when {
                progress.claimed -> Material.LIME_DYE
                progress.progress >= task.target -> Material.GOLD_INGOT
                else -> Material.PAPER
            }
            inv.setItem(
                slot,
                icon(
                    iconType,
                    "${task.description} [${task.period.name.lowercase()}]",
                    listOf(
                        "进度: $status",
                        "奖励: ${task.rewardCoins} 硬币 / ${task.rewardXp} 经验",
                        if (progress.claimed) "§7奖励已领取" else "§a点击领取",
                    ),
                ),
            )
            actions[slot] = { p, _ ->
                if (progress.claimed) {
                    p.sendMessage(Component.text("该任务奖励已领取", NamedTextColor.RED))
                } else if (progress.progress >= task.target) {
                    val result = tasks.claim(p.uniqueId, task.id)
                    p.sendMessage(Component.text(result, NamedTextColor.GREEN))
                    p.closeInventory()
                } else {
                    p.sendMessage(Component.text("任务尚未完成", NamedTextColor.RED))
                }
            }
        }
        register(player, inv, "tasks", actions)
    }

    fun openTitles(player: Player) {
        val list = titleCatalog()
        val holder = Holder("titles")
        val rows = ((list.size + 8) / 9).coerceAtMost(6).coerceAtLeast(1)
        val inv = inventoryFactory(holder, rows * 9, Component.text("选择称号"))
        holder.backingInventory = inv
        val actions = mutableMapOf<Int, (Player, InventoryClickEvent) -> Unit>()

        list.forEachIndexed { index, title ->
            val slot = index
            if (slot >= rows * 9) return@forEachIndexed
            val current = playerData.profileOf(player.uniqueId).title
            val selected = current == title
            inv.setItem(
                slot,
                icon(
                    if (selected) Material.NAME_TAG else Material.PAPER,
                    title,
                    listOf(if (selected) "§a当前称号" else "§e点击使用"),
                ),
            )
            actions[slot] = { p, _ ->
                playerData.setTitle(p.uniqueId, title)
                p.sendMessage(Component.text("已设置称号：$title", NamedTextColor.GREEN))
                p.closeInventory()
            }
        }

        // 最后一格放清除
        val clearSlot = rows * 9 - 1
        inv.setItem(clearSlot, icon(Material.BARRIER, "清除称号", listOf("§7点击取消当前称号")))
        actions[clearSlot] = { p, _ ->
            playerData.setTitle(p.uniqueId, null)
            p.sendMessage(Component.text("已清除称号", NamedTextColor.GREEN))
            p.closeInventory()
        }
        register(player, inv, "titles", actions)
    }

    /** 可配置称号目录；后续可改为读取 config/titles.yml。 */
    private fun titleCatalog(): List<String> = listOf(
        "新人",
        "跑酷者",
        "门之守护者",
        "僵尸杀手",
        "逃生专家",
        "金色传说",
    )

    private fun register(player: Player, inv: Inventory, menuId: String, actions: Map<Int, (Player, InventoryClickEvent) -> Unit>) {
        slots[player.uniqueId] = actions
        player.openInventory(inv)
        logger.debug("gui", "opened $menuId for ${player.name}")
    }

    private fun icon(material: Material, name: String, lore: List<String>): ItemStack =
        iconFactory(material, name, lore)
}