package cn.oneachina.zombieRun

import cn.oneachina.zombieRun.command.DoorPerformanceCommand
import cn.oneachina.zombieRun.command.ZombieRunCommand
import cn.oneachina.zombieRun.listener.GameListener
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
    var weaponMechanicsAvailable = false

    override fun onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            ZombieRunExpansion(this).register()
        }

        weaponMechanicsAvailable = Bukkit.getPluginManager().getPlugin("WeaponMechanics") != null
        if (!weaponMechanicsAvailable) {
            logger.warning("WeaponMechanics 未安装，枪械功能将被禁用")
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

        Bukkit.getScheduler().runTaskLater(this, Runnable {
            doorManager.reset()
        }, 20L)

        val pm = Bukkit.getPluginManager()
        pm.registerEvents(GameListener(this), this)
        pm.registerEvents(miscManager, this)

        val zrCommand = ZombieRunCommand(this)
        getCommand("zr")?.setExecutor(zrCommand)
        getCommand("zr")?.tabCompleter = zrCommand
        getCommand("doorperf")?.setExecutor(DoorPerformanceCommand(this))

        logger.info("ZombieRun 核心已启用")
    }

    override fun onDisable() {
        doorManager.reset()
        respawnManager.clear()
        gameManager.clear()
        buttonManager.clear()
    }
}
