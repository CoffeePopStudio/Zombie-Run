# 自定义枪械系统 + 硬币持久化 + 商店 GUI 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完全脱离 WeaponMechanics，在 zombie-run 内部实现 PDC 枪械系统、SQLite 硬币持久化、GUI 商店。

**Architecture:** 新建 WeaponManager（武器构建/射击/换弹）、CoinManager（SQLite 异步缓存）、ShopGUI（Inventory GUI）。删除 WeaponHelper/SQLManager。CombatListener/GameManager/GameListener/ZombieRun.kt 全部改为调用新类。

**Tech Stack:** Kotlin JVM, Paper 1.21, HikariCP (SQLite), PDC, Bukkit Inventory API, Adventure Component.

---

### Task 1: 数据模型 — WeaponConfig + AmmoConfig

**Files:**
- Create: `src/main/kotlin/cn/oneachina/zombieRun/model/WeaponConfig.kt`
- Create: `src/main/kotlin/cn/oneachina/zombieRun/model/AmmoConfig.kt`

- [ ] **Step 1: Write WeaponConfig.kt**

```kotlin
package cn.oneachina.zombieRun.model

data class WeaponConfig(
    val id: String,
    val material: String,
    val customModelData: Int,
    val name: String,
    val lore: List<String>,
    val damage: Double,
    val ammoType: String,
    val maxAmmo: Int,
    val price: Int,
    val cooldownTicks: Int,
    val sound: String?,
    val knockback: Double,
    val range: Int,
    val pellets: Int,
    val automatic: Boolean
)
```

- [ ] **Step 2: Write AmmoConfig.kt**

```kotlin
package cn.oneachina.zombieRun.model

data class AmmoConfig(
    val id: String,
    val material: String,
    val customModelData: Int,
    val name: String,
    val lore: List<String>
)
```

- [ ] **Step 3: Verify compilation**

```bash
.\gradlew compileKotlin --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cn/oneachina/zombieRun/model/WeaponConfig.kt src/main/kotlin/cn/oneachina/zombieRun/model/AmmoConfig.kt
git commit -m "feat: add WeaponConfig and AmmoConfig data classes"
```

---

### Task 2: WeaponManager — 加载与构建

**Files:**
- Create: `src/main/kotlin/cn/oneachina/zombieRun/manager/WeaponManager.kt`
- Modify: `src/main/kotlin/cn/oneachina/zombieRun/manager/ConfigManager.kt` (add getters)

- [ ] **Step 1: Add ConfigManager weapon getters**

In `ConfigManager.kt`, before `fun addDoorFull`, add:

```kotlin
fun loadWeaponConfigs(): Map<String, WeaponConfig> {
    val weapons = mutableMapOf<String, WeaponConfig>()
    val section = config.getConfigurationSection("custom-weapons") ?: return weapons
    for (id in section.getKeys(false)) {
        val ws = section.getConfigurationSection(id) ?: continue
        val config = WeaponConfig(
            id = id,
            material = ws.getString("material") ?: "WOODEN_HOE",
            customModelData = ws.getInt("custom-model-data", 0),
            name = ws.getString("name") ?: id,
            lore = ws.getStringList("lore") ?: emptyList(),
            damage = ws.getDouble("damage", 5.0),
            ammoType = ws.getString("ammo-type") ?: "pistol_ammo",
            maxAmmo = ws.getInt("max-ammo", 12),
            price = ws.getInt("price", 300),
            cooldownTicks = ws.getInt("cooldown-ticks", 10),
            sound = ws.getString("sound"),
            knockback = ws.getDouble("knockback", 0.0),
            range = ws.getInt("range", 30),
            pellets = ws.getInt("pellets", 1),
            automatic = ws.getBoolean("automatic", false)
        )
        weapons[id] = config
    }
    return weapons
}

fun loadAmmoConfigs(): Map<String, AmmoConfig> {
    val ammos = mutableMapOf<String, AmmoConfig>()
    val section = config.getConfigurationSection("ammo-items") ?: return ammos
    for (id in section.getKeys(false)) {
        val as_ = section.getConfigurationSection(id) ?: continue
        val config = AmmoConfig(
            id = id,
            material = as_.getString("material") ?: "PAPER",
            customModelData = as_.getInt("custom-model-data", 0),
            name = as_.getString("name") ?: id,
            lore = as_.getStringList("lore") ?: emptyList()
        )
        ammos[id] = config
    }
    return ammos
}
```

- [ ] **Step 2: Write WeaponManager.kt**

