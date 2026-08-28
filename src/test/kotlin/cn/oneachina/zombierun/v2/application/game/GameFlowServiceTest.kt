package cn.oneachina.zombierun.v2.application.game

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.GameEndedEvent
import cn.oneachina.zombierun.v2.application.event.GameStartedEvent
import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnType
import cn.oneachina.zombierun.v2.domain.door.Vec3
import cn.oneachina.zombierun.v2.domain.game.GamePhase
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.domain.game.FinishType
import cn.oneachina.zombierun.v2.domain.game.MapFlowDefinition
import cn.oneachina.zombierun.v2.domain.game.MapFlowFinish
import cn.oneachina.zombierun.v2.domain.game.MapFlowStage
import cn.oneachina.zombierun.v2.application.event.PlayerPassedDoorEvent
import cn.oneachina.zombierun.v2.infrastructure.config.ArenaYamlRepository
import cn.oneachina.zombierun.v2.infrastructure.config.V2Settings
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.PlayerRef
import cn.oneachina.zombierun.v2.ports.RegionLocation
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.ports.TaskHandle
import cn.oneachina.zombierun.v2.ports.TeleporterPort
import cn.oneachina.zombierun.v2.ports.WorldAccessPort
import cn.oneachina.zombierun.v2.support.TaskRegistry
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.UUID
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeHandle(
    var callback: ((TaskHandle) -> Unit)? = null,
    var laterAction: (() -> Unit)? = null,
) : TaskHandle {
    override var isCancelled: Boolean = false
        private set

    override fun cancel() {
        isCancelled = true
    }
}

private class FakeScheduler : SchedulerPort {
    val timers = mutableListOf<FakeHandle>()
    val laters = mutableListOf<FakeHandle>()

    override fun globalTimer(delayTicks: Long, periodTicks: Long, callback: (TaskHandle) -> Unit): TaskHandle {
        val handle = FakeHandle(callback = callback)
        timers.add(handle)
        return handle
    }

    override fun globalLater(delayTicks: Long, action: () -> Unit): TaskHandle {
        val handle = FakeHandle(laterAction = action)
        laters.add(handle)
        return handle
    }

    override fun regionExecute(location: RegionLocation, action: () -> Unit) {
        action()
    }

    fun tickTimers() {
        timers.filter { !it.isCancelled }.toList().forEach { it.callback?.invoke(it) }
    }

    fun runLaters() {
        laters.filter { !it.isCancelled }.toList().forEach { it.laterAction?.invoke() }
    }
}

private class FakeWorld : WorldAccessPort {
    val playersByWorld = mutableMapOf<String, MutableList<UUID>>()
    val names = mutableMapOf<UUID, String>()

    fun setPlayers(world: String, ids: List<UUID>) {
        playersByWorld[world] = ids.toMutableList()
        ids.forEach { names.putIfAbsent(it, "P$it") }
    }

    override fun playersIn(worldName: String): List<PlayerRef> =
        playersByWorld[worldName].orEmpty().map {
            PlayerRef(it, names[it] ?: it.toString(), worldName, Vec3(0.0, 0.0, 0.0))
        }

    override fun player(id: UUID): PlayerRef? {
        val entry = playersByWorld.entries.firstOrNull { id in it.value } ?: return null
        return PlayerRef(id, names[id] ?: id.toString(), entry.key, Vec3(0.0, 0.0, 0.0))
    }

    override fun worldLoaded(worldName: String): Boolean = true
}

private class FakeMessages : PlayerMessagePort {
    val chats = mutableListOf<String>()
    override fun actionBar(playerId: UUID, message: String) = Unit
    override fun chat(playerId: UUID, message: String) {
        chats.add(message)
    }
    override fun title(playerId: UUID, title: String, subtitle: String) = Unit
    override fun soundBell(worldName: String) = Unit
}

private class FakeTeleporter : TeleporterPort {
    val teleports = mutableListOf<Pair<UUID, String>>()
    override fun teleport(playerId: UUID, worldName: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        teleports.add(playerId to worldName)
    }
}

