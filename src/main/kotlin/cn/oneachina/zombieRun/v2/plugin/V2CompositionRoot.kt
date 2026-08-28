package cn.oneachina.zombierun.v2.plugin

import cn.oneachina.zombierun.v2.application.combat.CombatHealthService
import cn.oneachina.zombierun.v2.application.combat.StaminaService
import cn.oneachina.zombierun.v2.application.door.DoorApplicationService
import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.application.task.TaskService
import cn.oneachina.zombierun.v2.application.weapon.WeaponService
import cn.oneachina.zombierun.v2.domain.combat.StaminaRules
import cn.oneachina.zombierun.v2.infrastructure.bukkit.BukkitBlockOpsPort
import cn.oneachina.zombierun.v2.infrastructure.bukkit.BukkitPlayerMessagePort
import cn.oneachina.zombierun.v2.infrastructure.bukkit.BukkitTeleporterPort
import cn.oneachina.zombierun.v2.infrastructure.bukkit.BukkitWorldAccessPort
import cn.oneachina.zombierun.v2.infrastructure.bukkit.command.Zr2Command
import cn.oneachina.zombierun.v2.infrastructure.bukkit.gui.GuiService
import cn.oneachina.zombierun.v2.infrastructure.bukkit.hook.ZombieRunV2Expansion
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2CombatListener
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2DoorListener
import cn.oneachina.zombierun.v2.domain.combat.CombatRules
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2BattleListener
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2HazardListener
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2PlayerStateListener
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2ProtectionListener
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.bindPlayerStateBridge
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2GameListener
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2PlayerDataListener
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2TaskListener
import cn.oneachina.zombierun.v2.infrastructure.bukkit.scheduler.BukkitSchedulerPort
import cn.oneachina.zombierun.v2.infrastructure.bukkit.weapon.QaWeaponIntegrationPort
import cn.oneachina.zombierun.v2.infrastructure.config.ArenaYamlRepository
import cn.oneachina.zombierun.v2.infrastructure.config.BlockSnapshotStore
import cn.oneachina.zombierun.v2.infrastructure.config.V2SettingsLoader
import cn.oneachina.zombierun.v2.infrastructure.config.V1MigrationService
import cn.oneachina.zombierun.v2.infrastructure.config.TaskYamlRepository
import cn.oneachina.zombierun.v2.infrastructure.config.WeaponYamlRepository
import cn.oneachina.zombierun.v2.infrastructure.storage.SqlitePlayerDataRepository
import cn.oneachina.zombierun.v2.infrastructure.storage.SqlitePlayerTaskRepository
import cn.oneachina.zombierun.v2.ports.BlockOpsPort
import cn.oneachina.zombierun.v2.ports.PlayerDataPort
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.PlayerTaskPort
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.ports.TeleporterPort
import cn.oneachina.zombierun.v2.ports.WeaponIntegrationPort
import cn.oneachina.zombierun.v2.ports.WorldAccessPort
import cn.oneachina.zombierun.v2.support.TaskRegistry
import cn.oneachina.zombierun.v2.support.V2Logger

/**
 * v2 唯一对象装配点：手工构造器注入，无反射 DI。
 */
class V2CompositionRoot(private val plugin: ZombieRunV2Plugin) {

    val services = V2ServiceRegistry()
    val logger: V2Logger = V2Logger(plugin.logger)
    val taskRegistry = TaskRegistry()
    val scheduler: SchedulerPort = BukkitSchedulerPort(plugin)
    val worldAccess: WorldAccessPort = BukkitWorldAccessPort()
    val blockOps: BlockOpsPort = BukkitBlockOpsPort(scheduler)
    val messages: PlayerMessagePort = BukkitPlayerMessagePort()
    val teleporter: TeleporterPort = BukkitTeleporterPort()
    val eventBus = ApplicationEventBus()

