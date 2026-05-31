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

    private val playerCoins = ConcurrentHashMap<Player, Int>()
    private val playerKills = ConcurrentHashMap<Player, Int>()
    private val playerInfections = ConcurrentHashMap<Player, Int>()
    private val selectedWeapon = ConcurrentHashMap<Player, String>()
    private val lastHealth = ConcurrentHashMap<Player, Double>()

    fun getSelectableWeapons(): List<String> {
        if (!plugin.weaponMechanicsAvailable) return emptyList()
        return loadWeaponTitlesFromWeaponMechanics()
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
        if (!plugin.weaponMechanicsAvailable) {
            player.sendMessage("§c武器系统不可用（未安装WeaponMechanics）")
            giveFallbackSword(player)
            return
        }
        player.inventory.clear()
        val weapons = getSelectableWeapons()
        if (weapons.isEmpty()) {
            player.sendMessage("§c未找到可用枪械配置。")
            giveFallbackSword(player)
            return
        }

        val selected = selectedWeapon[player]
        val weaponTitle = if (selected != null && weapons.contains(selected)) {
            val price = getWeaponPrice(selected)
            if (takeCoins(player, price)) {
                player.sendMessage("§a购买成功！花费硬币: $price，剩余: ${getCoins(player)}")
                selected
            } else {
                player.sendMessage("§c硬币不足，已改为随机枪械。")
                weapons.random()
            }
        } else {
            weapons.random()
        }

        if (!giveWeaponDirectly(player, weaponTitle)) {
            player.sendMessage("§c发放枪械失败：$weaponTitle")
            giveFallbackSword(player)
            return
        }

        giveFallbackSword(player)
        giveWeaponAmmo(player, weaponTitle)
    }

    private fun giveFallbackSword(player: Player) {
        val sword = ItemStack(Material.IRON_SWORD)
        val meta = sword.itemMeta
        meta?.addEnchant(Enchantment.KNOCKBACK, 1, true)
        meta?.displayName(Component.text("§c匕首"))
        sword.itemMeta = meta
        player.inventory.addItem(sword)
    }

    private fun getWeaponPrice(weaponTitle: String): Int {
        val config = plugin.configManager.getConfig()
        val defaultPrice = config.getInt("weapons.default-price", 600)
        val prices = config.getConfigurationSection("weapons.prices")?.getValues(false) ?: emptyMap()
        val price = when (val value = prices[weaponTitle]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
        return price ?: defaultPrice
    }

    private fun loadWeaponTitlesFromWeaponMechanics(): List<String> {
        return WeaponHelper.getInstance().loadWeaponTitles()
    }

    private fun giveWeaponDirectly(player: Player, weaponTitle: String): Boolean {
        return WeaponHelper.getInstance().giveOrDropWeapon(weaponTitle, player)
    }

    private fun giveWeaponAmmo(player: Player, weaponTitle: String) {
        val helper = WeaponHelper.getInstance()
        if (!helper.isAvailable()) return
        runCatching {
            val inv = player.inventory
            val weaponStack = inv.contents
                .asSequence()
                .filterNotNull()
                .firstOrNull { helper.getWeaponTitle(it) == weaponTitle }
                ?: return

            val ammoData = helper.getCurrentAmmo(weaponStack) ?: return
            val ammoTitle = ammoData.ammoTitle

            val magazineItem = helper.generateAmmo(ammoTitle, true)
            val bulletItem = helper.generateAmmo(ammoTitle, false)

            when {
                magazineItem != null -> {
                    setOrAddStacks(inv, magazineItem, amount = 3, preferredSlots = 9..13)
                }
                bulletItem != null -> {
                    val stacks = 5
                    val stackSize = bulletItem.maxStackSize.coerceAtLeast(1)
                    setOrAddStacks(inv, bulletItem, amount = stacks * stackSize, preferredSlots = 9..13)
                }
                else -> Unit
            }
        }
    }

    private fun setOrAddStacks(
        inv: org.bukkit.inventory.PlayerInventory,
        template: ItemStack,
        amount: Int,
        preferredSlots: IntRange
    ) {
        var remaining = amount
        val maxStack = template.maxStackSize.coerceAtLeast(1)

        for (slot in preferredSlots) {
            if (remaining <= 0) break
            if (inv.getItem(slot) != null) continue
            val give = minOf(remaining, maxStack)
            inv.setItem(slot, template.clone().apply { this.amount = give })
            remaining -= give
        }

        while (remaining > 0) {
            val give = minOf(remaining, maxStack)
            inv.addItem(template.clone().apply { this.amount = give })
            remaining -= give
        }
    }

    fun giveStarterKit(player: Player) {
        giveRandomGun(player)
    }

    fun addCoins(player: Player, amount: Int) {
        val current = playerCoins.getOrDefault(player, 0)
        playerCoins[player] = current + amount
        player.sendMessage(Component.text("+ $amount 硬币!").color(NamedTextColor.GOLD))
    }

    fun getCoins(player: Player): Int = playerCoins.getOrDefault(player, 0)

    fun takeCoins(player: Player, amount: Int): Boolean {
        val current = getCoins(player)
        if (current < amount) return false
        playerCoins[player] = current - amount
        return true
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
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                val before = lastHealth.remove(victim) ?: return@Runnable
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
        }
    }

    fun teleportToLobby(player: Player) {
        plugin.respawnManager.teleportToWaitRespawn(player)
        plugin.gameManager.setPlayerTeam(player, GameManager.Team.SPECTATOR)
        player.sendMessage("§a你已传送回大厅")
    }

    fun clear() {
        playerCoins.clear()
        playerKills.clear()
        playerInfections.clear()
        selectedWeapon.clear()
        lastHealth.clear()
    }
}