```kotlin
package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.AmmoConfig
import cn.oneachina.zombieRun.model.WeaponConfig
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.ConcurrentHashMap

class WeaponManager(private val plugin: ZombieRun) {

    private val weaponIdKey = NamespacedKey("zombie-run", "weapon_id")
    private val ammoKey = NamespacedKey("zombie-run", "ammo")

    private var weapons: Map<String, WeaponConfig> = emptyMap()
    private var ammoItems: Map<String, AmmoConfig> = emptyMap()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()

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
        meta.displayName(Component.text(
            config.name.replace("&", "§")
        ))
        meta.lore = config.lore.map { Component.text(it.replace("&", "§")) }
        if (config.customModelData > 0) {
            meta.setCustomModelData(config.customModelData)
        }
        meta.persistentDataContainer.set(weaponIdKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(ammoKey, PersistentDataType.INTEGER, config.maxAmmo)
        item.itemMeta = meta
        return item
    }

    fun giveWeapon(player: Player, weaponId: String): Boolean {
        val item = buildWeaponItem(weaponId) ?: return false
        if (player.inventory.firstEmpty() == -1) {
            player.sendMessage("§c背包已满，无法接收武器")
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
}
```

- [ ] **Step 3: Compile**

```bash
.\gradlew compileKotlin --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cn/oneachina/zombieRun/manager/WeaponManager.kt src/main/kotlin/cn/oneachina/zombieRun/manager/ConfigManager.kt
git commit -m "feat: add WeaponManager with load/build/give, ConfigManager weapon getters"
```

---

### Task 3: WeaponManager — 射击与换弹

**Files:**
- Modify: `src/main/kotlin/cn/oneachina/zombieRun/manager/WeaponManager.kt`

- [ ] **Step 1: Add shooting + reload methods to WeaponManager.kt**

Append after the closing `}` of `fun isZombieRunWeapon`, before the final `}` of the class:

```kotlin
    fun handleShoot(player: Player, item: ItemStack): Boolean {
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return false
        if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return false

        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(weaponIdKey, PersistentDataType.STRING) ?: return false
        val config = weapons[weaponId] ?: return false
        var ammo = pdc.get(ammoKey, PersistentDataType.INTEGER) ?: 0

        if (ammo <= 0) {
            player.playSound(player.location, org.bukkit.Sound.BLOCK_DISPENSER_FAIL, 0.5f, 1.5f)
            player.sendActionBar(Component.text("§c弹药耗尽"))
            return false
        }

        val now = plugin.server.currentTick
        val lastShot = cooldowns.getOrDefault(player.uniqueId, 0L)
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
            ammo.toDouble() / config.maxAmmo > 0.5 -> "§a"
            ammo.toDouble() / config.maxAmmo > 0.25 -> "§e"
            else -> "§c"
        }
        player.sendActionBar(Component.text("${barColor}${ammo} §7/ ${config.maxAmmo}"))
        return true
    }

    fun reloadWeapon(player: Player, weaponStack: ItemStack): Boolean {
        val meta = weaponStack.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(weaponIdKey, PersistentDataType.STRING) ?: return false
        val config = weapons[weaponId] ?: return false
        var ammo = pdc.get(ammoKey, PersistentDataType.INTEGER) ?: 0

        if (ammo >= config.maxAmmo) {
            player.sendActionBar(Component.text("§a弹药已满"))
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
            player.playSound(player.location, org.bukkit.Sound.BLOCK_DISPENSER_FAIL, 0.5f, 1.5f)
            player.sendActionBar(Component.text("§c没有匹配的弹药"))
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

        player.playSound(player.location, org.bukkit.Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 1.5f)
        player.sendActionBar(Component.text("§a装填完成 §7${ammo} / ${config.maxAmmo}"))
        return true
    }

    fun buildAmmoItem(ammoId: String, amount: Int): ItemStack? {
        val config = ammoItems[ammoId] ?: return null
        val material = Material.matchMaterial(config.material) ?: Material.PAPER
        val item = ItemStack(material, amount)
        val meta = item.itemMeta ?: return null
        meta.displayName(Component.text(config.name.replace("&", "§")))
        if (config.lore.isNotEmpty()) {
            meta.lore = config.lore.map { Component.text(it.replace("&", "§")) }
        }
        if (config.customModelData > 0) {
            meta.setCustomModelData(config.customModelData)
        }
        meta.persistentDataContainer.set(
            NamespacedKey("zombie-run", "ammo_type"), PersistentDataType.STRING, ammoId
        )
        item.itemMeta = meta
        return item
    }
```

- [ ] **Step 2: Add missing imports at top of WeaponManager.kt**