private class Fixture(globalMinPlayers: Int = 2) {
    val temp = createTempDirectory("zr2-test").toFile()
    val scheduler = FakeScheduler()
    val world = FakeWorld()
    val messages = FakeMessages()
    val teleporter = FakeTeleporter()
    val eventBus = ApplicationEventBus()
    val repo = ArenaYamlRepository(temp, V2Logger(Logger.getLogger("test")))
    val taskRegistry = TaskRegistry()
    val service = GameFlowService(
        settings = V2Settings(
            schema = 2,
            debug = false,
            defaultWorld = "world",
            startDelaySeconds = 5,
            minPlayers = globalMinPlayers,
            maxDurationSeconds = 60,
            staminaMax = 20.0,
            staminaSprintDrain = 0.25,
            staminaRegen = 0.08,
            staminaExhaustDelayTicks = 40,
            economy = cn.oneachina.zombierun.v2.domain.combat.EconomyRules(),
        ),
        arenaRepository = repo,
        worldAccess = world,
        scheduler = scheduler,
        taskRegistry = taskRegistry,
        messages = messages,
        teleporter = teleporter,
        logger = V2Logger(Logger.getLogger("test")),
        eventBus = eventBus,
    )
}

class GameFlowServiceTest {

    private fun uuid(seed: Int): UUID = UUID(0L, seed.toLong())

    @Test
    fun `auto start after countdown assigns one alpha and teleports everyone`() {
        val f = Fixture()
        f.repo.save(
            ArenaDefinition(
                name = "a",
                world = "w",
                respawns = listOf(
                    RespawnDefinition("r1", "w", RespawnType.PLAYER, 0.0, 64.0, 0.0, 0f, 0f, null),
                    RespawnDefinition("r2", "w", RespawnType.ZOMBIE, 1.0, 64.0, 0.0, 0f, 0f, null),
                ),
            ),
        )
        val ids = listOf(uuid(1), uuid(2), uuid(3))
        f.world.setPlayers("w", ids)
        f.service.start()

        // 第一个 tick：autoTick 检测人数并开始倒计时
        f.scheduler.tickTimers()
        assertEquals(GamePhase.STARTING, f.service.phaseOf("w"))

        // 再跑 6 个 tick：5 秒倒计时 + 1 次触发开局
        repeat(6) { f.scheduler.tickTimers() }
        assertEquals(GamePhase.RUNNING, f.service.phaseOf("w"))

        val started = mutableListOf<GameStartedEvent>()
        f.eventBus.subscribe(GameStartedEvent::class.java) { started.add(it) }
        // 事件在 startGame 内已 publish；这里改为先订阅会丢失，重新手动开局验证
        f.service.reset("w")
        f.service.forceStart("w")
        assertEquals(1, started.size)
        val assignments = started[0].playerAssignments
        assertEquals(1, assignments.values.count { it == GameTeam.ZOMBIE_MAIN.name })
        assertEquals(2, assignments.values.count { it == GameTeam.HUMAN.name })
        assertTrue(f.teleporter.teleports.isNotEmpty())
    }

    @Test
    fun `countdown cancels when players drop below minimum`() {
        val f = Fixture()
        f.repo.save(ArenaDefinition("a", "w"))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f.service.start()
        f.scheduler.tickTimers()
        assertEquals(GamePhase.STARTING, f.service.phaseOf("w"))

        f.world.setPlayers("w", listOf(uuid(1)))
        f.scheduler.tickTimers()
        assertEquals(GamePhase.WAITING, f.service.phaseOf("w"))
    }

    @Test
    fun `lethal zombie attack infects last human and ends game`() {
        val f = Fixture()
        f.repo.save(ArenaDefinition("a", "w"))
        val ids = listOf(uuid(1), uuid(2))
        f.world.setPlayers("w", ids)
        f.service.start()

        val ended = mutableListOf<GameEndedEvent>()
        f.eventBus.subscribe(GameEndedEvent::class.java) { ended.add(it) }

        f.service.forceStart("w")
        val instance = f.service.instance("w")!!
        val alpha = instance.alphaId()!!
        val human = f.world.playersIn("w").map { it.id }.first { it != alpha }
        val infected = f.service.onCombat(human, alpha, "w", lethal = true)

        assertTrue(infected)
        assertEquals(GamePhase.ENDED, f.service.phaseOf("w"))
        assertEquals(GameTeam.ZOMBIE, instance.teamOf(human))
        assertEquals(1, ended.size)
        assertEquals(GameTeam.ZOMBIE_MAIN.name, ended[0].winner)
    }

