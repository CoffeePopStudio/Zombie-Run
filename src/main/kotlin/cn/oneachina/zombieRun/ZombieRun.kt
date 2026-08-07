package cn.oneachina.zombieRun

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
import cn.oneachina.zombieRun.manager.*
import cn.oneachina.zombieRun.papi.ZombieRunExpansion
import cn.oneachina.zombieRun.service.WorldService
import cn.oneachina.zombieRun.util.DebugLogger
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class ZombieRun : JavaPlugin() {
    var debugMode = false

    private val postoolUsers = mutableSetOf<UUID>()

    val worldService: WorldService by lazy { WorldService(this).apply { init() } }

    val configManager: ConfigManager by lazy { ConfigManager(this).apply { loadConfig() } }
    val databaseManager: DatabaseManager by lazy { DatabaseManager(this).apply { init() } }
    val doorManager: DoorManager by lazy { DoorManager(this).apply { loadDoors() } }
    val doorZoneManager: DoorZoneManager by lazy { DoorZoneManager() }
    val buttonManager: ButtonManager by lazy { ButtonManager(this).apply { loadButtons() } }
    val respawnManager: RespawnManager by lazy { RespawnManager(this).apply { loadRespawns() } }
    val gameManager: GameManager by lazy { GameManager(this) }
    val staminaManager: StaminaManager by lazy { StaminaManager(this).apply { init() } }
    val miscManager: MiscManager by lazy { MiscManager(this) }
    val startEffectManager: StartEffectManager by lazy { StartEffectManager(this).apply { loadEffects() } }
    val weaponManager: WeaponManager by lazy { WeaponManager(this) }
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
    lateinit var gameListener: GameListener

    fun isPostoolActive(player: Player): Boolean = postoolUsers.contains(player.uniqueId)
    fun activatePostool(player: Player) { postoolUsers.add(player.uniqueId) }
    fun deactivatePostool(player: Player) { postoolUsers.remove(player.uniqueId) }

    override fun onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            ZombieRunExpansion(this).register()
        }

        Bukkit.getGlobalRegionScheduler().runDelayed(this, { _ ->
            doorManager.reset()
        }, 20L)

        val pm = Bukkit.getPluginManager()
        val taskTracker = PlayerTaskTracker()
        gameListener = GameListener(this, taskTracker)
        pm.registerEvents(gameListener, this)
        pm.registerEvents(CombatListener(this, taskTracker), this)
        pm.registerEvents(StaminaListener(this), this)
        pm.registerEvents(shopGUI, this)
        pm.registerEvents(profileGUI, this)
        pm.registerEvents(questGUI, this)
        pm.registerEvents(titleGUI, this)
        pm.registerEvents(miscManager, this)

        val zrCommand = ZombieRunCommand(this)
        getCommand("zr")?.setExecutor(zrCommand)
        getCommand("zr")?.tabCompleter = zrCommand

        DebugLogger.init(this)
        logger.info("ZombieRun 核心已启用")
    }

    override fun onDisable() {
        Bukkit.getOnlinePlayers().forEach { player ->
            player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.baseValue = 20.0
            player.health = 20.0
            player.clearActivePotionEffects()
        }
        coinManager.close()
        databaseManager.close()
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