Add to imports:
```kotlin
import net.kyori.adventure.text.Component
import org.bukkit.Sound
import java.util.UUID
```

- [ ] **Step 3: Compile**

```bash
.\gradlew compileKotlin --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cn/oneachina/zombieRun/manager/WeaponManager.kt
git commit -m "feat: add WeaponManager shoot/reload/ammo item methods"
```

---

### Task 4: WeaponListener — 右键交互

**Files:**
- Create: `src/main/kotlin/cn/oneachina/zombieRun/listener/WeaponListener.kt`

- [ ] **Step 1: Write WeaponListener.kt**

```kotlin
package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

class WeaponListener(private val plugin: ZombieRun) : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return
        val action = event.action

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return

        val wm = plugin.weaponManager

        if (wm.isZombieRunWeapon(item)) {
            event.isCancelled = true

            if (player.isSneaking) {
                wm.reloadWeapon(player, item)
            } else {
                wm.handleShoot(player, item)
            }
        }
    }
}
```

- [ ] **Step 2: Add `lateinit var weaponManager` to ZombieRun.kt**

In `ZombieRun.kt`, add after `lateinit var startEffectManager`:
```kotlin
lateinit var weaponManager: WeaponManager
```

In `onEnable()`, add after `startEffectManager = ...`:
```kotlin
weaponManager = WeaponManager(this).apply { loadWeapons() }
```

In the event registration block, add after `pm.registerEvents(miscManager, this)`:
```kotlin
pm.registerEvents(WeaponListener(this), this)
```

Add import:
```kotlin
import cn.oneachina.zombieRun.listener.WeaponListener
```

Remove the old WeaponMechanics detection lines (lines ~30-33):
```kotlin
// Remove:
weaponMechanicsAvailable = Bukkit.getPluginManager().getPlugin("WeaponMechanics") != null
if (!weaponMechanicsAvailable) {
    logger.warning("WeaponMechanics 未安装，枪械功能将被禁用")
}
```

And remove `var weaponMechanicsAvailable = false` from the class body.

- [ ] **Step 3: Compile**

```bash
.\gradlew compileKotlin --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cn/oneachina/zombieRun/listener/WeaponListener.kt src/main/kotlin/cn/oneachina/zombieRun/ZombieRun.kt
git commit -m "feat: add WeaponListener for right-click shoot/reload, wire into ZombieRun"
```

---

### Task 5: CoinManager — SQLite 持久化

**Files:**
- Create: `src/main/kotlin/cn/oneachina/zombieRun/manager/CoinManager.kt`
- Delete: `src/main/kotlin/cn/oneachina/zombieRun/manager/SQLManager.kt`

- [ ] **Step 1: Write CoinManager.kt**

