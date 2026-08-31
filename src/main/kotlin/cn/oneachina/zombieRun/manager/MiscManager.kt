package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap

class MiscManager(private val plugin: ZombieRun) : Listener {

    private val playerKills = ConcurrentHashMap<Player, Int>()
    private val playerInfections = ConcurrentHashMap<Player, Int>()
    private val selectedWeapon = ConcurrentHashMap<Player, String>()
    private val lastHealth = ConcurrentHashMap<Player, Double>()

    fun getSelectableWeapons(): List<String> {
        return plugin.weaponManager.getAllItemIds()
    }

    fun setSelectedWeapon(player: Player, weaponIndex: Int): Boolean {
        val weapons = getSelectableWeapons()
        if (weaponIndex !in 1..weapons.size) {
            return false
        }
        selectedWeapon[player] = weapons[weaponIndex - 1]
        return true
    }

    fun clearSelectedWeapon(player: Player) {
        selectedWeapon.remove(player)
    }

    fun getSelectedWeapon(player: Player): String? = selectedWeapon[player]

    fun giveRandomGunToAllHumans() {
        Bukkit.getOnlinePlayers().forEach { player ->
            if (plugin.gameManager.getPlayerTeam(player) == GameManager.Team.HUMAN) {
                giveRandomGun(player)
            }
        }
    }

    fun giveRandomGun(player: Player) {
        val itemIds = plugin.weaponManager.getAllItemIds()
        if (itemIds.isEmpty()) {
            player.sendMessage(Component.text("未找到可用枪械/道具配置。", NamedTextColor.RED))
            giveFallbackSword(player)
            return
        }

        val selected = selectedWeapon[player]
        val itemId = if (selected != null && itemIds.contains(selected)) {
            val price = plugin.weaponManager.getWeaponPrice(selected).toInt()
            if (plugin.coinManager.takeCoins(player.uniqueId, price)) {
                val remaining = plugin.coinManager.getCoins(player.uniqueId)
                player.sendMessage(Component.text("购买成功！花费硬币: $price，剩余: $remaining", NamedTextColor.GREEN))
                selected
            } else {
                player.sendMessage(Component.text("硬币不足，已改为随机物品。", NamedTextColor.RED))
                itemIds.random()
            }
        } else {
            itemIds.random()
        }

        val isGun = itemId in plugin.weaponManager.getWeaponIds()
        val ok = if (isGun) {
            plugin.weaponManager.giveWeapon(player, itemId)
        } else {
            plugin.weaponManager.giveGadget(player, itemId)
        }

        if (!ok) {
            player.sendMessage(Component.text("发放物品失败：$itemId", NamedTextColor.RED))
            giveFallbackSword(player)
            return
        }

        giveFallbackSword(player)
        if (isGun) {
            plugin.weaponManager.giveAmmoRespectingMaxReserve(player, itemId)
        }
    }

    private fun giveFallbackSword(player: Player) {
        val sword = ItemStack(Material.IRON_SWORD)
        val meta = sword.itemMeta
        meta?.addEnchant(Enchantment.KNOCKBACK, 1, true)
        meta?.displayName(Component.text("匕首", NamedTextColor.RED))
        sword.itemMeta = meta
        player.inventory.addItem(sword)
    }

    fun giveStarterKit(player: Player) {
        giveRandomGun(player)
    }

    fun addKill(player: Player) {
        playerKills[player] = playerKills.getOrDefault(player, 0) + 1
    }

    fun getKills(player: Player): Int = playerKills.getOrDefault(player, 0)

    fun addInfection(player: Player) {
        playerInfections[player] = playerInfections.getOrDefault(player, 0) + 1
    }

    fun getInfections(player: Player): Int = playerInfections.getOrDefault(player, 0)

    fun getAllKills(): Map<Player, Int> = playerKills.toMap()
    fun getAllInfections(): Map<Player, Int> = playerInfections.toMap()

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        if (plugin.gameManager.getPlayerTeam(victim) != GameManager.Team.HUMAN) return

        if (event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            // 使用配置中的爆炸伤害减免倍率（在 NORMAL 阶段修改才会被其他监听器正确读取）
            event.damage *= plugin.configManager.getExplosionDamageReduction()
        }

        val causingEntity = event.damageSource.causingEntity
        // 环境伤害（掉落/火焰等）按实际伤害直接扣体力：
        // - 过滤 ENTITY_ATTACK（僵尸/玩家近战走 CombatListener 自定义血量）
        // - 过滤 CUSTOM（HealthManager 红闪 player.damage(0.01) 触发，不扣体力）
        // 不再依赖 1 tick 延迟读原版血量差，避免与红闪/回血竞争
        if (causingEntity == null &&
            event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
            event.cause != EntityDamageEvent.DamageCause.CUSTOM
        ) {
            val damage = event.finalDamage
            if (damage > 0) {
                plugin.staminaManager.deductStamina(victim, 2.0 * damage)
            }
        }
    }

    fun teleportToLobby(player: Player) {
        plugin.respawnManager.teleportToWaitRespawn(player)
        plugin.gameManager.setPlayerTeam(player, GameManager.Team.SPECTATOR)
        player.sendMessage(Component.text("你已传送回大厅", NamedTextColor.GREEN))
    }

    fun clear() {
        playerKills.clear()
        playerInfections.clear()
        selectedWeapon.clear()
    }
}
