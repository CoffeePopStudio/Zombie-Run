package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.AmmoCategory
import cn.oneachina.zombieRun.model.WeaponConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class WeaponManager(private val plugin: ZombieRun) {

    private val weaponIdKey = NamespacedKey("zombie-run", "weapon_id")
    private val magazineKey = NamespacedKey("zombie-run", "magazine")
    private val ammoCatKey = NamespacedKey("zombie-run", "ammo_cat")
    private val reloadKey = NamespacedKey("zombie-run", "reloading")
    private val shotCountKey = NamespacedKey("zombie-run", "shot_count")

    private var weapons: Map<String, WeaponConfig> = emptyMap()
    private var ammoCategories: Map<String, AmmoCategory> = emptyMap()
    private val cooldowns = ConcurrentHashMap<UUID, Int>()
    private val adsState = ConcurrentHashMap<UUID, Boolean>()
    private val adsStartTime = ConcurrentHashMap<UUID, Long>()
    private val autoFireTasks = ConcurrentHashMap<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask?>()
    private val reloadTasks = ConcurrentHashMap<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask?>()

    fun loadWeapons() {
        weapons = plugin.configManager.loadWeaponConfigs()
        ammoCategories = plugin.configManager.loadAmmoCategories()
        plugin.logger.info("已加载 ${weapons.size} 把武器, ${ammoCategories.size} 种弹药类别")
    }

    fun getWeaponConfig(id: String): WeaponConfig? = weapons[id]
    fun getAllWeaponConfigs(): Collection<WeaponConfig> = weapons.values
    fun getWeaponIds(): List<String> = weapons.keys.toList()
    fun getAmmoCategory(id: String): AmmoCategory? = ammoCategories[id]

    fun buildWeaponItem(id: String): ItemStack? {
        val config = weapons[id] ?: return null
        val material = Material.matchMaterial(config.material) ?: Material.WOODEN_HOE
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return null
        val cmdComp = meta.customModelDataComponent
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize(config.name.replace("&", "§")))
        val lore: MutableList<Component> = config.lore
            .map { LegacyComponentSerializer.legacySection().deserialize(it.replace("&", "§")) }
            .toMutableList()
        meta.lore(lore)
        if (!config.customModelData.floats().isEmpty()) {
            cmdComp.floats = config.customModelData.floats()
            meta.setCustomModelDataComponent(cmdComp)
        }
        meta.persistentDataContainer.set(weaponIdKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(magazineKey, PersistentDataType.INTEGER, config.magazineSize)
        meta.persistentDataContainer.set(ammoCatKey, PersistentDataType.STRING, config.ammoCategory)
        meta.persistentDataContainer.set(reloadKey, PersistentDataType.INTEGER, 0)
        meta.persistentDataContainer.set(shotCountKey, PersistentDataType.INTEGER, 0)
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
        if (!plugin.debugMode) {
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return false
            if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return false
        }

        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(weaponIdKey, PersistentDataType.STRING) ?: return false
        val config = weapons[weaponId] ?: return false
        var magazine = pdc.get(magazineKey, PersistentDataType.INTEGER) ?: 0
        var shotCount = pdc.get(shotCountKey, PersistentDataType.INTEGER) ?: 0
        val reloading = (pdc.get(reloadKey, PersistentDataType.INTEGER) ?: 0) > 0

        if (reloading || magazine <= 0) {
            if (magazine <= 0 && !reloading) {
                player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.5f, 1.5f)
                player.sendActionBar(Component.text("弹药耗尽", NamedTextColor.RED))
            }
            return false
        }

        val now = plugin.server.currentTick
        val lastShot = cooldowns.getOrDefault(player.uniqueId, 0)
        if (now - lastShot < config.cooldownTicks) return false
        cooldowns[player.uniqueId] = now

        val ads = adsState.getOrDefault(player.uniqueId, false)
        val currentSpreadMultiplier = if (ads) config.adsSpreadMult else 1.0
        val spreadFromRecoil = shotCount * config.spreadPerShot
        val finalSpread = (config.spread + spreadFromRecoil) * currentSpreadMultiplier

        val eyeLoc = player.eyeLocation
        val baseDir = eyeLoc.direction

        for (i in 0 until config.pellets) {
            val spreadDir = applySpreadAndRecoil(baseDir, config, shotCount, ads, finalSpread, config.pellets > 1)
            val rayTrace = player.world.rayTraceEntities(eyeLoc, spreadDir, config.range.toDouble(), 0.1) { it is Player && it != player }
            if (rayTrace != null) {
                val target = rayTrace.hitEntity as? Player ?: continue
                val targetTeam = plugin.gameManager.getPlayerTeam(target)
                if (targetTeam == GameManager.Team.ZOMBIE || targetTeam == GameManager.Team.ZOMBIE_MAIN) {
                    val isHeadshot = checkHeadshot(target, rayTrace.hitPosition.y)
                    val dmg = config.damage * (if (isHeadshot) config.headshotMult else 1.0)

                    target.scheduler.run(plugin, { _ ->
                        val scale = cn.oneachina.zombieRun.listener.CombatListener.getDamageScale(targetTeam)
                        target.damage(dmg * scale)
                        if (config.knockback > 0) {
                            target.velocity = target.velocity.add(spreadDir.clone().multiply(config.knockback))
                        }
                        if (isHeadshot) {
                            target.world.spawnParticle(Particle.CRIT, target.location.clone().add(0.0, target.eyeHeight - 0.2, 0.0), 5, 0.3, 0.3, 0.3, 0.0)
                        }
                    }, null)

                    target.sendActionBar(LegacyComponentSerializer.legacySection().deserialize("§c-${dmg.toInt()}"))
                    if (isHeadshot) {
                        plugin.progressionManager.addXp(player, 5, "爆头")
                    }
                }
                if (config.hitSound != null) {
                    try {
                        val s = Sound.valueOf(config.hitSound.uppercase())
                        player.playSound(player.location, s, 0.5f, 1.5f)
                    } catch (_: IllegalArgumentException) {}
                }
            }
        }

        if (config.sound != null) {
            try {
                val s = Sound.valueOf(config.sound.uppercase())
                player.playSound(player.location, s, 0.8f, 1.2f)
            } catch (_: IllegalArgumentException) {}
        }

        magazine -= 1
        shotCount += 1
        pdc.set(magazineKey, PersistentDataType.INTEGER, magazine)
        pdc.set(shotCountKey, PersistentDataType.INTEGER, shotCount)
        item.itemMeta = meta

        val barColor = when {
            magazine.toDouble() / config.magazineSize > 0.5 -> NamedTextColor.GREEN
            magazine.toDouble() / config.magazineSize > 0.25 -> NamedTextColor.YELLOW
            else -> NamedTextColor.RED
        }
        player.sendActionBar(
            Component.text(magazine, barColor)
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(config.magazineSize))
        )
        return true
    }

    private fun applySpreadAndRecoil(
        baseDir: org.bukkit.util.Vector,
        config: WeaponConfig,
        shotCount: Int,
        ads: Boolean,
        spreadRad: Double,
        multiPellet: Boolean
    ): org.bukkit.util.Vector {
        val dir = baseDir.clone()

        val recoilIdx = (shotCount % config.recoil.size).coerceIn(0, config.recoil.size - 1)
        val recoilAngle = config.recoil[recoilIdx] * (if (ads) config.adsRecoilMult else 1.0)
        dir.y += Math.toRadians(recoilAngle)

        if (multiPellet) {
            val yawOffset = (Math.random() - 0.5) * spreadRad * 2
            val pitchOffset = (Math.random() - 0.5) * spreadRad * 2
            val loc = org.bukkit.Location(null, 0.0, 0.0, 0.0)
            loc.direction = dir
            loc.yaw += Math.toDegrees(yawOffset).toFloat()
            loc.pitch += Math.toDegrees(pitchOffset).toFloat()
            return loc.direction
        }

        val yawOffset = (Math.random() - 0.5) * spreadRad
        val pitchOffset = (Math.random() - 0.5) * spreadRad
        val loc = org.bukkit.Location(null, 0.0, 0.0, 0.0)
        loc.direction = dir
        loc.yaw += Math.toDegrees(yawOffset).toFloat()
        loc.pitch += Math.toDegrees(pitchOffset).toFloat()
        return loc.direction
    }

    private fun checkHeadshot(player: Player, hitY: Double): Boolean {
        val feetY = player.location.y
        val eyeY = feetY + player.eyeHeight
        val headBottom = eyeY - 0.4
        val headTop = eyeY + 0.3
        return hitY in headBottom..headTop
    }

    fun handleReload(player: Player, weaponStack: ItemStack): Boolean {
        val meta = weaponStack.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(weaponIdKey, PersistentDataType.STRING) ?: return false
        val config = weapons[weaponId] ?: return false
        val magazine = pdc.get(magazineKey, PersistentDataType.INTEGER) ?: 0

        if (magazine >= config.magazineSize) {
            player.sendActionBar(Component.text("弹匣已满", NamedTextColor.GREEN))
            return false
        }

        if (countAmmoInInventory(player, config.ammoCategory) <= 0) {
            player.sendActionBar(Component.text("没有可用弹药", NamedTextColor.RED))
            return false
        }

        pdc.set(reloadKey, PersistentDataType.INTEGER, 1)
        weaponStack.itemMeta = meta

        val task = player.scheduler.runAtFixedRate(plugin, { t ->
            val currentItem = player.inventory.itemInMainHand
            val currentId = getWeaponId(currentItem)
            if (currentId != weaponId) {
                forceCancelReload(player, weaponStack)
                reloadTasks.remove(player.uniqueId)
                t.cancel()
                return@runAtFixedRate
            }

            val curMeta = currentItem.itemMeta ?: run {
                forceCancelReload(player, weaponStack)
                reloadTasks.remove(player.uniqueId)
                t.cancel()
                return@runAtFixedRate
            }
            val curPdc = curMeta.persistentDataContainer
            var progress = curPdc.get(reloadKey, PersistentDataType.INTEGER) ?: 0
            progress += 1

            if (progress >= config.reloadTimeTicks) {
                val currentMag = curPdc.get(magazineKey, PersistentDataType.INTEGER) ?: 0
                val need = config.magazineSize - currentMag
                val ammoInInv = countAmmoInInventory(player, config.ammoCategory)
                if (ammoInInv <= 0) {
                    curPdc.set(reloadKey, PersistentDataType.INTEGER, 0)
                    currentItem.itemMeta = curMeta
                    player.sendActionBar(Component.text("没有可用弹药", NamedTextColor.RED))
                    reloadTasks.remove(player.uniqueId)
                    t.cancel()
                    return@runAtFixedRate
                }
                val actual = min(need, ammoInInv)
                val newMagazine = currentMag + actual
                consumeAmmoFromInventory(player, config.ammoCategory, actual)

                curPdc.set(magazineKey, PersistentDataType.INTEGER, newMagazine)
                curPdc.set(reloadKey, PersistentDataType.INTEGER, 0)
                currentItem.itemMeta = curMeta

                player.playSound(player.location, Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 1.5f)
                player.sendActionBar(
                    Component.text("装填完成 ", NamedTextColor.GREEN)
                        .append(Component.text(newMagazine, NamedTextColor.GRAY))
                        .append(Component.text(" / "))
                        .append(Component.text(config.magazineSize))
                )
                reloadTasks.remove(player.uniqueId)
                t.cancel()
                return@runAtFixedRate
            }

            curPdc.set(reloadKey, PersistentDataType.INTEGER, progress)
            currentItem.itemMeta = curMeta

            val percent = (progress.toDouble() / config.reloadTimeTicks * 100).toInt()
            val filled = "█".repeat(percent / 5)
            val empty = "░".repeat(20 - percent / 5)
            player.sendActionBar(
                LegacyComponentSerializer.legacySection().deserialize("§e装填中... $filled$empty $percent%")
            )
        }, null, 1L, 1L)

        reloadTasks[player.uniqueId] = task
        return true
    }

    fun cancelReload(player: Player, weaponStack: ItemStack) {
        reloadTasks.remove(player.uniqueId)?.cancel()
        val meta = weaponStack.itemMeta ?: return
        meta.persistentDataContainer.set(reloadKey, PersistentDataType.INTEGER, 0)
        weaponStack.itemMeta = meta
    }

    private fun forceCancelReload(player: Player, weaponStack: ItemStack) {
        val meta = weaponStack.itemMeta ?: return
        meta.persistentDataContainer.set(reloadKey, PersistentDataType.INTEGER, 0)
        weaponStack.itemMeta = meta
    }

    private fun countAmmoInInventory(player: Player, category: String): Int {
        var total = 0
        for (item in player.inventory.contents) {
            if (item == null) continue
            val ammoCat = item.itemMeta?.persistentDataContainer?.get(ammoCatKey, PersistentDataType.STRING)
            if (ammoCat == category) total += item.amount
        }
        return total
    }

    private fun consumeAmmoFromInventory(player: Player, category: String, amount: Int) {
        var remaining = amount
        val inv = player.inventory
        for (i in 0 until inv.size) {
            if (remaining <= 0) break
            val item = inv.getItem(i) ?: continue
            val ammoCat = item.itemMeta?.persistentDataContainer?.get(ammoCatKey, PersistentDataType.STRING)
            if (ammoCat == category) {
                val take = min(remaining, item.amount)
                if (item.amount <= take) {
                    inv.setItem(i, null)
                } else {
                    item.amount -= take
                }
                remaining -= take
            }
        }
    }

    fun buildAmmoItem(category: String, amount: Int): ItemStack? {
        val cat = ammoCategories[category] ?: return null
        val material = Material.matchMaterial(cat.itemMaterial) ?: Material.PAPER
        val item = ItemStack(material, amount)
        val meta = item.itemMeta ?: return null
        val cmdComp = meta.customModelDataComponent
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize(cat.name.replace("&", "§")))
        if (cat.customModelData > 0) {
            cmdComp.floats = listOf(cat.customModelData.toFloat())
            meta.setCustomModelDataComponent(cmdComp)
        }
        meta.persistentDataContainer.set(ammoCatKey, PersistentDataType.STRING, category)
        item.itemMeta = meta
        return item
    }

    fun setAds(player: Player, ads: Boolean) {
        if (ads) {
            adsState[player.uniqueId] = true
            adsStartTime[player.uniqueId] = System.currentTimeMillis()
            player.scheduler.run(plugin, { _ ->
                val attr = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)
                attr?.baseValue = (attr?.baseValue ?: 0.1) * 0.4
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, -1, 0, false, false))
            }, null)
        } else {
            adsState[player.uniqueId] = false
            player.scheduler.run(plugin, { _ ->
                val attr = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)
                attr?.baseValue = 0.1
                player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS)
            }, null)
        }
    }

    fun isAds(player: Player): Boolean = adsState.getOrDefault(player.uniqueId, false)
    fun getAdsStartTime(player: Player): Long = adsStartTime.getOrDefault(player.uniqueId, 0L)

    fun isReloading(item: ItemStack): Boolean {
        return (item.itemMeta?.persistentDataContainer?.get(reloadKey, PersistentDataType.INTEGER) ?: 0) > 0
    }

    fun getMagazine(item: ItemStack): Int {
        return item.itemMeta?.persistentDataContainer?.get(magazineKey, PersistentDataType.INTEGER) ?: 0
    }

    fun removeAds(player: Player) {
        adsState.remove(player.uniqueId)
        adsStartTime.remove(player.uniqueId)
    }

    fun startAutoFire(player: Player) {
        val weaponStack = player.inventory.itemInMainHand
        val weaponId = getWeaponId(weaponStack) ?: return
        val config = weapons[weaponId] ?: return

        if (autoFireTasks.containsKey(player.uniqueId)) return

        val task = player.scheduler.runAtFixedRate(plugin, { t ->
            val currentItem = player.inventory.itemInMainHand
            val currentId = getWeaponId(currentItem)
            if (currentId != weaponId) {
                stopAutoFire(player)
                t.cancel()
                return@runAtFixedRate
            }
            if (isReloading(currentItem)) return@runAtFixedRate
            if (getMagazine(currentItem) <= 0) {
                stopAutoFire(player)
                t.cancel()
                return@runAtFixedRate
            }
            if (!plugin.debugMode && plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                stopAutoFire(player)
                t.cancel()
                return@runAtFixedRate
            }
            handleShoot(player, currentItem)
        }, null, 0L, config.cooldownTicks.toLong())

        autoFireTasks[player.uniqueId] = task
    }

    fun stopAutoFire(player: Player) {
        autoFireTasks.remove(player.uniqueId)?.cancel()
    }

    fun isAutoFiring(player: Player): Boolean {
        return autoFireTasks.containsKey(player.uniqueId)
    }
}