```kotlin
package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class CoinManager(private val plugin: ZombieRun) {

    private val cache = ConcurrentHashMap<UUID, Int>()
    private lateinit var dataSource: HikariDataSource

    fun init() {
        val dataDir = File(plugin.dataFolder, "data")
        if (!dataDir.exists()) dataDir.mkdirs()
        val dbFile = File(dataDir, "zr_economy.db")

        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$dbFile"
            maximumPoolSize = 2
            minimumIdle = 1
            connectionTimeout = 5000
        }
        dataSource = HikariDataSource(config)

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS zr_economy (
                        uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(32),
                        coins INT DEFAULT 0
                    )
                """.trimIndent())
            }
        }
        plugin.logger.info("CoinManager SQLite 已初始化: $dbFile")
    }

    fun loadPlayer(uuid: UUID, username: String): Int {
        val coins = CompletableFuture.supplyAsync {
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT coins FROM zr_economy WHERE uuid = ?").use { stmt ->
                    stmt.setString(1, uuid.toString())
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        rs.getInt("coins")
                    } else {
                        conn.prepareStatement("INSERT INTO zr_economy (uuid, username, coins) VALUES (?, ?, 0)").use { ins ->
                            ins.setString(1, uuid.toString())
                            ins.setString(2, username)
                            ins.executeUpdate()
                        }
                        0
                    }
                }
            }
        }.get()
        cache[uuid] = coins
        return coins
    }

    fun getCoins(uuid: UUID): Int = cache.getOrDefault(uuid, 0)

    fun addCoins(uuid: UUID, amount: Int) {
        val current = getCoins(uuid)
        cache[uuid] = current + amount
        updateAsync(uuid)
    }

    fun takeCoins(uuid: UUID, amount: Int): Boolean {
        val current = getCoins(uuid)
        if (current < amount) return false
        cache[uuid] = current - amount
        updateAsync(uuid)
        return true
    }

    fun setCoins(uuid: UUID, amount: Int) {
        cache[uuid] = amount
        updateAsync(uuid)
    }

    fun savePlayer(uuid: UUID, username: String) {
        val coins = cache[uuid] ?: return
        CompletableFuture.runAsync {
            dataSource.connection.use { conn ->
                conn.prepareStatement("UPDATE zr_economy SET coins = ?, username = ? WHERE uuid = ?").use { stmt ->
                    stmt.setInt(1, coins)
                    stmt.setString(2, username)
                    stmt.setString(3, uuid.toString())
                    stmt.executeUpdate()
                }
            }
        }
        cache.remove(uuid)
    }

    fun flushAll() {
        cache.forEach { (uuid, coins) ->
            runCatching {
                dataSource.connection.use { conn ->
                    conn.prepareStatement("UPDATE zr_economy SET coins = ? WHERE uuid = ?").use { stmt ->
                        stmt.setInt(1, coins)
                        stmt.setString(2, uuid.toString())
                        stmt.executeUpdate()
                    }
                }
            }
        }
        cache.clear()
    }

    fun getCoinsFromDb(uuid: UUID): Int {
        return CompletableFuture.supplyAsync {
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT coins FROM zr_economy WHERE uuid = ?").use { stmt ->
                    stmt.setString(1, uuid.toString())
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getInt("coins") else 0
                }
            }
        }.get()
    }

    fun getTopCoins(limit: Int): List<Pair<String, Int>> {
        return CompletableFuture.supplyAsync {
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT username, coins FROM zr_economy ORDER BY coins DESC LIMIT ?").use { stmt ->
                    stmt.setInt(1, limit)
                    val rs = stmt.executeQuery()
                    val result = mutableListOf<Pair<String, Int>>()
                    while (rs.next()) {
                        result.add(Pair(rs.getString("username") ?: "???", rs.getInt("coins")))
                    }
                    result
                }
            }
        }.get()
    }

    private fun updateAsync(uuid: UUID) {
        val coins = cache[uuid] ?: return
        CompletableFuture.runAsync {
            dataSource.connection.use { conn ->
                conn.prepareStatement("UPDATE zr_economy SET coins = ? WHERE uuid = ?").use { stmt ->
                    stmt.setInt(1, coins)
                    stmt.setString(2, uuid.toString())
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun close() {
        flushAll()
        dataSource.close()
    }
}
```

- [ ] **Step 2: Wire CoinManager into ZombieRun.kt**

Add field:
```kotlin
lateinit var coinManager: CoinManager
```

In `onEnable()`, add after `configManager = ...`:
```kotlin
coinManager = CoinManager(this).apply { init() }
```

In `onDisable()`, add at start:
```kotlin
coinManager.close()
```

- [ ] **Step 3: Register PlayerJoin/PlayerQuit handling in GameListener.kt for CoinManager**

In `onPlayerJoin`, after `plugin.gameManager.addPlayer(player)`, add:
```kotlin
plugin.coinManager.loadPlayer(player.uniqueId, player.name)
```

In `onPlayerQuit`, after clearing tasks but before `plugin.gameManager.removePlayer(player)`, add:
```kotlin
plugin.coinManager.savePlayer(player.uniqueId, player.name)
```

- [ ] **Step 4: Compile**

```bash
.\gradlew compileKotlin --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Delete SQLManager.kt**

```bash
git rm src/main/kotlin/cn/oneachina/zombieRun/manager/SQLManager.kt
```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cn/oneachina/zombieRun/manager/CoinManager.kt src/main/kotlin/cn/oneachina/zombieRun/ZombieRun.kt src/main/kotlin/cn/oneachina/zombieRun/listener/GameListener.kt
git commit -m "feat: add CoinManager with SQLite persistence, wire join/quit lifecycle"
```

---

### Task 6: 迁移所有 coins 调用到 CoinManager

**Files:**
- Modify: `src/main/kotlin/cn/oneachina/zombieRun/listener/CombatListener.kt`
- Modify: `src/main/kotlin/cn/oneachina/zombieRun/manager/GameManager.kt`
- Modify: `src/main/kotlin/cn/oneachina/zombieRun/manager/MiscManager.kt`

- [ ] **Step 1: CombatListener — change coins to CoinManager (UUID-based)**

Replace all `plugin.miscManager.addCoins(...)` calls:

