package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.*
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.*
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class WeaponManager(private val plugin: ZombieRun) {

    // NBT keys
    private val weaponIdKey = NamespacedKey("zombie-run", "weapon_id")
    private val magazineKey = NamespacedKey("zombie-run", "magazine")
    private val chamberKey = NamespacedKey("zombie-run", "has_chamber")
    private val ammoCatKey = NamespacedKey("zombie-run", "ammo_cat")
    private val reloadKey = NamespacedKey("zombie-run", "reloading")
    private val shotCountKey = NamespacedKey("zombie-run", "shot_count")

    private var weapons: Map<String, WeaponConfig> = emptyMap()
    private var ammoCategories: Map<String, AmmoCategory> = emptyMap()

    // Player state
    private val cooldowns = ConcurrentHashMap<UUID, Int>()
    private val adsProgress = ConcurrentHashMap<UUID, Float>() // 0.0 ~ 1.0
    private val adsStartTime = ConcurrentHashMap<UUID, Long>()
    private val adsOriginalSpeed = ConcurrentHashMap<UUID, Double>()
    private val autoFireTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    private val reloadTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    private val boltTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    private val headshotCooldowns = ConcurrentHashMap<String, Int>()
    private val burstCounters = ConcurrentHashMap<UUID, Int>() // burst 连发剩余计数
    private val lastShotSemi = ConcurrentHashMap<UUID, Boolean>() // SEMI 模式防止按住连发

    // ==================== 加载 ====================

    fun loadWeapons() {
        weapons = plugin.configManager.loadWeaponConfigs()
        ammoCategories = plugin.configManager.loadAmmoCategories()
        plugin.logger.info("已加载 ${weapons.size} 把武器, ${ammoCategories.size} 种弹药类别")
    }

    fun getWeaponConfig(id: String): WeaponConfig? = weapons[id]
    fun getAllWeaponConfigs(): Collection<WeaponConfig> = weapons.values
    fun getWeaponIds(): List<String> = weapons.keys.toList()
    fun getAmmoCategory(id: String): AmmoCategory? = ammoCategories[id]

    // ==================== 状态查询 ====================

    fun isAds(player: Player): Boolean = (adsProgress[player.uniqueId] ?: 0f) > 0f
    fun getAdsProgress(player: Player): Float = adsProgress[player.uniqueId] ?: 0f
    fun isReloading(item: ItemStack): Boolean = (item.itemMeta?.persistentDataContainer?.get(reloadKey, PersistentDataType.INTEGER) ?: 0) > 0
    fun isPlayerReloading(player: Player): Boolean = reloadTasks.containsKey(player.uniqueId)
    fun isBolting(player: Player): Boolean = boltTasks.containsKey(player.uniqueId)
    fun getMagazine(item: ItemStack): Int = item.itemMeta?.persistentDataContainer?.get(magazineKey, PersistentDataType.INTEGER) ?: 0
    fun hasChamber(item: ItemStack): Boolean = (item.itemMeta?.persistentDataContainer?.get(chamberKey, PersistentDataType.INTEGER) ?: 0) == 1

    /** 是否处于可射击的 idle 状态 */
    fun canOperate(player: Player): Boolean = !isPlayerReloading(player) && !isBolting(player)

    // ==================== 物品构建 ====================

    fun buildWeaponItem(id: String): ItemStack? {
        val config = weapons[id] ?: return null
        val material = Material.matchMaterial(config.material) ?: Material.WOODEN_HOE
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return null
        val cmdComp = meta.customModelDataComponent
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize(config.name.replace("&", "§")))
        val lore = config.lore.map { LegacyComponentSerializer.legacySection().deserialize(it.replace("&", "§")) }.toMutableList()
        meta.lore(lore)
        if (config.customModelData.floats().isNotEmpty()) {
            cmdComp.floats = config.customModelData.floats()
            meta.setCustomModelDataComponent(cmdComp)
        }
        val pdc = meta.persistentDataContainer
        pdc.set(weaponIdKey, PersistentDataType.STRING, id)
        pdc.set(magazineKey, PersistentDataType.INTEGER, config.magazineSize)
        pdc.set(ammoCatKey, PersistentDataType.STRING, config.ammoCategory)
        pdc.set(reloadKey, PersistentDataType.INTEGER, 0)
        pdc.set(shotCountKey, PersistentDataType.INTEGER, 0)
        // CLOSED_BOLT / MANUAL_ACTION 初始膛内有弹
        if (config.boltType == BoltType.CLOSED_BOLT || config.boltType == BoltType.MANUAL_ACTION) {
            pdc.set(chamberKey, PersistentDataType.INTEGER, 1)
        }
        item.itemMeta = meta
        return item
    }

    fun isZombieRunWeapon(item: ItemStack): Boolean =
        item.itemMeta?.persistentDataContainer?.has(weaponIdKey, PersistentDataType.STRING) == true

    fun getWeaponId(item: ItemStack): String? =
        item.itemMeta?.persistentDataContainer?.get(weaponIdKey, PersistentDataType.STRING)

    fun giveWeapon(player: Player, weaponId: String): Boolean {
        val item = buildWeaponItem(weaponId) ?: return false
        if (player.inventory.firstEmpty() == -1) {
            player.sendMessage(Component.text("背包已满，无法接收武器", NamedTextColor.RED))
            return false
        }
        player.inventory.addItem(item)
        return true
    }

    // ==================== 射击 ====================

    fun handleShoot(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        if (!isZombieRunWeapon(item)) return false
        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(weaponIdKey, PersistentDataType.STRING) ?: return false
        val config = weapons[weaponId] ?: return false
        val magazine = pdc.get(magazineKey, PersistentDataType.INTEGER) ?: 0
        val hasChamberBullet = (pdc.get(chamberKey, PersistentDataType.INTEGER) ?: 0) == 1
        val shotCount = pdc.get(shotCountKey, PersistentDataType.INTEGER) ?: 0

        if (!canOperate(player)) return false
        if (!plugin.debugMode) {
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return false
            if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return false
        }

        // 弹药检查
        val canFire = when (config.boltType) {
            BoltType.OPEN_BOLT -> magazine > 0
            BoltType.CLOSED_BOLT -> hasChamberBullet || magazine > 0
            BoltType.MANUAL_ACTION -> hasChamberBullet
        }
        if (!canFire) {
            player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.5f, 1.5f)
            return false
        }

        // 冷却
        val now = plugin.server.currentTick
        val lastShot = cooldowns.getOrDefault(player.uniqueId, 0)
        if (now - lastShot < config.cooldownTicks) return false
        cooldowns[player.uniqueId] = now

        // SEMI 防连发
        if (config.fireMode == FireMode.SEMI) {
            if (lastShotSemi.getOrDefault(player.uniqueId, false)) return false
            lastShotSemi[player.uniqueId] = true
        }

        // BURST
        if (config.fireMode == FireMode.BURST) {
            val remaining = burstCounters.getOrDefault(player.uniqueId, 0)
            if (remaining <= 0) {
                burstCounters[player.uniqueId] = config.burstCount - 1
            } else {
                burstCounters[player.uniqueId] = remaining - 1
            }
        }

        // 消耗弹药
        val newMagazine: Int
        val newChamber: Int
        when (config.boltType) {
            BoltType.OPEN_BOLT -> {
                newMagazine = magazine - 1; newChamber = 0
            }
            BoltType.CLOSED_BOLT -> {
                if (hasChamberBullet) {
                    newMagazine = magazine; newChamber = 0
                } else {
                    newMagazine = magazine - 1; newChamber = 0
                }
            }
            BoltType.MANUAL_ACTION -> {
                newMagazine = magazine; newChamber = 0
            }
        }

        // 散布 + 射击
        val ads = (adsProgress[player.uniqueId] ?: 0f) > 0.8f
        val adsMult = 1.0f - (adsProgress[player.uniqueId] ?: 0f) * (1.0f - config.adsSpreadMult.toFloat())
        val finalSpread = (config.spread + shotCount * config.spreadPerShot) * adsMult
        val shotResult = performShots(player, player.eyeLocation, player.eyeLocation.direction, config, shotCount, ads, finalSpread)

        // 音效
        if (config.sound != null) {
            try { player.playSound(player.location, Sound.valueOf(config.sound.uppercase()), 0.8f, 1.2f) } catch (_: Exception) {}
        }

        // 写回
        pdc.set(magazineKey, PersistentDataType.INTEGER, newMagazine)
        pdc.set(chamberKey, PersistentDataType.INTEGER, newChamber)
        pdc.set(shotCountKey, PersistentDataType.INTEGER, shotCount + 1)
        item.itemMeta = meta

        // ActionBar: 弹药
        showAmmoBar(player, newMagazine, newChamber == 1, config)

        // MANUAL_ACTION 射击后需拉栓
        if (config.boltType == BoltType.MANUAL_ACTION && newMagazine > 0) {
            startBolt(player, item, config)
        }

        return true
    }

    private fun showAmmoBar(player: Player, magazine: Int, hasChamber: Boolean, config: WeaponConfig) {
        val barColor = when {
            magazine.toDouble() / config.magazineSize > 0.5 -> NamedTextColor.GREEN
            magazine.toDouble() / config.magazineSize > 0.25 -> NamedTextColor.YELLOW
            else -> NamedTextColor.RED
        }
        val chamberTag = if (config.boltType != BoltType.OPEN_BOLT) {
            if (hasChamber) Component.text(" +1", NamedTextColor.AQUA) else Component.empty()
        } else Component.empty()
        player.sendActionBar(
            Component.text(magazine, barColor)
                .append(chamberTag)
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(config.magazineSize))
        )
    }

    private data class ShotResult(val totalHitDmg: Double = 0.0, val hitHeadshot: Boolean = false)

    private fun performShots(
        player: Player, eyeLoc: Location, baseDir: org.bukkit.util.Vector,
        config: WeaponConfig, shotCount: Int, ads: Boolean, finalSpread: Double
    ): ShotResult {
        var totalHitDmg = 0.0
        var hitHeadshot = false
        val now = plugin.server.currentTick

        for (i in 0 until config.pellets) {
            val spreadDir = applySpreadAndRecoil(baseDir, config, shotCount, ads, finalSpread, config.pellets > 1)
            val rayTrace = player.world.rayTraceEntities(eyeLoc, spreadDir, config.range.toDouble(), 0.1) { it is Player && it != player }
            if (rayTrace != null) {
                val target = rayTrace.hitEntity as? Player ?: continue
                val targetTeam = plugin.gameManager.getPlayerTeam(target)
                if (targetTeam == GameManager.Team.ZOMBIE || targetTeam == GameManager.Team.ZOMBIE_MAIN) {
                    val isHeadshot = checkHeadshot(target, rayTrace.hitPosition.y)
                    val dmg = config.damage * (if (isHeadshot) config.headshotMult else 1.0)
                    totalHitDmg += dmg
                    if (isHeadshot) hitHeadshot = true

                    target.scheduler.run(plugin, { _ ->
                        plugin.healthManager.damage(target, dmg, player)
                        if (config.knockback > 0) {
                            target.velocity = target.velocity.add(spreadDir.clone().multiply(config.knockback))
                        }
                    }, null)

                    // TextDisplay 浮字伤害
                    spawnDamageDisplay(target, dmg, isHeadshot)

                    if (isHeadshot) {
                        target.world.spawnParticle(Particle.CRIT, target.location.clone().add(0.0, target.eyeHeight - 0.2, 0.0), 5, 0.3, 0.3, 0.3, 0.0)
                        val key = "${player.uniqueId}:${target.uniqueId}"
                        val lastHsTick = headshotCooldowns.getOrDefault(key, 0)
                        if (now - lastHsTick >= config.cooldownTicks * 5) {
                            headshotCooldowns[key] = now
                            plugin.progressionManager.addXp(player, plugin.economyConfig.headshotXp, "爆头")
                        }
                    }
                }
                if (config.hitSound != null) {
                    try { player.playSound(player.location, Sound.valueOf(config.hitSound.uppercase()), 0.5f, 1.5f) } catch (_: Exception) {}
                }
            }
        }
        return ShotResult(totalHitDmg, hitHeadshot)
    }

    private fun spawnDamageDisplay(target: Player, dmg: Double, isHeadshot: Boolean) {
        val world = target.world
        val loc = target.location.clone().add(0.0, target.eyeHeight + 0.5, 0.0)

        val dmgInt = dmg.toInt()
        val color = if (isHeadshot) NamedTextColor.GOLD else NamedTextColor.RED
        val text = Component.text("$dmgInt", color)

        val display = world.spawn(loc, TextDisplay::class.java) { td ->
            td.text(text)
            td.isSeeThrough = false
            td.billboard = Display.Billboard.CENTER
            td.isShadowed = true
        }

        var ticks = 0
        val task = display.scheduler.runAtFixedRate(plugin, { t ->
            if (ticks >= 12 || display.isDead) {
                display.remove()
                t.cancel()
                return@runAtFixedRate
            }
            display.teleport(display.location.add(0.0, 0.08, 0.0))
            display.textOpacity = ((12 - ticks) / 12f * 0xFF).toInt().toByte()
            ticks++
        }, null, 1L, 1L)
    }

    private fun applySpreadAndRecoil(
        baseDir: org.bukkit.util.Vector, config: WeaponConfig,
        shotCount: Int, ads: Boolean, spreadRad: Double, multiPellet: Boolean
    ): org.bukkit.util.Vector {
        val dir = baseDir.clone()
        val recoilIdx = (shotCount % config.recoil.size).coerceIn(0, maxOf(config.recoil.size - 1, 0))
        if (config.recoil.isNotEmpty()) {
            val recoilAngle = config.recoil[recoilIdx] * (if (ads) config.adsRecoilMult else 1.0)
            val adsMult = if (ads) config.adsRecoilMult else 1.0
            dir.y += Math.toRadians(recoilAngle)
            val hRecoilBase = config.recoil[recoilIdx] * 0.3 * adsMult
            val hRecoil = (Math.random() - 0.5) * 2 * hRecoilBase
            val loc1 = Location(null, 0.0, 0.0, 0.0)
            loc1.direction = dir
            loc1.yaw += Math.toDegrees(hRecoil).toFloat()
            dir.setX(loc1.direction.x).setY(loc1.direction.y).setZ(loc1.direction.z)
        }

        val spreadMultiplier = if (multiPellet) 1.0 else 0.5
        val yawOffset = (Math.random() - 0.5) * spreadRad * spreadMultiplier * 2
        val pitchOffset = (Math.random() - 0.5) * spreadRad * spreadMultiplier * 2
        val loc = Location(null, 0.0, 0.0, 0.0)
        loc.direction = dir
        loc.yaw += Math.toDegrees(yawOffset).toFloat()
        loc.pitch += Math.toDegrees(pitchOffset).toFloat()
        return loc.direction
    }

    private fun checkHeadshot(player: Player, hitY: Double): Boolean {
        val eyeY = player.location.y + player.eyeHeight
        return hitY in (eyeY - 0.4)..(eyeY + 0.3)
    }

    // ==================== 拉栓 (MANUAL_ACTION) ====================

    private fun startBolt(player: Player, item: ItemStack, config: WeaponConfig) {
        boltTasks.remove(player.uniqueId)?.cancel()

        val task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            if (!player.isOnline || !canOperate(player)) {
                boltTasks.remove(player.uniqueId)
                return@runDelayed
            }
            val curItem = player.inventory.itemInMainHand
            val curMeta = curItem.itemMeta ?: run { boltTasks.remove(player.uniqueId); return@runDelayed }
            val curPdc = curMeta.persistentDataContainer
            val curMag = curPdc.get(magazineKey, PersistentDataType.INTEGER) ?: 0
            if (curMag <= 0) { boltTasks.remove(player.uniqueId); return@runDelayed }

            curPdc.set(magazineKey, PersistentDataType.INTEGER, curMag - 1)
            curPdc.set(chamberKey, PersistentDataType.INTEGER, 1)
            curPdc.set(shotCountKey, PersistentDataType.INTEGER, 0)
            curItem.itemMeta = curMeta
            player.playSound(player.location, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 2f)
            showAmmoBar(player, curMag - 1, true, config)
            boltTasks.remove(player.uniqueId)
        }, config.reloadTimeTicks * 2L / 3L)
        boltTasks[player.uniqueId] = task
    }

    // ==================== 换弹 ====================

    fun handleReload(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        if (!isZombieRunWeapon(item)) return false
        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(weaponIdKey, PersistentDataType.STRING) ?: return false
        val config = weapons[weaponId] ?: return false
        val magazine = pdc.get(magazineKey, PersistentDataType.INTEGER) ?: 0
        val hasChamberBullet = config.boltType != BoltType.OPEN_BOLT && (pdc.get(chamberKey, PersistentDataType.INTEGER) ?: 0) == 1

        val totalRounds = magazine + if (hasChamberBullet) 1 else 0
        if (totalRounds >= config.magazineSize + if (config.boltType != BoltType.OPEN_BOLT) 1 else 0) {
            player.sendActionBar(Component.text("弹匣已满", NamedTextColor.GREEN))
            return false
        }

        if (countAmmoInInventory(player, config.ammoCategory) <= 0) {
            player.sendActionBar(Component.text("没有可用弹药", NamedTextColor.RED))
            return false
        }

        if (!canOperate(player)) return false

        // 取消当前操作的自动射击
        stopAutoFire(player)

        pdc.set(reloadKey, PersistentDataType.INTEGER, 1)
        item.itemMeta = meta

        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { t ->
            val curItem = player.inventory.itemInMainHand
            if (getWeaponId(curItem) != weaponId) {
                forceCancelReload(player, item); reloadTasks.remove(player.uniqueId); t.cancel(); return@runAtFixedRate
            }
            val curMeta = curItem.itemMeta ?: run {
                forceCancelReload(player, item); reloadTasks.remove(player.uniqueId); t.cancel(); return@runAtFixedRate
            }
            val curPdc = curMeta.persistentDataContainer
            var progress = curPdc.get(reloadKey, PersistentDataType.INTEGER) ?: 0
            progress++

            if (progress >= config.reloadTimeTicks) {
                val curMag = curPdc.get(magazineKey, PersistentDataType.INTEGER) ?: 0
                val need = config.magazineSize - curMag
                val ammoInInv = countAmmoInInventory(player, config.ammoCategory)
                if (ammoInInv <= 0) {
                    curPdc.set(reloadKey, PersistentDataType.INTEGER, 0); curItem.itemMeta = curMeta
                    player.sendActionBar(Component.text("没有可用弹药", NamedTextColor.RED))
                    reloadTasks.remove(player.uniqueId); t.cancel(); return@runAtFixedRate
                }
                val actual = min(need, ammoInInv)
                consumeAmmoFromInventory(player, config.ammoCategory, actual)
                val newMag = curMag + actual
                curPdc.set(magazineKey, PersistentDataType.INTEGER, newMag)
                curPdc.set(reloadKey, PersistentDataType.INTEGER, 0)
                curPdc.set(shotCountKey, PersistentDataType.INTEGER, 0)
                curItem.itemMeta = curMeta
                player.playSound(player.location, Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 1.5f)
                showAmmoBar(player, newMag, (curPdc.get(chamberKey, PersistentDataType.INTEGER) ?: 0) == 1, config)
                reloadTasks.remove(player.uniqueId); t.cancel(); return@runAtFixedRate
            }

            curPdc.set(reloadKey, PersistentDataType.INTEGER, progress); curItem.itemMeta = curMeta
            val percent = (progress.toDouble() / config.reloadTimeTicks * 100).toInt()
            val filled = "█".repeat(percent / 5); val empty = "░".repeat(20 - percent / 5)
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize("§e装填中... $filled$empty $percent%"))
        }, 1L, 1L)

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

    // ==================== ADS ====================

    fun setAds(player: Player, aiming: Boolean) {
        if (aiming) {
            adsStartTime[player.uniqueId] = System.currentTimeMillis()
            // Start speed reduction immediately
            player.scheduler.run(plugin, { _ ->
                val attr = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)
                val current = attr?.baseValue ?: plugin.balanceConfig.defaultMoveSpeed
                adsOriginalSpeed.putIfAbsent(player.uniqueId, current)
                attr?.baseValue = current * plugin.balanceConfig.adsSpeedMultiplier
                player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, -1, 0, false, false))
            }, null)
        } else {
            adsProgress[player.uniqueId] = 0f
            player.scheduler.run(plugin, { _ ->
                val attr = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)
                val original = adsOriginalSpeed.remove(player.uniqueId) ?: plugin.balanceConfig.defaultMoveSpeed
                attr?.baseValue = original
                player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS)
                lastShotSemi.remove(player.uniqueId) // 关镜重置 semi 标志
            }, null)
        }
    }

    /** 每 tick 更新 ADS 进度（由 WeaponListener 调用） */
    fun tickAds(player: Player) {
        val item = player.inventory.itemInMainHand
        if (!isZombieRunWeapon(item)) {
            if (isAds(player)) setAds(player, false)
            return
        }
        val weaponId = getWeaponId(item) ?: return
        val config = weapons[weaponId] ?: return

        val start = adsStartTime[player.uniqueId]
        if (start == null) {
            adsProgress[player.uniqueId] = (adsProgress[player.uniqueId] ?: 0f) * 0.8f // decay
            if ((adsProgress[player.uniqueId] ?: 0f) < 0.01f) {
                adsProgress[player.uniqueId] = 0f
                // Fully restore speed if not aiming anymore
                if (adsOriginalSpeed.containsKey(player.uniqueId)) {
                    setAds(player, false)
                }
            }
            return
        }

        val elapsed = (System.currentTimeMillis() - start) / 1000f
        val progress = if (config.aimTime > 0f) (elapsed / config.aimTime).coerceIn(0f, 1f) else 1f
        adsProgress[player.uniqueId] = progress
    }

    fun getAdsStartTime(player: Player): Long = adsStartTime.getOrDefault(player.uniqueId, 0L)
    fun removeAds(player: Player) {
        adsProgress.remove(player.uniqueId)
        adsStartTime.remove(player.uniqueId)
        lastShotSemi.remove(player.uniqueId)
    }

    // ==================== 自动射击 ====================

    fun startAutoFire(player: Player) {
        val item = player.inventory.itemInMainHand
        if (!isZombieRunWeapon(item)) return
        val weaponId = getWeaponId(item) ?: return
        val config = weapons[weaponId] ?: return
        if (config.fireMode != FireMode.AUTO) return
        if (autoFireTasks.containsKey(player.uniqueId)) return

        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { t ->
            val curItem = player.inventory.itemInMainHand
            if (getWeaponId(curItem) != weaponId || !canOperate(player)) {
                stopAutoFire(player); t.cancel(); return@runAtFixedRate
            }
            if (getMagazine(curItem) <= 0 && !hasChamber(curItem)) {
                stopAutoFire(player); t.cancel(); return@runAtFixedRate
            }
            if (!plugin.debugMode && plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                stopAutoFire(player); t.cancel(); return@runAtFixedRate
            }
            handleShoot(player)
        }, 1L, config.cooldownTicks.toLong())

        autoFireTasks[player.uniqueId] = task
    }

    fun stopAutoFire(player: Player) {
        autoFireTasks.remove(player.uniqueId)?.cancel()
        lastShotSemi.remove(player.uniqueId)
    }

    fun isAutoFiring(player: Player) = autoFireTasks.containsKey(player.uniqueId)

    // ==================== 弹药管理 ====================

    private fun countAmmoInInventory(player: Player, category: String): Int {
        var total = 0
        for (item in player.inventory.contents) {
            if (item == null) continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(weaponIdKey, PersistentDataType.STRING)) continue
            if (meta.persistentDataContainer.get(ammoCatKey, PersistentDataType.STRING) == category) total += item.amount
        }
        return total
    }

    private fun consumeAmmoFromInventory(player: Player, category: String, amount: Int) {
        var remaining = amount
        val inv = player.inventory
        for (i in 0 until inv.size) {
            if (remaining <= 0) break
            val item = inv.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(weaponIdKey, PersistentDataType.STRING)) continue
            if (meta.persistentDataContainer.get(ammoCatKey, PersistentDataType.STRING) == category) {
                val take = min(remaining, item.amount)
                if (item.amount <= take) inv.setItem(i, null) else item.amount -= take
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

    fun giveAmmoRespectingMaxReserve(player: Player, weaponId: String) {
        val config = weapons[weaponId] ?: return
        val existing = countAmmoInInventory(player, config.ammoCategory)
        val needed = (config.maxReserve - existing).coerceAtLeast(0)
        if (needed <= 0) return
        var remaining = needed
        val inv = player.inventory
        for (i in 0 until inv.size) {
            if (remaining <= 0) break
            val item = inv.getItem(i) ?: continue
            if (item.amount >= 64) continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(weaponIdKey, PersistentDataType.STRING)) continue
            if (meta.persistentDataContainer.get(ammoCatKey, PersistentDataType.STRING) == config.ammoCategory) {
                val space = 64 - item.amount; val add = min(remaining, space)
                item.amount += add; remaining -= add
            }
        }
        while (remaining > 0) {
            val stackSize = min(remaining, 64)
            val ammoItem = buildAmmoItem(config.ammoCategory, stackSize) ?: break
            if (inv.firstEmpty() == -1) break
            inv.addItem(ammoItem); remaining -= stackSize
        }
    }

    // ==================== 清理 ====================

    fun clearPlayer(player: Player) {
        val uid = player.uniqueId
        cooldowns.remove(uid)
        adsProgress.remove(uid)
        adsStartTime.remove(uid)
        adsOriginalSpeed.remove(uid)
        stopAutoFire(player)
        reloadTasks.remove(uid)?.cancel()
        boltTasks.remove(uid)?.cancel()
        burstCounters.remove(uid)
        lastShotSemi.remove(uid)
    }
}