    @Test
    fun `midgame join becomes zombie`() {
        val f = Fixture()
        f.repo.save(ArenaDefinition("a", "w"))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f.service.start()
        f.service.forceStart("w")

        val joiner = uuid(3)
        f.service.onPlayerJoin("w", joiner)
        assertEquals(GameTeam.ZOMBIE, f.service.instance("w")?.teamOf(joiner))
    }

    @Test
    fun `max duration ends with human victory`() {
        val f = Fixture()
        f.repo.save(ArenaDefinition("a", "w"))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f.service.start()
        f.service.forceStart("w")

        val ended = mutableListOf<GameEndedEvent>()
        f.eventBus.subscribe(GameEndedEvent::class.java) { ended.add(it) }

        f.scheduler.runLaters()
        assertEquals(GamePhase.ENDED, f.service.phaseOf("w"))
        assertEquals(GameTeam.HUMAN.name, ended[0].winner)
    }

    @Test
    fun `door passed updates room through game context bridge`() {
        val f = Fixture()
        f.repo.save(ArenaDefinition("a", "w"))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f.service.start()
        f.service.forceStart("w")

        f.service.setRoom("w", uuid(1), 2)
        assertEquals(2, f.service.instance("w")?.roomOf(uuid(1)))
    }

    @Test
    fun `escape countdown ends with human victory`() {
        val f = Fixture()
        f.repo.save(ArenaDefinition("a", "w"))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f.service.start()
        f.service.forceStart("w")

        val ended = mutableListOf<GameEndedEvent>()
        f.eventBus.subscribe(GameEndedEvent::class.java) { ended.add(it) }

        assertTrue(f.service.triggerEscape("w", "tester"))
        repeat(GameFlowService.ESCAPE_SECONDS + 1) { f.scheduler.tickTimers() }

        assertEquals(GamePhase.ENDED, f.service.phaseOf("w"))
        assertEquals(GameTeam.HUMAN.name, ended.single().winner)
    }

    @Test
    fun `map flow advances through door events and ends human win`() {
        val f = Fixture()
        val flow = MapFlowDefinition(
            arenaName = "a",
            world = "w",
            minPlayers = 2,
            startDelaySeconds = 5,
            maxDurationSeconds = 60,
            stages = listOf(
                MapFlowStage("s1", "大门", listOf(1, 2), "s2"),
                MapFlowStage("s2", "终点线", listOf(3)),
            ),
            finish = MapFlowFinish(FinishType.DOOR, 3),
        )
        f.repo.save(ArenaDefinition("a", "w", mapFlow = flow))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f.service.start()
        f.service.forceStart("w")

        assertEquals(GamePhase.RUNNING, f.service.phaseOf("w"))
        assertTrue(f.service.isDoorUnlocked("w", 1))
        assertTrue(f.service.isDoorUnlocked("w", 2))
        assertFalse(f.service.isDoorUnlocked("w", 3))

        f.eventBus.publish(PlayerPassedDoorEvent("w", uuid(1), listOf(2)))
        assertTrue(f.service.isDoorUnlocked("w", 3))
        assertFalse(f.service.isDoorUnlocked("w", 1))

        f.eventBus.publish(PlayerPassedDoorEvent("w", uuid(1), listOf(3)))
        assertEquals(GamePhase.ENDED, f.service.phaseOf("w"))
    }