In `onPlayerDeath`, kill reward line (L75):
```kotlin
// Before: plugin.miscManager.addCoins(killer, reward)
plugin.coinManager.addCoins(killer.uniqueId, reward)
killer.sendMessage("§6+ $reward 硬币!")
```

In `infectPlayer`, infection reward (L115):
```kotlin
// Before: plugin.miscManager.addCoins(attacker, 50)
plugin.coinManager.addCoins(attacker.uniqueId, 50)
```

- [ ] **Step 2: GameManager — change coins in sendGameEndResult**

In `sendGameEndResult`, replace both `plugin.miscManager.addCoins(player, reward)` calls (L336, L343):
```kotlin
plugin.coinManager.addCoins(player.uniqueId, reward)
```

- [ ] **Step 3: MiscManager — remove coin methods and WeaponHelper import, rewrite giveRandomGun/giveStarterKit**

In MiscManager.kt remove these methods:
- `addCoins(player, amount)` (L180-184)
- `getCoins(player)` (L186)
- `takeCoins(player, amount)` (L188-192)

Remove `playerCoins` field (L20):
```kotlin
// Remove: private val playerCoins = ConcurrentHashMap<Player, Int>()
```

Rewrite `giveRandomGun`:
```kotlin
fun giveRandomGun(player: Player) {
    val weaponIds = plugin.weaponManager.getWeaponIds()
    if (weaponIds.isEmpty()) {
        player.sendMessage("§c未找到可用枪械配置。")
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
            player.sendMessage("§a购买成功！花费硬币: $price，剩余: $remaining")
            selected
        } else {
            player.sendMessage("§c硬币不足，已改为随机枪械。")
            weaponIds.random()
        }
    } else {
        weaponIds.random()
    }

    if (!plugin.weaponManager.giveWeapon(player, weaponId)) {
        player.sendMessage("§c发放枪械失败：$weaponId")
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
```

Rewrite `getSelectableWeapons`:
```kotlin
fun getSelectableWeapons(): List<String> {
    return plugin.weaponManager.getWeaponIds()
}
```

Remove `getWeaponPrice`, `loadWeaponTitlesFromWeaponMechanics`, `giveWeaponDirectly`, `giveWeaponAmmo`, `setOrAddStacks` methods (L101-174).

- [ ] **Step 4: Compile**

```bash
.\gradlew compileKotlin --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/oneachina/zombieRun/listener/CombatListener.kt src/main/kotlin/cn/oneachina/zombieRun/manager/GameManager.kt src/main/kotlin/cn/oneachina/zombieRun/manager/MiscManager.kt
git commit -m "refactor: migrate all coin ops to CoinManager, rewrite giveRandomGun for custom weapons"
```

---

### Task 7: ShopGUI — 枪械商店

**Files:**
- Create: `src/main/kotlin/cn/oneachina/zombieRun/gui/ShopGUI.kt`

- [ ] **Step 1: Create gui directory and write ShopGUI.kt**

```bash
mkdir -Force src\main\kotlin\cn\oneachina\zombieRun\gui
```