    val settingsLoader = V2SettingsLoader(plugin.dataFolder, logger)
    val arenaRepository = ArenaYamlRepository(plugin.dataFolder, logger)
    val snapshotStore = BlockSnapshotStore(plugin.dataFolder)
    val weaponRepository = WeaponYamlRepository(plugin.dataFolder, logger)
    val weaponIntegration: WeaponIntegrationPort = QaWeaponIntegrationPort(logger)
    val weaponService = WeaponService(weaponRepository, weaponIntegration, messages, logger)
    val playerDataRepository: PlayerDataPort = SqlitePlayerDataRepository(plugin.dataFolder, logger)
    val v1MigrationService = V1MigrationService(plugin.dataFolder, arenaRepository, playerDataRepository, snapshotStore, logger)
    val playerDataService = PlayerDataService(playerDataRepository, messages, logger, eventBus)
    val playerDataListener = V2PlayerDataListener(playerDataService)
    val taskRepository = TaskYamlRepository(plugin.dataFolder, logger)
    val taskStorage: PlayerTaskPort = SqlitePlayerTaskRepository(plugin.dataFolder, logger)
    val taskService = TaskService(taskStorage, taskRepository, playerDataService, messages, logger, eventBus)
    val taskListener = V2TaskListener(taskService)
    val guiService = GuiService(playerDataService, weaponService, taskService, logger)
    val staminaService = StaminaService(logger)
    val combatHealth = CombatHealthService(logger)
    val combatListener = V2CombatListener(staminaService, scheduler, taskRegistry)

    lateinit var gameFlow: GameFlowService
        private set

    val doorService: DoorApplicationService = DoorApplicationService(
        arenaRepository = arenaRepository,
        snapshotStore = snapshotStore,
        blockOps = blockOps,
        worldAccess = worldAccess,
        scheduler = scheduler,
        taskRegistry = taskRegistry,
        messages = messages,
        teleporter = teleporter,
        logger = logger,
        gameContext = GameContextBridge(),
        eventBus = eventBus,
    )

    /** 门系统通过该桥访问对局状态（对局模块可能在 enable 时构造）。 */
    private inner class GameContextBridge : cn.oneachina.zombierun.v2.ports.GameContextPort {
        override fun teamOf(worldName: String, playerId: java.util.UUID) =
            if (::gameFlow.isInitialized) gameFlow.teamOf(worldName, playerId) else null

        override fun setRoom(worldName: String, playerId: java.util.UUID, room: Int) {
            if (::gameFlow.isInitialized) gameFlow.setRoom(worldName, playerId, room)
        }

        override fun currentStageDoorNumbers(worldName: String): List<Int>? =
            if (::gameFlow.isInitialized) gameFlow.currentStageDoorNumbers(worldName) else null

        override fun isDoorUnlocked(worldName: String, doorNumber: Int): Boolean =
            if (::gameFlow.isInitialized) gameFlow.isDoorUnlocked(worldName, doorNumber) else true
    }

