package cn.oneachina.zombierun.v2.plugin

import cn.oneachina.zombierun.v2.application.door.DoorApplicationService
import cn.oneachina.zombierun.v2.infrastructure.bukkit.BukkitBlockOpsPort
import cn.oneachina.zombierun.v2.infrastructure.bukkit.BukkitPlayerMessagePort
import cn.oneachina.zombierun.v2.infrastructure.bukkit.BukkitTeleporterPort
import cn.oneachina.zombierun.v2.infrastructure.bukkit.BukkitWorldAccessPort
import cn.oneachina.zombierun.v2.infrastructure.bukkit.command.Zr2Command
import cn.oneachina.zombierun.v2.infrastructure.bukkit.listener.V2DoorListener
import cn.oneachina.zombierun.v2.infrastructure.bukkit.scheduler.BukkitSchedulerPort
import cn.oneachina.zombierun.v2.infrastructure.config.ArenaYamlRepository
import cn.oneachina.zombierun.v2.infrastructure.config.BlockSnapshotStore
import cn.oneachina.zombierun.v2.infrastructure.config.V2SettingsLoader
import cn.oneachina.zombierun.v2.ports.BlockOpsPort
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.ports.TeleporterPort
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

    val settingsLoader = V2SettingsLoader(plugin.dataFolder, logger)
    val arenaRepository = ArenaYamlRepository(plugin.dataFolder, logger)
    val snapshotStore = BlockSnapshotStore(plugin.dataFolder)

    val doorService = DoorApplicationService(
        arenaRepository = arenaRepository,
        snapshotStore = snapshotStore,
        blockOps = blockOps,
        worldAccess = worldAccess,
        scheduler = scheduler,
        taskRegistry = taskRegistry,
        messages = messages,
        teleporter = teleporter,
        logger = logger,
    )

    fun enable() {
        services.register(SchedulerPort::class, scheduler)
        services.register(WorldAccessPort::class, worldAccess)
        services.register(BlockOpsPort::class, blockOps)
        services.register(PlayerMessagePort::class, messages)
        services.register(TeleporterPort::class, teleporter)
        services.register(TaskRegistry::class, taskRegistry)
        services.register(ArenaYamlRepository::class, arenaRepository)
        services.register(BlockSnapshotStore::class, snapshotStore)
        services.register(DoorApplicationService::class, doorService)

        val settings = settingsLoader.load()
        try {
            arenaRepository.loadAll()
        } catch (e: Exception) {
            logger.severe("arena config load failed: ${e.message}")
        }

        val doorListener = V2DoorListener(doorService, arenaRepository)
        plugin.server.pluginManager.registerEvents(doorListener, plugin)

        val command = Zr2Command(this, settings.defaultWorld)
        plugin.getCommand("zr2")?.setExecutor(command)
        plugin.getCommand("zr2")?.tabCompleter = command
    }

    fun disable() {
        doorService.cancelAllSessions()
        taskRegistry.cancelAll()
    }
}