```kotlin
package cn.oneachina.zombieRun.gui

import cn.oneachina.zombieRun.ZombieRun
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.NamespacedKey
import kotlin.math.ceil

class ShopGUI(private val plugin: ZombieRun) : Listener {

    private val shopKey = NamespacedKey("zombie-run", "shop_weapon")
    private const val GUI_TITLE = "§8枪械商店"

    fun open(player: Player) {
        val weapons = plugin.weaponManager.getAllWeaponConfigs().toList()
        val rows = ceil(weapons.size / 9.0).toInt().coerceAtLeast(1)
        val totalRows = rows + 1
        val size = totalRows * 9

        val inv = Bukkit.createInventory(null, size, Component.text(GUI_TITLE))

        weapons.forEachIndexed { index, config ->
            val material = Material.matchMaterial(config.material) ?: Material.WOODEN_HOE
            val item = ItemStack(material)
            val meta = item.itemMeta ?: return@forEachIndexed
            meta.displayName(Component.text(config.name.replace("&", "§")))
            val lore = mutableListOf<Component>()
            if (config.lore.isNotEmpty()) {
                lore.addAll(config.lore.map { Component.text(it.replace("&", "§")) })
            }
            lore.add(Component.text(""))
            lore.add(Component.text("§e价格: §6${config.price} 硬币"))
            lore.add(Component.text("§7伤害: ${config.damage} | 弹容: ${config.maxAmmo}"))
            meta.lore(lore)
            if (config.customModelData > 0) {
                meta.setCustomModelData(config.customModelData)
            }
            meta.persistentDataContainer.set(shopKey, PersistentDataType.STRING, config.id)
            item.itemMeta = meta
            inv.setItem(index, item)
        }

        val barIndex = rows * 9
        val close = ItemStack(Material.BARRIER)
        val closeMeta = close.itemMeta ?: return
        closeMeta.displayName(Component.text("§c关闭商店"))
        close.itemMeta = closeMeta
        inv.setItem(barIndex + 4, close)

        player.openInventory(inv)
    }

    fun onAutoOpen(player: Player) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline &&
                plugin.gameManager.getGameStatus() == cn.oneachina.zombieRun.manager.GameManager.GameStatus.WAITING) {
                open(player)
            }
        }, 20L)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val title = event.view.title().toString()
        if (title != GUI_TITLE.replace("§", "")) {
            val raw = event.view.title()
            if (raw is net.kyori.adventure.text.Component) {
                val plainText = plugin.plainTextSerializer.serialize(raw)
                if (plainText != GUI_TITLE.replace("§", "")) {
                    return
                }
            } else return
        }

        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val clicked = event.currentItem ?: return

        val meta = clicked.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(shopKey, PersistentDataType.STRING)

        if (weaponId != null) {
            val config = plugin.weaponManager.getWeaponConfig(weaponId) ?: return
            val coins = plugin.coinManager.getCoins(player.uniqueId)
            if (coins < config.price) {
                player.sendMessage("§c硬币不足！需要: ${config.price}，当前: $coins")
                player.closeInventory()
                return
            }

            if (player.inventory.firstEmpty() == -1) {
                player.sendMessage("§c背包已满，请清理后重试")
                player.closeInventory()
                return
            }

            plugin.coinManager.takeCoins(player.uniqueId, config.price)
            plugin.weaponManager.giveWeapon(player, weaponId)
            player.sendMessage("§a购买成功！${config.name.replace("&", "§")} §a花费: ${config.price} 硬币")
            player.closeInventory()
            return
        }

        if (clicked.type == Material.BARRIER) {
            player.closeInventory()
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val title = event.view.title().toString()
        if (title == GUI_TITLE.replace("§", "") || event.view.title().let {
                if (it is net.kyori.adventure.text.Component) {
                    plugin.plainTextSerializer.serialize(it)
                } else it.toString()
            } == GUI_TITLE.replace("§", "")) {
            event.isCancelled = true
        }
    }
}
```

- [ ] **Step 2: Wire ShopGUI into ZombieRun.kt**

Add field:
```kotlin
lateinit var shopGUI: ShopGUI
```

Add plainTextSerializer (need for GUI title check):
```kotlin
val plainTextSerializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
```

In `onEnable()`, after weaponManager init:
```kotlin
shopGUI = ShopGUI(this)
pm.registerEvents(shopGUI, this)
```

In `onPlayerJoin` (GameListener), after coinManager.loadPlayer, add:
```kotlin
plugin.shopGUI.onAutoOpen(player)
```

Remove WeaponMechanics detection from onEnable(already done in Task 4).

- [ ] **Step 3: Compile**

```bash
.\gradlew compileKotlin --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cn/oneachina/zombieRun/gui/ShopGUI.kt src/main/kotlin/cn/oneachina/zombieRun/listener/GameListener.kt src/main/kotlin/cn/oneachina/zombieRun/ZombieRun.kt
git commit -m "feat: add ShopGUI weapon store with buy/sell and auto-open on join"
```

---

### Task 8: 命令 — /zr shop, /zr coins, /zr weapon

**Files:**
- Modify: `src/main/kotlin/cn/oneachina/zombieRun/command/ZombieRunCommand.kt`

- [ ] **Step 1: Add command handlers in ZombieRunCommand.kt**

In `onCommand`, add to the `when` block before `"select"`:
```kotlin
"shop" -> {
    if (sender !is Player) {
        sender.sendMessage("§c此命令只能由玩家执行！")
        return true
    }
    plugin.shopGUI.open(sender)
}
"coins" -> handleCoins(sender, args.drop(1).toTypedArray())
```

Add after `handleSpawnTabComplete`:

```kotlin
private fun handleCoins(sender: CommandSender, args: Array<out String>) {
    if (args.isEmpty()) {
        sender.sendMessage("§c用法: /zr coins <add|remove|set|get|top> [...]")
        return
    }
    when (args[0].lowercase()) {
        "add", "remove", "set" -> {
            if (!sender.hasPermission("zombie.run.admin")) {
                sender.sendMessage("§c你没有权限使用此命令！")
                return
            }
            if (args.size < 3) {
                sender.sendMessage("§c用法: /zr coins ${args[0]} <玩家> <金额>")
                return
            }
            val amount = args[2].toIntOrNull()
            if (amount == null || amount <= 0) {
                sender.sendMessage("§c金额必须是正整数！")
                return
            }
            val target = Bukkit.getPlayer(args[1])
            if (target == null) {
                sender.sendMessage("§c玩家不在线！")
                return
            }
            when (args[0]) {
                "add" -> {
                    plugin.coinManager.addCoins(target.uniqueId, amount)
                    sender.sendMessage("§a已给 ${target.name} 增加 $amount 硬币")
                    target.sendMessage("§6管理员给了你 $amount 硬币")
                }
                "remove" -> {
                    if (!plugin.coinManager.takeCoins(target.uniqueId, amount)) {
                        sender.sendMessage("§c玩家硬币不足！")
                    } else {
                        sender.sendMessage("§a已从 ${target.name} 扣除 $amount 硬币")
                    }
                }
                "set" -> {
                    plugin.coinManager.setCoins(target.uniqueId, amount)
                    sender.sendMessage("§a已将 ${target.name} 的硬币设置为 $amount")
                }
            }
        }
        "get" -> {
            if (!sender.hasPermission("zombie.run.admin")) return
            val targetName = if (args.size > 1) args[1] else sender.name
            val player = Bukkit.getPlayer(targetName)
            val coins = if (player != null) {
                plugin.coinManager.getCoins(player.uniqueId)
            } else {
                sender.sendMessage("§c玩家不在线")
                return
            }
            sender.sendMessage("§a${targetName} 的硬币: $coins")
        }
        "top" -> {
            val count = if (args.size > 1) args[1].toIntOrNull() ?: 10 else 10
            val top = plugin.coinManager.getTopCoins(count)
            sender.sendMessage("§a===== 硬币排行榜 (TOP $count) =====")
            top.forEachIndexed { index, (name, coins) ->
                sender.sendMessage("§a${index + 1}. $name - $coins 硬币")
            }
        }
        else -> sender.sendMessage("§c未知子命令，可用: add, remove, set, get, top")
    }
}
```

In the first-level `when` command block, add `"weapon"` alongside admin commands:
```kotlin
"start", "spawn", "doors", "buttons", "reload", "open", "close", "weapon" -> {
```

And inside that block:
```kotlin
"weapon" -> handleWeapon(sender, args.drop(1).toTypedArray())
```

Add the handler:
```kotlin
private fun handleWeapon(sender: CommandSender, args: Array<out String>) {
    if (!sender.hasPermission("zombie.run.admin")) {
        sender.sendMessage("§c你没有权限使用此命令！")
        return
    }
    if (args.isEmpty() || args[0].lowercase() != "create") {
        sender.sendMessage("§c用法: /zr weapon create <id> <material> <damage> <price>")
        return
    }
    if (args.size < 5) {
        sender.sendMessage("§c用法: /zr weapon create <id> <material> <damage> <price>")
        return
    }
    val id = args[1]
    val material = args[2].uppercase()
    val damage = args[3].toDoubleOrNull()
    val price = args[4].toIntOrNull()
    if (damage == null || price == null) {
        sender.sendMessage("§c伤害和价格必须是数字！")
        return
    }

    val config = plugin.configManager.getConfig()
    config.createSection("custom-weapons.$id")
    config.set("custom-weapons.$id.material", material)
    config.set("custom-weapons.$id.name", "&f$id")
    config.set("custom-weapons.$id.custom-model-data", 0)
    config.set("custom-weapons.$id.lore", emptyList<String>())
    config.set("custom-weapons.$id.damage", damage)
    config.set("custom-weapons.$id.ammo-type", "pistol_ammo")
    config.set("custom-weapons.$id.max-ammo", 30)
    config.set("custom-weapons.$id.price", price)
    config.set("custom-weapons.$id.cooldown-ticks", 10)
    config.set("custom-weapons.$id.range", 30)
    plugin.configManager.saveConfig()
    plugin.weaponManager.loadWeapons()
    sender.sendMessage("§a武器 '$id' 已创建到 config.yml，请编辑 config.yml 调整详细属性后 /zr reload")
}
```

Add `"shop"` and `"coins"` to the onTabComplete list:
In `args.size == 1`:
```kotlin
listOf("start", "door", "spawn", "doors", "buttons", "reload", "open", "close", "select", "unselect", "randomgun", "lobby", "shop", "coins")
```

In `args.size == 2`, add `"coins"` branch:
```kotlin
"coins" -> {
    listOf("add", "remove", "set", "get", "top").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
}
```

