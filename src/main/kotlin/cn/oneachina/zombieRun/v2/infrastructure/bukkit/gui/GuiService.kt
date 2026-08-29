package cn.oneachina.zombierun.v2.infrastructure.bukkit.gui

import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.application.task.TaskService
import cn.oneachina.zombierun.v2.application.weapon.WeaponService
import cn.oneachina.zombierun.v2.domain.weapon.WeaponCategory
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

    companion object {
        /** 商店总行数 6（5 行武器区 + 1 行操作栏） */
        private const val SHOP_SIZE = 54

        /** 武器区格位数（不含底部操作栏） */
        private const val SHOP_WEAPON_SLOTS = SHOP_SIZE - 9

        /** 补弹费用 = 武器价 × 该比例（向上取整） */
        private const val AMMO_COST_RATE = 0.2

        /** 单把枪补弹最低费用 */
        private const val MIN_AMMO_COST = 10
    }

    /** 武器集成端口：组合根注入 QA 实现；无法注入时（纯单测）补弹按“持有”处理。 */
    private val integration: cn.oneachina.zombierun.v2.ports.WeaponIntegrationPort? =
        cn.oneachina.zombierun.v2.infrastructure.bukkit.weapon.QaWeaponIntegrationPort(logger)

    private val slots = ConcurrentHashMap<UUID, Map<Int, (Player, InventoryClickEvent) -> Unit>>()
    private class Holder(val menuId: String) : InventoryHolder {
        lateinit var backingInventory: Inventory
        override fun getInventory(): Inventory = backingInventory
    }

    /** 是否为 v2 自有 GUI 容器；用于保护监听精确拦截，避免影响第三方 GUI。 */
    fun isV2Holder(holder: org.bukkit.inventory.InventoryHolder?): Boolean = holder is Holder

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
                    "感染: ${profile.totalInfections}",
                    "场次: ${profile.gamesPlayed}",
                    "人类胜利: ${profile.humanWins}",
                    "已解锁称号: ${profile.unlockedTitles.size}",
                ),
            ),
        )
        register(player, inv, "profile", mapOf(26 to { p, _ -> p.closeInventory() }))
    }

    fun openShop(player: Player) {
        val items = weapons.all()
        val holder = Holder("shop")
        val inv = inventoryFactory(holder, SHOP_SIZE, Component.text("武器商店"))
        holder.backingInventory = inv
        val actions = mutableMapOf<Int, (Player, InventoryClickEvent) -> Unit>()

        // 上半区：武器（点击立即购买并发放）
        items.forEachIndexed { index, weapon ->
            val slot = index
            if (slot >= SHOP_WEAPON_SLOTS) return@forEachIndexed
            inv.setItem(
                slot,
                icon(
                    Material.DIAMOND_SWORD,
                    weapon.displayName,
                    listOf(
                        "类型: ${weapon.type}",
                        "分类: ${weapon.category.name.lowercase()}",
                        "价格: ${weapon.price.toInt()} 硬币",
                        if (weapon.enabled) "§a点击购买并发放" else "§c暂不可购买",
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
                            // 购买即送 1 个弹匣的弹药，保证新枪能直接开火
                            weapons.refillAmmo(p.uniqueId, weapon.id, 1)
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

        // 底部操作栏：补弹按钮（仅枪械）+ 关闭
        val guns = items.filter { it.category == WeaponCategory.GUN && it.enabled }
        val ammoSlot = SHOP_SIZE - 5
        if (guns.isNotEmpty()) {
            inv.setItem(
                ammoSlot,
                icon(
                    Material.SPECTRAL_ARROW,
                    "补充弹药",
                    listOf(
                        "为背包中的枪械补充 1 个弹匣",
                        "价格: 枪械价 ×1 的 20%（向上取整，最低 10 硬币）",
                        "§a点击补充",
                    ),
                ),
            )
            actions[ammoSlot] = { p, _ -> buyAmmoRefill(p) }
        }
        val closeSlot = SHOP_SIZE - 1
        inv.setItem(closeSlot, icon(Material.BARRIER, "关闭", listOf("§7点击关闭商店")))
        actions[closeSlot] = { p, _ -> p.closeInventory() }

        register(player, inv, "shop", actions)
    }

    /**
     * 局内补弹：遍历玩家背包中的 QA 枪械，按武器配置价格换算弹药费。
     * 费用 = Σ(武器价 × 20%)，向上取整、单把最低 10 硬币。
     */
    private fun buyAmmoRefill(player: Player) {
        val id = player.uniqueId
        val heldWeapons = weapons.all()
            .filter { it.category == WeaponCategory.GUN && it.enabled }
            .filter { integrationHolds(player, it) }
        if (heldWeapons.isEmpty()) {
            player.sendMessage(Component.text("背包中没有可补弹的枪械", NamedTextColor.RED))
            return
        }
        var cost = 0
        heldWeapons.forEach { w ->
            val unit = kotlin.math.ceil(w.price * AMMO_COST_RATE).toInt().coerceIn(MIN_AMMO_COST, Int.MAX_VALUE)
            cost += unit
        }
        val afterSpend = playerData.spendCoins(id, cost)
        if (afterSpend == null) {
            player.sendMessage(Component.text("硬币不足，补弹需要 $cost 硬币", NamedTextColor.RED))
            return
        }
        var refilled = 0
        heldWeapons.forEach { w ->
            if (weapons.refillAmmo(id, w.id, 1)) refilled++
        }
        if (refilled == 0) {
            // 全部补弹失败，退款
            playerData.addCoins(id, cost)
            player.sendMessage(Component.text("弹药系统不可用，已退还 $cost 硬币", NamedTextColor.RED))
            return
        }
        val refund = if (refilled < heldWeapons.size) {
            // 部分失败按比例退款
            val back = cost * (heldWeapons.size - refilled) / heldWeapons.size
            if (back > 0) playerData.addCoins(id, back)
            back
        } else 0
        val msg = if (refund > 0) "已为 $refilled 把枪补弹，花费 ${cost - refund} 硬币（退款 $refund）"
        else "已为 $refilled 把枪补弹，花费 $cost 硬币"
        player.sendMessage(Component.text(msg, NamedTextColor.GREEN))
    }

    private fun integrationHolds(player: Player, weapon: cn.oneachina.zombierun.v2.domain.weapon.WeaponDefinition): Boolean =
        integration?.holdsWeapon(player.uniqueId, weapon.type) ?: true

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
        val profile = playerData.profileOf(player.uniqueId)
        val list = titleCatalog()
        val holder = Holder("titles")
        val rows = ((list.size + 8) / 9).coerceAtMost(6).coerceAtLeast(1)
        val inv = inventoryFactory(holder, rows * 9, Component.text("选择称号"))
        holder.backingInventory = inv
        val actions = mutableMapOf<Int, (Player, InventoryClickEvent) -> Unit>()

        list.forEachIndexed { index, def ->
            val slot = index
            if (slot >= rows * 9) return@forEachIndexed
            val current = profile.title
            val selected = current == def.name
            val unlocked = profile.isTitleUnlocked(def.name) || profile.level >= def.level
            inv.setItem(
                slot,
                icon(
                    when {
                        selected -> Material.NAME_TAG
                        unlocked -> Material.PAPER
                        else -> Material.GRAY_DYE
                    },
                    def.name,
                    listOf(
                        "需要等级: ${def.level}",
                        when {
                            selected -> "§a当前称号"
                            unlocked -> "§e点击使用"
                            else -> "§c未解锁"
                        },
                    ),
                ),
            )
            actions[slot] = { p, _ ->
                if (unlocked) {
                    // 确保解锁状态已持久化
                    playerData.unlockTitle(p.uniqueId, def.name)
                    playerData.setTitle(p.uniqueId, def.name)
                    p.sendMessage(Component.text("已设置称号：${def.name}", NamedTextColor.GREEN))
                    p.closeInventory()
                } else {
                    p.sendMessage(Component.text("需要达到等级 ${def.level} 才能解锁该称号", NamedTextColor.RED))
                }
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

    /** 称号目录（含解锁等级，与 PlayerDataService.TITLE_LEVELS 保持一致）。 */
    private fun titleCatalog(): List<TitleDef> = listOf(
        TitleDef("新人", 1),
        TitleDef("跑酷者", 5),
        TitleDef("门之守护者", 10),
        TitleDef("僵尸杀手", 15),
        TitleDef("逃生专家", 20),
        TitleDef("金色传说", 30),
    )

    private data class TitleDef(val name: String, val level: Int)

    private fun register(player: Player, inv: Inventory, menuId: String, actions: Map<Int, (Player, InventoryClickEvent) -> Unit>) {
        slots[player.uniqueId] = actions
        player.openInventory(inv)
        logger.debug("gui", "opened $menuId for ${player.name}")
    }

    private fun icon(material: Material, name: String, lore: List<String>): ItemStack =
        iconFactory(material, name, lore)
}