package cn.oneachina.zombierun.v2.application.door

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.PlayerPassedDoorEvent
import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.door.BlockRegion
import cn.oneachina.zombierun.v2.domain.door.DoorBehavior
import cn.oneachina.zombierun.v2.domain.door.DoorBehaviorType
import cn.oneachina.zombierun.v2.domain.door.DoorDefinition
import cn.oneachina.zombierun.v2.domain.door.DoorMode
import cn.oneachina.zombierun.v2.domain.door.Portal
import cn.oneachina.zombierun.v2.domain.door.PortalAxis
import cn.oneachina.zombierun.v2.domain.door.PortalFront
import cn.oneachina.zombierun.v2.domain.door.Vec3
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.infrastructure.config.ArenaYamlRepository
import cn.oneachina.zombierun.v2.infrastructure.config.BlockSnapshotStore
import cn.oneachina.zombierun.v2.ports.BlockOpsPort
import cn.oneachina.zombierun.v2.ports.GameContextPort
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

/**
 * DoorApplicationService 编排层测试：全部端口用 fake，验证门会话生命周期、
 * 穿越记录、兜底、传送排除、门组联动、特殊门行为与锁定。
 */
class DoorApplicationServiceTest {

    private class FakeTaskHandle(
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
        val timers = mutableListOf<FakeTaskHandle>()
        val laters = mutableListOf<FakeTaskHandle>()

        override fun globalTimer(delayTicks: Long, periodTicks: Long, callback: (TaskHandle) -> Unit): TaskHandle {
            val handle = FakeTaskHandle(callback = callback)
            timers.add(handle)
            return handle
        }

        override fun globalLater(delayTicks: Long, action: () -> Unit): TaskHandle {
            val handle = FakeTaskHandle(laterAction = action)
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
        private val players = LinkedHashMap<UUID, PlayerRef>()

        fun addPlayer(id: UUID, worldName: String, position: Vec3) {
            players[id] = PlayerRef(id, "P$id", worldName, position)
        }

        fun setPosition(id: UUID, position: Vec3) {
            players[id]?.let { players[id] = it.copy(position = position) }
        }

        override fun playersIn(worldName: String): List<PlayerRef> =
            players.values.filter { it.worldName == worldName }

        override fun player(id: UUID): PlayerRef? = players[id]
        override fun worldLoaded(worldName: String): Boolean = true
    }

    private class FakeBlockOps : BlockOpsPort {
        val opened = mutableListOf<BlockRegion>()
        val closed = mutableListOf<Pair<String, BlockRegion>>()

        override fun openRegion(worldName: String, region: BlockRegion) {
            opened.add(region)
        }

        override fun closeRegion(
            worldName: String,
            region: BlockRegion,
            snapshot: Map<String, String>,
            fallbackMaterial: String,
        ) {
            closed.add(worldName to region)
        }

        override fun scanRegion(worldName: String, region: BlockRegion): Map<String, String> = emptyMap()
    }

    private class FakeMessages : PlayerMessagePort {
        val chats = mutableListOf<String>()
        val actionBars = mutableListOf<String>()
        val titles = mutableListOf<String>()

        override fun actionBar(playerId: UUID, message: String) {
            actionBars.add(message)
        }

        override fun chat(playerId: UUID, message: String) {
            chats.add(message)
        }

        override fun title(playerId: UUID, title: String, subtitle: String) {
            titles.add("$title|$subtitle")
        }

        override fun soundBell(worldName: String) = Unit
    }

    private class FakeTeleporter : TeleporterPort {
        data class Tp(val playerId: UUID, val worldName: String, val x: Double, val y: Double, val z: Double)
        val teleports = mutableListOf<Tp>()

        override fun teleport(
            playerId: UUID,
            worldName: String,
            x: Double,
            y: Double,
            z: Double,
            yaw: Float,
            pitch: Float,
        ) {
            teleports.add(Tp(playerId, worldName, x, y, z))
        }
    }

    private class FakeGameContext : GameContextPort {
        var unlocked: Boolean = true
        var team: GameTeam = GameTeam.HUMAN
        val rooms = mutableMapOf<UUID, Int>()

        override fun teamOf(worldName: String, playerId: UUID): GameTeam = team
        override fun setRoom(worldName: String, playerId: UUID, room: Int) {
            rooms[playerId] = room
        }

        override fun isDoorUnlocked(worldName: String, doorNumber: Int): Boolean = unlocked
    }

    private class Fixture {
        val temp = createTempDirectory("zr2-door-svc").toFile()
        val logger = V2Logger(Logger.getLogger("test"))
        val repo = ArenaYamlRepository(temp, logger)
        val snapshots = BlockSnapshotStore(temp)
        val blockOps = FakeBlockOps()
        val world = FakeWorld()
        val scheduler = FakeScheduler()
        val taskRegistry = TaskRegistry()
        val messages = FakeMessages()
        val teleporter = FakeTeleporter()
        val bus = ApplicationEventBus()
        val context = FakeGameContext()
        val playerId = UUID.randomUUID()

        val service = DoorApplicationService(
            arenaRepository = repo,
            snapshotStore = snapshots,
            blockOps = blockOps,
            worldAccess = world,
            scheduler = scheduler,
            taskRegistry = taskRegistry,
            messages = messages,
            teleporter = teleporter,
            logger = logger,
            gameContext = context,
            eventBus = bus,
        )

        fun door(
            number: Int,
            group: String? = null,
            open: Int = 1,
            close: Int = 1,
            behavior: DoorBehavior? = null,
        ) = DoorDefinition(
            id = "door_$number",
            world = "w",
            number = number,
            mode = DoorMode.NORMAL,
            group = group,
            openSeconds = open,
            closeSeconds = close,
            portal = Portal(
                axis = PortalAxis.X,
                front = PortalFront.POSITIVE,
                planeCoordinate = 100.0,
                transverseMin = 10.0,
                transverseMax = 20.0,
                yMin = 64.0,
                yMax = 66.0,
            ),
            region = BlockRegion(100, 64, 10, 100, 66, 20),
            snapshotId = null,
            fallbackMaterial = "STONE",
            behavior = behavior,
        )

        fun arenaWith(vararg doors: DoorDefinition) {
            repo.save(ArenaDefinition("a", "w", doors = doors.toList()))
        }

        fun addPlayer(position: Vec3 = Vec3(95.0, 65.0, 15.0)) {
            world.addPlayer(playerId, "w", position)
        }

        fun openSession() {
            scheduler.tickTimers() // OPENING: 1 -> 0
            scheduler.tickTimers() // openDoors -> CLOSING
        }

        fun closeSession() {
            scheduler.tickTimers() // CLOSING: 1 -> 0
            scheduler.tickTimers() // closeDoors
        }
    }

    @Test
    fun `trigger opens and closes door regions`() {
        val f = Fixture().apply {
            arenaWith(door(1))
            addPlayer()
        }

        val result = f.service.triggerDoor("w", 1, "op")

        assertTrue(result.success)
        assertNotNull(f.service.activeSessionInfo("w"))
        f.openSession()
        assertEquals(1, f.blockOps.opened.size)
        assertEquals(BlockRegion(100, 64, 10, 100, 66, 20), f.blockOps.opened.single())

        f.closeSession()
        assertEquals(1, f.blockOps.closed.size)
        assertEquals("w", f.blockOps.closed.single().first)
        assertNull(f.service.activeSessionInfo("w"))
    }

    @Test
    fun `crossing movement publishes door passed event and updates room`() {
        val f = Fixture().apply {
            arenaWith(door(1))
            addPlayer()
        }
        val events = mutableListOf<PlayerPassedDoorEvent>()
        f.bus.subscribe(PlayerPassedDoorEvent::class.java) { events.add(it) }

        f.service.triggerDoor("w", 1, "op")
        f.openSession()

        val crossed = f.service.onPlayerMove(
            "w",
            f.playerId,
            Vec3(99.0, 65.0, 15.0),
            Vec3(101.0, 65.0, 15.0),
        )
        assertTrue(crossed)

        f.closeSession()

        assertEquals(listOf(1), events.single().doorNumbers)
        assertEquals(1, f.context.rooms[f.playerId])
        assertTrue(f.messages.actionBars.any { it.contains("你已通过") })
    }

    @Test
    fun `locked door is rejected`() {
        val f = Fixture().apply {
            arenaWith(door(1))
            addPlayer()
            context.unlocked = false
        }

        val result = f.service.triggerDoor("w", 1, "op")

        assertFalse(result.success)
        assertEquals("该门尚未解锁", result.message)
        assertNull(f.service.activeSessionInfo("w"))
    }

    @Test
    fun `second trigger while session is active is rejected`() {
        val f = Fixture().apply {
            arenaWith(door(1))
            addPlayer()
        }

        assertTrue(f.service.triggerDoor("w", 1, "op").success)
        val second = f.service.triggerDoor("w", 1, "op")

        assertFalse(second.success)
        assertEquals("该世界已有门会话进行中", second.message)
    }

    @Test
    fun `group doors open together`() {
        val f = Fixture().apply {
            arenaWith(door(1, group = "g"), door(2, group = "g"))
            addPlayer()
        }

        f.service.triggerDoor("w", 1, "op")
        f.openSession()

        assertEquals(2, f.blockOps.opened.size)
    }

    @Test
    fun `fallback passes player already in front projection at close`() {
        val f = Fixture().apply {
            arenaWith(door(1))
            addPlayer()
        }
        val events = mutableListOf<PlayerPassedDoorEvent>()
        f.bus.subscribe(PlayerPassedDoorEvent::class.java) { events.add(it) }

        f.service.triggerDoor("w", 1, "op")
        f.openSession()
        // 从未收到移动事件，但关门时玩家已经在前侧投影内：严格兜底
        f.world.setPosition(f.playerId, Vec3(101.0, 65.0, 15.0))

        f.closeSession()

        assertEquals(1, events.size)
        assertEquals(1, f.context.rooms[f.playerId])
    }

    @Test
    fun `teleported player is not marked as passed`() {
        val f = Fixture().apply {
            arenaWith(door(1))
            addPlayer()
        }
        val events = mutableListOf<PlayerPassedDoorEvent>()
        f.bus.subscribe(PlayerPassedDoorEvent::class.java) { events.add(it) }

        f.service.triggerDoor("w", 1, "op")
        f.openSession()
        f.service.onPlayerTeleport("w", f.playerId)

        val crossed = f.service.onPlayerMove(
            "w",
            f.playerId,
            Vec3(99.0, 65.0, 15.0),
            Vec3(101.0, 65.0, 15.0),
        )
        assertFalse(crossed)

        f.closeSession()

        assertTrue(events.isEmpty())
        assertFalse(f.context.rooms.containsKey(f.playerId))
    }

    @Test
    fun `special door behavior teleports passed player`() {
        val behavior = DoorBehavior(
            type = DoorBehaviorType.ELEVATOR,
            humanTargetX = 50.0,
            humanTargetY = 48.0,
            humanTargetZ = 50.0,
            countdown = 1,
        )
        val f = Fixture().apply {
            arenaWith(door(1, behavior = behavior))
            addPlayer()
            context.team = GameTeam.HUMAN
        }

        f.service.triggerDoor("w", 1, "op")
        f.openSession()
        f.service.onPlayerMove(
            "w",
            f.playerId,
            Vec3(99.0, 65.0, 15.0),
            Vec3(101.0, 65.0, 15.0),
        )
        f.closeSession()

        f.scheduler.tickTimers() // behavior countdown: 1 -> 0
        f.scheduler.tickTimers() // teleport

        assertEquals(1, f.teleporter.teleports.size)
        assertEquals(50.0, f.teleporter.teleports.single().x)
        assertEquals(48.0, f.teleporter.teleports.single().y)
        assertEquals(50.0, f.teleporter.teleports.single().z)
    }

    @Test
    fun `reload cancels active session`() {
        val f = Fixture().apply {
            arenaWith(door(1))
            addPlayer()
        }

        f.service.triggerDoor("w", 1, "op")
        assertNotNull(f.service.activeSessionInfo("w"))

        f.service.reload()

        assertNull(f.service.activeSessionInfo("w"))
    }
}