    fun enable() {
        services.register(SchedulerPort::class, scheduler)
        services.register(WorldAccessPort::class, worldAccess)
        services.register(BlockOpsPort::class, blockOps)
        services.register(PlayerMessagePort::class, messages)
        services.register(TeleporterPort::class, teleporter)
        services.register(TaskRegistry::class, taskRegistry)
        services.register(ArenaYamlRepository::class, arenaRepository)
        services.register(BlockSnapshotStore::class, snapshotStore)
        services.register(V1MigrationService::class, v1MigrationService)
        services.register(WeaponYamlRepository::class, weaponRepository)
        services.register(WeaponIntegrationPort::class, weaponIntegration)
        services.register(WeaponService::class, weaponService)
        services.register(PlayerDataPort::class, playerDataRepository)
        services.register(PlayerDataService::class, playerDataService)
        services.register(PlayerTaskPort::class, taskStorage)
        services.register(TaskService::class, taskService)

        val settings = settingsLoader.load()
        logger.debugEnabled = settings.debug
        combatHealth.applyRules(loadCombatRules())
        staminaService.applyRules(
            StaminaRules(
                max = settings.staminaMax,
                sprintDrainPerTick = settings.staminaSprintDrain,
                regenPerTick = settings.staminaRegen,
                exhaustRecoveryDelayTicks = settings.staminaExhaustDelayTicks,
            ),
        )
        try {
            arenaRepository.loadAll()
        } catch (e: Exception) {
            logger.severe("arena config load failed: ${e.message}")
        }
        try {
            weaponService.reload()
        } catch (e: Exception) {
            logger.severe("weapon config load failed: ${e.message}")
        }

        gameFlow = GameFlowService(
            settings = settings,
            arenaRepository = arenaRepository,
            worldAccess = worldAccess,
            scheduler = scheduler,
            taskRegistry = taskRegistry,
            messages = messages,
            teleporter = teleporter,
            logger = logger,
            eventBus = eventBus,
            playerData = playerDataService,
            weaponService = weaponService,
        )
        services.register(GameFlowService::class, gameFlow)
        services.register(DoorApplicationService::class, doorService)

        if (plugin.server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            ZombieRunV2Expansion(playerDataService, gameFlow).register()
            logger.info("PlaceholderAPI expansion registered: %zombierun_*%")
        }

        plugin.server.pluginManager.registerEvents(V2DoorListener(doorService, arenaRepository, gameFlow), plugin)
        plugin.server.pluginManager.registerEvents(V2GameListener(gameFlow, guiService), plugin)
        plugin.server.pluginManager.registerEvents(V2PlayerDataListener(playerDataService), plugin)
        plugin.server.pluginManager.registerEvents(V2TaskListener(taskService), plugin)
        plugin.server.pluginManager.registerEvents(combatListener, plugin)
        plugin.server.pluginManager.registerEvents(guiService, plugin)

        // 战斗/状态接管/保护监听（依赖 gameFlow，故在 enable 内构造）
        val battleListener = V2BattleListener(plugin, gameFlow, combatHealth, playerDataService, settings, logger)
        val playerStateListener = V2PlayerStateListener(plugin, gameFlow, combatHealth, logger)
        val protectionListener = V2ProtectionListener(plugin, gameFlow)
        plugin.server.pluginManager.registerEvents(battleListener, plugin)
        plugin.server.pluginManager.registerEvents(playerStateListener, plugin)
        plugin.server.pluginManager.registerEvents(protectionListener, plugin)
        plugin.server.pluginManager.registerEvents(V2HazardListener(gameFlow, combatHealth), plugin)
        bindPlayerStateBridge(eventBus, playerStateListener)
        gameFlow.zombieBuffApplier = { id -> cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2PlayerStateListener.zombieBuffs(id) }
        gameFlow.motherReleaseStateSync = { id -> cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2PlayerStateListener.unfreezeAlpha(id) }
        gameFlow.doorAutoOpener = { world, mode ->
            try {
                doorService.triggerAutoDoors(world, cn.oneachina.zombierun.v2.domain.door.DoorMode.valueOf(mode))
            } catch (e: Exception) {
                logger.warn("auto open $mode doors failed for $world: ${e.message}")
            }
        }

        gameFlow.start()
        combatListener.start()

        val command = Zr2Command(this, settings.defaultWorld)
        plugin.getCommand("zr2")?.setExecutor(command)
        plugin.getCommand("zr2")?.tabCompleter = command
        plugin.server.pluginManager.registerEvents(command, plugin)
    }

    private fun loadCombatRules(): CombatRules {
        val f = java.io.File(plugin.dataFolder, "config/settings.yml")
        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f)
        return CombatRules(
            swordDamage = yaml.getDouble("combat.sword-damage", 5.0),
            zombieDamage = yaml.getDouble("combat.zombie-damage", 5.0),
            zombieMainDamage = yaml.getDouble("combat.zombie-main-damage", 8.0),
            zombieMaxHealth = yaml.getDouble("combat.zombie-max-health", 120.0),
            zombieMainMaxHealth = yaml.getDouble("combat.zombie-main-max-health", 300.0),
            humanMaxHealth = yaml.getDouble("combat.human-max-health", 20.0),
            explosionDamageReduction = yaml.getDouble("combat.explosion-damage-reduction", 0.05),
        )
    }

    fun disable() {
        combatListener.stop()
        doorService.cancelAllSessions()
        if (::gameFlow.isInitialized) gameFlow.stop()
        taskRegistry.cancelAll()
        taskService.close()
        playerDataService.close()
    }
}