    @Test
    fun `map flow countdown uses arena min players not global setting`() {
        // 全局 min-players=8，map-flow min-players=2：3 名玩家必须能正常开局。
        // 回归：tickCountdown 曾错误使用 settings.minPlayers，导致倒计时反复开始/取消。
        val f = Fixture(globalMinPlayers = 8)
        val flow = MapFlowDefinition(
            arenaName = "a",
            world = "w",
            minPlayers = 2,
            startDelaySeconds = 5,
            maxDurationSeconds = 60,
            stages = listOf(
                MapFlowStage("s1", "大门", listOf(1), "s2"),
                MapFlowStage("s2", "终点", listOf(2)),
            ),
            finish = MapFlowFinish(FinishType.DOOR, 2),
        )
        f.repo.save(ArenaDefinition("a", "w", mapFlow = flow))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2), uuid(3)))
        f.service.start()

        // 第一次 tick 进入倒计时
        f.scheduler.tickTimers()
        assertEquals(GamePhase.STARTING, f.service.phaseOf("w"))

        // 倒计时期间不会被“人数不足”取消，5 秒后正常开局
        repeat(6) { f.scheduler.tickTimers() }
        assertEquals(GamePhase.RUNNING, f.service.phaseOf("w"))
    }

    @Test
    fun `alpha leaving world is replaced and last human leaving ends game`() {
        val f = Fixture()
        f.repo.save(ArenaDefinition("a", "w"))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f.service.start()
        f.service.forceStart("w")

        val instance = f.service.instance("w")!!
        val alpha = instance.alphaId()!!
        val human = f.world.playersIn("w").map { it.id }.first { it != alpha }

        // 母体传送离开世界：补位唯一人类为新母体 → 人类清零 → 僵尸获胜
        f.service.onPlayerLeaveWorld("w", alpha)
        assertEquals(GamePhase.ENDED, f.service.phaseOf("w"))
        assertNull(f.service.instance("w")?.teamOf(alpha))
        assertTrue(f.service.isMotherReleased("w"))

        // 另一条路径：人类离开世界同样触发结算
        val f2 = Fixture()
        f2.repo.save(ArenaDefinition("a", "w"))
        f2.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f2.service.start()
        f2.service.forceStart("w")
        val alpha2 = f2.service.instance("w")!!.alphaId()!!
        val human2 = f2.world.playersIn("w").map { it.id }.first { it != alpha2 }
        f2.service.onPlayerLeaveWorld("w", human2)
        assertEquals(GamePhase.ENDED, f2.service.phaseOf("w"))
    }

    @Test
    fun `map flow door events after restart reset finished flow`() {
        // 上一局 HUMAN_WIN 后未等自动复位即强制开局：流程必须被复位而不是卡在结束态
        val f = Fixture()
        val flow = MapFlowDefinition(
            arenaName = "a",
            world = "w",
            minPlayers = 2,
            startDelaySeconds = 5,
            maxDurationSeconds = 60,
            stages = listOf(
                MapFlowStage("s1", "大门", listOf(1), "s2"),
                MapFlowStage("s2", "终点", listOf(2)),
            ),
            finish = MapFlowFinish(FinishType.DOOR, 2),
        )
        f.repo.save(ArenaDefinition("a", "w", mapFlow = flow))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f.service.start()
        f.service.forceStart("w")

        f.eventBus.publish(PlayerPassedDoorEvent("w", uuid(1), listOf(1)))
        f.eventBus.publish(PlayerPassedDoorEvent("w", uuid(1), listOf(2)))
        assertEquals(GamePhase.ENDED, f.service.phaseOf("w"))

        // 不跑自动复位任务，直接再次强制开局
        f.service.forceStart("w")
        assertEquals(GamePhase.RUNNING, f.service.phaseOf("w"))
        assertTrue(f.service.isDoorUnlocked("w", 1))
    }

    @Test
    fun `one hundred repeated matches keep task registry bounded`() {
        val f = Fixture()
        f.repo.save(ArenaDefinition("a", "w"))
        f.world.setPlayers("w", listOf(uuid(1), uuid(2)))
        f.service.start()

        repeat(100) {
            f.service.forceStart("w")
            f.scheduler.runLaters()
            assertEquals(GamePhase.ENDED, f.service.phaseOf("w"))
            f.service.reset("w")
        }

        assertEquals(GamePhase.WAITING, f.service.phaseOf("w"))
        assertTrue(f.taskRegistry.size() <= 5, "task registry leaked: size=${f.taskRegistry.size()}")
        f.service.stop()
        f.taskRegistry.cancelAll()
        assertEquals(0, f.taskRegistry.size())
    }
}
