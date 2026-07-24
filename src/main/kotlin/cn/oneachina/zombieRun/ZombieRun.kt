package cn.oneachina.zombieRun

import cn.oneachina.zombieRun.command.DoorPerformanceCommand
import cn.oneachina.zombieRun.command.ZombieRunCommand
import cn.oneachina.zombieRun.gui.ProfileGUI
import cn.oneachina.zombieRun.gui.QuestGUI
import cn.oneachina.zombieRun.gui.ShopGUI
import cn.oneachina.zombieRun.gui.TitleGUI
import cn.oneachina.zombieRun.listener.CombatListener
import cn.oneachina.zombieRun.listener.GameListener
import cn.oneachina.zombieRun.listener.PlayerTaskTracker
import cn.oneachina.zombieRun.listener.ProgressionListener
import cn.oneachina.zombieRun.listener.StaminaListener
import cn.oneachina.zombieRun.listener.WeaponListener
import cn.oneachina.zombieRun.manager.*
import cn.oneachina.zombieRun.papi.ZombieRunExpansion
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class ZombieRun : JavaPlugin() {
    var debugMode = false

    val configManager: ConfigManager by lazy { ConfigManager(this).apply { loadConfig() } }
    val doorManager: DoorManager by lazy { DoorManager(this).apply { loadDoors() } }
    val doorZoneManager: DoorZoneManager by lazy { DoorZoneManager() }
    val buttonManager: ButtonManager by lazy { ButtonManager(this).apply { loadButtons() } }
    val respawnManager: RespawnManager by lazy { RespawnManager(this).apply { loadRespawns() } }
    val gameManager: GameManager by lazy { GameManager(this) }
    val staminaManager: StaminaManager by lazy { StaminaManager(this).apply { init() } }
    val miscManager: MiscManager by lazy { MiscManager(this) }
    val startEffectManager: StartEffectManager by lazy { StartEffectManager(this).apply { loadEffects() } }
    val weaponManager: WeaponManager by lazy { WeaponManager(this).apply { loadWeapons() } }
    val healthManager: HealthManager by lazy { HealthManager(this) }
    val coinManager: CoinManager by lazy { CoinManager(this).apply { init() } }
    val progressionManager: ProgressionManager by lazy { ProgressionManager(this).apply { init() } }
    val progressionListener: ProgressionListener by lazy { ProgressionListener(this) }
    val questManager: QuestManager by lazy { QuestManager(this).apply { init() } }
    val titleManager: TitleManager by lazy { TitleManager(this) }
    val nametagManager: NametagManager by lazy { NametagManager(this).apply { init() } }
    val shopGUI: ShopGUI by lazy { ShopGUI(this) }
    val profileGUI: ProfileGUI by lazy { ProfileGUI(this) }
    val questGUI: QuestGUI by lazy { QuestGUI(this) }
    val titleGUI: TitleGUI by lazy { TitleGUI(this) }

    val combatConfig: CombatConfig by lazy { configManager.loadCombatConfig() }
    val economyConfig: EconomyConfig by lazy { configManager.loadEconomyConfig() }
    val balanceConfig: BalanceConfig by lazy { configManager.loadBalanceConfig() }

    override fun onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            ZombieRunExpansion(this).register()
        }

        Bukkit.getGlobalRegionScheduler().runDelayed(this, { _ ->
            doorManager.reset()
        }, 20L)

        val pm = Bukkit.getPluginManager()
        val taskTracker = PlayerTaskTracker()
        pm.registerEvents(GameListener(this, taskTracker), this)
        pm.registerEvents(CombatListener(this, taskTracker), this)
        pm.registerEvents(StaminaListener(this), this)
        pm.registerEvents(WeaponListener(this), this)
        pm.registerEvents(shopGUI, this)
        pm.registerEvents(profileGUI, this)
        pm.registerEvents(questGUI, this)
        pm.registerEvents(titleGUI, this)
        pm.registerEvents(miscManager, this)

        val zrCommand = ZombieRunCommand(this)
        getCommand("zr")?.setExecutor(zrCommand)
        getCommand("zr")?.tabCompleter = zrCommand
        getCommand("doorperf")?.setExecutor(DoorPerformanceCommand(this))

        logger.info("ZombieRun 核心已启用")
    }

    override fun onDisable() {
        Bukkit.getOnlinePlayers().forEach { player ->
            player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.baseValue = 20.0
            player.health = 20.0
            player.clearActivePotionEffects()
        }
        coinManager.close()
        doorManager.reset()
        respawnManager.clear()
        gameManager.clear()
        staminaManager.clear()
        progressionManager.close()
        buttonManager.clear()
        nametagManager.clearAll()
        healthManager.clearAll()
    }
}