Also add `"weapon"` to the top admin list.

- [ ] **Step 2: Compile**

```bash
.\gradlew compileKotlin --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/cn/oneachina/zombieRun/command/ZombieRunCommand.kt
git commit -m "feat: add /zr shop, /zr coins, /zr weapon create commands"
```

---

### Task 9: 清理 — build.gradle, plugin.yml, WeaponHelper, config.yml

**Files:**
- Modify: `build.gradle.kts` (remove WeaponMechanics dependency)
- Modify: `src/main/resources/plugin.yml` (remove WeaponMechanics depend, add softdepend)
- Modify: `src/main/resources/config/config.yml` (add custom-weapons and ammo-items)
- Delete: `src/main/kotlin/cn/oneachina/zombieRun/manager/WeaponHelper.kt`

- [ ] **Step 1: Remove WeaponMechanics from build.gradle.kts**

Replace line:
```kotlin
    compileOnly("com.cjcrafter:weaponmechanics:4.1.0")
```
with nothing (delete it). And add `softdepend: [PlaceholderAPI]` in plugin.yml, remove WeaponMechanics reference.

- [ ] **Step 2: Update plugin.yml**

Remove `depend: [WeaponMechanics]` if present. The current plugin.yml doesn't have it, so just ensure `softdepend` is not needed.

- [ ] **Step 3: Add default weapon configs to config.yml and ammo-items**

Add after the `weapons:` block, before `misc:`:

```yaml
# ---------- 自定义枪械 ----------
# 每把枪定义在 custom-weapons 下，键为武器 ID
# 玩家右键 = 射击，潜行+右键 = 换弹
# 弹药在 ammo-items 下定义，ammo-type 用于匹配弹药
custom-weapons:
  pistol:
    material: WOODEN_HOE
    custom-model-data: 10001
    name: "&a手枪"
    lore:
      - "&7一把可靠的基础手枪"
    damage: 5.0
    ammo-type: pistol_ammo
    max-ammo: 12
    price: 300
    cooldown-ticks: 10
    sound: ENTITY_GENERIC_EXPLODE
    knockback: 0.3
    range: 30
  shotgun:
    material: WOODEN_HOE
    custom-model-data: 10002
    name: "&6霰弹枪"
    damage: 3.0
    ammo-type: shotgun_ammo
    max-ammo: 8
    price: 800
    cooldown-ticks: 30
    range: 15
    pellets: 5
  rifle:
    material: WOODEN_HOE
    custom-model-data: 10003
    name: "&c步枪"
    damage: 8.0
    ammo-type: rifle_ammo
    max-ammo: 30
    price: 1500
    cooldown-ticks: 4
    range: 50
    automatic: true

# ---------- 弹药物品 ----------
ammo-items:
  pistol_ammo:
    material: PAPER
    custom-model-data: 20001
    name: "&e手枪弹药"
    lore:
      - "&7装填手枪用"
  shotgun_ammo:
    material: PAPER
    custom-model-data: 20002
    name: "&6霰弹弹药"
  rifle_ammo:
    material: PAPER
    custom-model-data: 20003
    name: "&c步枪弹药"
```

- [ ] **Step 4: Delete WeaponHelper.kt**

```bash
git rm src/main/kotlin/cn/oneachina/zombieRun/manager/WeaponHelper.kt
```

- [ ] **Step 5: Full build**

```bash
.\gradlew build --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/resources/plugin.yml src/main/resources/config/config.yml
git commit -m "chore: remove WeaponMechanics dependency, add default custom-weapons and ammo configs"
```

---

### Task 10: PAPI 适配 + 最终验证

**Files:**
- Modify: `src/main/kotlin/cn/oneachina/zombieRun/papi/ZombieRunExpansion.kt`
- Modify: `src/main/kotlin/cn/oneachina/zombieRun/manager/ConfigManager.kt` (fix `saveConfig` visibility)

- [ ] **Step 1: Fix PAPI expansion to work with CoinManager**

Read ZombieRunExpansion.kt, find `%zombierun_coins%` handler and change it to use `plugin.coinManager.getCoins(player.uniqueId)`.

- [ ] **Step 2: Full build**

```bash
.\gradlew build --no-daemon 2>&1
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Final commit**

```bash
git add src/main/kotlin/cn/oneachina/zombieRun/papi/ZombieRunExpansion.kt
git commit -m "fix: update PAPI coins placeholder to use CoinManager"
```

- [ ] **Step 4: Push all**

```bash
git push origin master
```
Expected: `master -> master`
