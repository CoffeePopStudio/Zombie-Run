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
import cn.oneachina.zombieRun.listener.WeaponListener
import cn.oneachina.zombieRun.manager.*
import cn.oneachina.zombieRun.papi.ZombieRunExpansion
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class ZombieRun : JavaPlugin() {
    lateinit var configManager: ConfigManager
    lateinit var doorManager: DoorManager
    lateinit var doorZoneManager: DoorZoneManager
    lateinit var buttonManager: ButtonManager
    lateinit var respawnManager: RespawnManager
    lateinit var gameManager: GameManager
    lateinit var staminaManager: StaminaManager
    lateinit var miscManager: MiscManager
    lateinit var startEffectManager: StartEffectManager
    lateinit var weaponManager: WeaponManager
    lateinit var coinManager: CoinManager
    lateinit var progressionManager: ProgressionManager
    lateinit var progressionListener: ProgressionListener
    lateinit var questManager: QuestManager
    lateinit var titleManager: TitleManager
    lateinit var shopGUI: ShopGUI
    lateinit var profileGUI: ProfileGUI
    lateinit var questGUI: QuestGUI
    lateinit var titleGUI: TitleGUI

    override fun onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            ZombieRunExpansion(this).register()
        }

        configManager = ConfigManager(this).apply { loadConfig() }
        doorZoneManager = DoorZoneManager()
        doorManager = DoorManager(this).apply { loadDoors() }
        buttonManager = ButtonManager(this).apply { loadButtons() }
        respawnManager = RespawnManager(this).apply { loadRespawns() }
        gameManager = GameManager(this)
        staminaManager = StaminaManager(this).apply { init() }
        miscManager = MiscManager(this)
        startEffectManager = StartEffectManager(this).apply { loadEffects() }
        weaponManager = WeaponManager(this).apply { loadWeapons() }
        coinManager = CoinManager(this).apply { init() }
        progressionManager = ProgressionManager(this).apply { init() }
        progressionListener = ProgressionListener(this)
        questManager = QuestManager(this).apply { init() }
        titleManager = TitleManager(this)
        shopGUI = ShopGUI(this)
        profileGUI = ProfileGUI(this)
        questGUI = QuestGUI(this)
        titleGUI = TitleGUI(this)

        Bukkit.getGlobalRegionScheduler().runDelayed(this, { _ ->
            doorManager.reset()
        }, 20L)

        val pm = Bukkit.getPluginManager()
        val taskTracker = PlayerTaskTracker()
        pm.registerEvents(GameListener(this, taskTracker), this)
        pm.registerEvents(CombatListener(this, taskTracker), this)
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
        if (this::coinManager.isInitialized) {
            coinManager.close()
        }
        if (this::doorManager.isInitialized) {
            doorManager.reset()
        }
        if (this::respawnManager.isInitialized) {
            respawnManager.clear()
        }
        if (this::gameManager.isInitialized) {
            gameManager.clear()
        }
        if (this::progressionManager.isInitialized) {
            progressionManager.close()
        }
        if (this::buttonManager.isInitialized) {
            buttonManager.clear()
        }
    }
}
