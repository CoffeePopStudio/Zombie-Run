package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.AmmoConfig
import cn.oneachina.zombieRun.model.WeaponConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WeaponManager(private val plugin: ZombieRun) {

    private val weaponIdKey = NamespacedKey("zombie-run", "weapon_id")
    private val ammoKey = NamespacedKey("zombie-run", "ammo")

    private var weapons: Map<String, WeaponConfig> = emptyMap()
    private var ammoItems: Map<String, AmmoConfig> = emptyMap()
    private val cooldowns = ConcurrentHashMap<UUID, Int>()

    fun loadWeapons() {
        weapons = plugin.configManager.loadWeaponConfigs()
        ammoItems = plugin.configManager.loadAmmoConfigs()
        plugin.logger.info("已加载 ${weapons.size} 把武器, ${ammoItems.size} 种弹药")
    }

    fun getWeaponConfig(id: String): WeaponConfig? = weapons[id]

    fun getAllWeaponConfigs(): Collection<WeaponConfig> = weapons.values

    fun getWeaponIds(): List<String> = weapons.keys.toList()

    fun buildWeaponItem(id: String): ItemStack? {
        val config = weapons[id] ?: return null
        val material = Material.matchMaterial(config.material) ?: Material.WOODEN_HOE
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return null
        val customModelDataComponent = meta.customModelDataComponent
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize(config.name.replace("&", "§")))
        val lore: MutableList<Component> = config.lore
            .map { LegacyComponentSerializer.legacySection().deserialize(it.replace("&", "§")) }
            .toMutableList()
        meta.lore(lore)
        if (!config.customModelData.floats().isEmpty()) {
            customModelDataComponent.floats = config.customModelData.floats()
            meta.setCustomModelDataComponent(customModelDataComponent)
        }
        meta.persistentDataContainer.set(weaponIdKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(ammoKey, PersistentDataType.INTEGER, config.maxAmmo)
        item.itemMeta = meta
        return item
    }

    fun giveWeapon(player: Player, weaponId: String): Boolean {
        val item = buildWeaponItem(weaponId) ?: return false
        if (player.inventory.firstEmpty() == -1) {
            player.sendMessage(Component.text("背包已满，无法接收武器", NamedTextColor.RED))
            return false
        }
        player.inventory.addItem(item)
        return true
    }

    fun isZombieRunWeapon(item: ItemStack): Boolean {
        val pdc = item.itemMeta?.persistentDataContainer ?: return false
        return pdc.has(weaponIdKey, PersistentDataType.STRING)
    }

    fun getWeaponId(item: ItemStack): String? {
        return item.itemMeta?.persistentDataContainer?.get(weaponIdKey, PersistentDataType.STRING)
    }

    fun handleShoot(player: Player, item: ItemStack): Boolean {
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return false
        if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return false

        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(weaponIdKey, PersistentDataType.STRING) ?: return false
        val config = weapons[weaponId] ?: return false
        var ammo = pdc.get(ammoKey, PersistentDataType.INTEGER) ?: 0

        if (ammo <= 0) {
            player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.5f, 1.5f)
            player.sendActionBar(Component.text("弹药耗尽", NamedTextColor.RED))
            return false
        }

        val now = plugin.server.currentTick
        val lastShot = cooldowns.getOrDefault(player.uniqueId, 0)
        if (now - lastShot < config.cooldownTicks) return false
        cooldowns[player.uniqueId] = now

        val world = player.world
        val eyeLoc = player.eyeLocation
        val direction = eyeLoc.direction
        val maxRange = config.range.toDouble()

        for (i in 0 until config.pellets) {
            val spreadDir = if (config.pellets > 1) {
                val yawOffset = (Math.random() - 0.5) * 0.3
                val pitchOffset = (Math.random() - 0.5) * 0.3
                val loc = eyeLoc.clone()
                loc.yaw += Math.toDegrees(yawOffset).toFloat()
                loc.pitch += Math.toDegrees(pitchOffset).toFloat()
                loc.direction
            } else {
                direction
            }

            val rayTrace = world.rayTraceEntities(
                eyeLoc, spreadDir, maxRange, 0.1
            ) { it is Player && it != player }
            if (rayTrace != null) {
                val target = rayTrace.hitEntity as? Player ?: continue
                val targetTeam = plugin.gameManager.getPlayerTeam(target)
                if (targetTeam == GameManager.Team.ZOMBIE || targetTeam == GameManager.Team.ZOMBIE_MAIN) {
                    target.damage(config.damage)
                    if (config.knockback > 0) {
                        target.velocity = target.velocity.add(
                            spreadDir.clone().multiply(config.knockback)
                        )
                    }
                }
            }
        }

        if (config.sound != null) {
            try {
                val sound = Sound.valueOf(config.sound.uppercase())
                player.playSound(player.location, sound, 0.8f, 1.2f)
            } catch (_: IllegalArgumentException) {}
        }

        ammo -= 1
        pdc.set(ammoKey, PersistentDataType.INTEGER, ammo)
        item.itemMeta = meta

        val barColor = when {
            ammo.toDouble() / config.maxAmmo > 0.5 -> NamedTextColor.GREEN
            ammo.toDouble() / config.maxAmmo > 0.25 -> NamedTextColor.YELLOW
            else -> NamedTextColor.RED
        }
        player.sendActionBar(
            Component.text(ammo, barColor)
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(config.maxAmmo))
        )
        return true
    }

    fun reloadWeapon(player: Player, weaponStack: ItemStack): Boolean {
        val meta = weaponStack.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(weaponIdKey, PersistentDataType.STRING) ?: return false
        val config = weapons[weaponId] ?: return false
        var ammo = pdc.get(ammoKey, PersistentDataType.INTEGER) ?: 0

        if (ammo >= config.maxAmmo) {
            player.sendActionBar(Component.text("弹药已满", NamedTextColor.GREEN))
            return false
        }

        val inv = player.inventory
        var foundAmmoSlot = -1
        for (i in 0 until inv.size) {
            val stack = inv.getItem(i) ?: continue
            val ammoMeta = stack.itemMeta ?: continue
            val stackAmmoType = ammoMeta.persistentDataContainer.get(
                NamespacedKey("zombie-run", "ammo_type"), PersistentDataType.STRING
            ) ?: continue
            if (stackAmmoType == config.ammoType) {
                foundAmmoSlot = i
                break
            }
        }

        if (foundAmmoSlot == -1) {
            player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.5f, 1.5f)
            player.sendActionBar(Component.text("没有匹配的弹药", NamedTextColor.RED))
            return false
        }

        ammo = config.maxAmmo
        pdc.set(ammoKey, PersistentDataType.INTEGER, ammo)
        weaponStack.itemMeta = meta

        val ammoStack = inv.getItem(foundAmmoSlot)
        if (ammoStack != null && ammoStack.amount > 1) {
            ammoStack.amount -= 1
        } else {
            inv.setItem(foundAmmoSlot, null)
        }

        player.playSound(player.location, Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 1.5f)
        player.sendActionBar(
            Component.text("装填完成 ", NamedTextColor.GREEN)
                .append(Component.text(ammo, NamedTextColor.GRAY))
                .append(Component.text(" / "))
                .append(Component.text(config.maxAmmo))
        )
        return true
    }

    fun buildAmmoItem(ammoId: String, amount: Int): ItemStack? {
        val config = ammoItems[ammoId] ?: return null
        val material = Material.matchMaterial(config.material) ?: Material.PAPER
        val item = ItemStack(material, amount)
        val meta = item.itemMeta ?: return null
        val customModelDataComponent = meta.customModelDataComponent
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize(config.name.replace("&", "§")))
        if (config.lore.isNotEmpty()) {
            meta.lore = config.lore.map { it.replace("&", "§") }
        }
        if (!config.customModelData.floats().isEmpty()) {
            customModelDataComponent.floats = config.customModelData.floats()
            meta.setCustomModelDataComponent(customModelDataComponent)
        }
        meta.persistentDataContainer.set(
            NamespacedKey("zombie-run", "ammo_type"), PersistentDataType.STRING, ammoId
        )
        item.itemMeta = meta
        return item
    }
}
