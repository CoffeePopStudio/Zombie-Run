package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
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
        return plugin.weaponManager.getWeaponIds()
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
        val weaponIds = plugin.weaponManager.getWeaponIds()
        if (weaponIds.isEmpty()) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c未找到可用枪械配置。"))
            giveFallbackSword(player)
            return
        }
        player.inventory.clear()

        val selected = selectedWeapon[player]
        val weaponId = if (selected != null && weaponIds.contains(selected)) {
            val config = plugin.weaponManager.getWeaponConfig(selected)
            val price = config?.price ?: 600
            if (plugin.coinManager.takeCoins(player.uniqueId, price)) {
                val remaining = plugin.coinManager.getCoins(player.uniqueId)
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a购买成功！花费硬币: $price，剩余: $remaining"))
                selected
            } else {
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c硬币不足，已改为随机枪械。"))
                weaponIds.random()
            }
        } else {
            weaponIds.random()
        }

        if (!plugin.weaponManager.giveWeapon(player, weaponId)) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c发放枪械失败：$weaponId"))
            giveFallbackSword(player)
            return
        }

        giveFallbackSword(player)
        giveAmmoForWeapon(player, weaponId)
    }

    private fun giveAmmoForWeapon(player: Player, weaponId: String) {
        val config = plugin.weaponManager.getWeaponConfig(weaponId) ?: return
        val ammoItem = plugin.weaponManager.buildAmmoItem(config.ammoType, 64)
        if (ammoItem != null) {
            player.inventory.addItem(ammoItem.clone().apply { amount = 64 })
            player.inventory.addItem(ammoItem.clone().apply { amount = 64 })
            player.inventory.addItem(ammoItem.clone().apply { amount = 64 })
        }
    }

    private fun giveFallbackSword(player: Player) {
        val sword = ItemStack(Material.IRON_SWORD)
        val meta = sword.itemMeta
        meta?.addEnchant(Enchantment.KNOCKBACK, 1, true)
        meta?.displayName(LegacyComponentSerializer.legacySection().deserialize("§c匕首"))
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        if (plugin.gameManager.getPlayerTeam(victim) != GameManager.Team.HUMAN) return

        if (event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            event.damage = 0.05
        }

        val causingEntity = event.damageSource.causingEntity
        if (causingEntity == null && event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            lastHealth[victim] = victim.health
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
                val before = lastHealth.remove(victim) ?: return@runDelayed
                val after = victim.health
                val damage = before - after
                if (damage > 0) {
                    plugin.staminaManager.deductStamina(victim, 15.0 * damage)
                }
            }, 1L)
        }
    }

    @EventHandler
    fun onCombat(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        val victim = event.entity as? Player ?: return

        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return

        val attackerTeam = plugin.gameManager.getPlayerTeam(attacker)
        val victimTeam = plugin.gameManager.getPlayerTeam(victim)

        if (attackerTeam == GameManager.Team.HUMAN &&
            (victimTeam == GameManager.Team.ZOMBIE || victimTeam == GameManager.Team.ZOMBIE_MAIN)) {

            victim.velocity = victim.velocity.add(attacker.location.direction.setY(-1.0).normalize().multiply(0.3))

            val damage = event.finalDamage * 2
            attacker.sendActionBar(Component.text("造成伤害: ${String.format("%.1f", damage)}").color(NamedTextColor.RED))
            plugin.progressionListener.onDealDamage(attacker, event.finalDamage)
        }
    }

    fun teleportToLobby(player: Player) {
        plugin.respawnManager.teleportToWaitRespawn(player)
        plugin.gameManager.setPlayerTeam(player, GameManager.Team.SPECTATOR)
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a你已传送回大厅"))
    }

    fun clear() {
        playerKills.clear()
        playerInfections.clear()
        selectedWeapon.clear()
        lastHealth.clear()
    }
}
