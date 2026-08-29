package cn.oneachina.zombierun.v2.application.task

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.GameEndedEvent
import cn.oneachina.zombierun.v2.application.event.GameStartedEvent
import cn.oneachina.zombierun.v2.application.event.InfectHumanEvent
import cn.oneachina.zombierun.v2.application.event.PlayerDamageDealtEvent
import cn.oneachina.zombierun.v2.application.event.PlayerPassedDoorEvent
import cn.oneachina.zombierun.v2.application.event.ZombieKilledEvent
import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.domain.player.PlayerProfile
import cn.oneachina.zombierun.v2.domain.task.TaskProgress
import cn.oneachina.zombierun.v2.infrastructure.config.TaskYamlRepository
import cn.oneachina.zombierun.v2.ports.PlayerDataPort
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.PlayerTaskPort
import cn.oneachina.zombierun.v2.support.V2Logger
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskServiceTest {

    private class FakeTaskStorage : PlayerTaskPort {
        val store = ConcurrentHashMap<UUID, MutableMap<String, TaskProgress>>()
        override fun load(playerId: UUID): Map<String, TaskProgress> =
            store[playerId]?.toMap() ?: emptyMap()
        override fun save(playerId: UUID, progress: Map<String, TaskProgress>) {
            store[playerId] = progress.toMutableMap()
        }
        override fun close() = Unit
    }

    private class FakeDataStorage : PlayerDataPort {
        val store = ConcurrentHashMap<UUID, PlayerProfile>()
        override fun load(playerId: UUID): PlayerProfile? = store[playerId]
        override fun save(profile: PlayerProfile) { store[profile.playerId] = profile }
        override fun close() = Unit
    }

    private class FakeMessages : PlayerMessagePort {
        override fun actionBar(playerId: UUID, message: String) = Unit
        override fun chat(playerId: UUID, message: String) = Unit
        override fun title(playerId: UUID, title: String, subtitle: String) = Unit
        override fun soundBell(worldName: String) = Unit
    }

    private class Fixture(
        val service: TaskService,
        val bus: ApplicationEventBus,
        val storage: FakeTaskStorage,
        val playerData: PlayerDataService,
    )

    private fun fixture(dir: File): Fixture = fixture(
        dir,
        """
        tasks:
          daily_doors:
            description: 通过5扇门
            type: DOOR_PASSES
            target: 5
            reward-coins: 50
            reward-xp: 10
            period: DAILY
          weekly_kills:
            description: 击杀3只僵尸
            type: ZOMBIE_KILLS
            target: 3
            reward-coins: 100
            reward-xp: 30
            period: WEEKLY
        """.trimIndent(),
    )

    private fun fixture(dir: File, tasksYamlContent: String): Fixture {
        val tasksYaml = File(dir, "config/tasks.yml")
        tasksYaml.parentFile.mkdirs()
        tasksYaml.writeText(tasksYamlContent)
        val bus = ApplicationEventBus()
        val taskStorage = FakeTaskStorage()
        val dataStorage = FakeDataStorage()
        val messages = FakeMessages()
        val playerData = PlayerDataService(dataStorage, messages, V2Logger(Logger.getLogger("test")), bus)
        val service = TaskService(
            storage = taskStorage,
            taskRepository = TaskYamlRepository(dir, V2Logger(Logger.getLogger("test"))),
            playerData = playerData,
            messages = messages,
            logger = V2Logger(Logger.getLogger("test")),
            eventBus = bus,
        )
        return Fixture(service, bus, taskStorage, playerData)
    }

    @Test
    fun `door and kill events increment task progress`() {
        val f = fixture(createTempDirectory("zr2-task-test").toFile())
        val id = UUID.randomUUID()

        repeat(5) {
            f.bus.publish(PlayerPassedDoorEvent("w", id, listOf(1)))
        }
        repeat(3) {
            f.bus.publish(ZombieKilledEvent("w", id, UUID.randomUUID()))
        }

        val tasks = f.service.progressOf(id).associate { it.first.id to it.second }
        assertEquals(5, tasks["daily_doors"]?.progress)
        assertEquals(3, tasks["weekly_kills"]?.progress)
        assertFalse(tasks["daily_doors"]!!.claimed)
        assertTrue(f.storage.store[id]?.get("daily_doors")?.progress == 5)
    }

    @Test
    fun `claim awards coins and xp once`() {
        val f = fixture(createTempDirectory("zr2-task-test").toFile())
        val id = UUID.randomUUID()

        repeat(5) {
            f.bus.publish(PlayerPassedDoorEvent("w", id, listOf(1)))
        }
        assertTrue(f.service.claim(id, "daily_doors").contains("已领取"))
        assertEquals("任务尚未完成", f.service.claim(id, "weekly_kills"))
        assertEquals("该任务奖励已领取", f.service.claim(id, "daily_doors"))

        val profile = f.playerData.profileOf(id)
        assertEquals(50, profile.coins)
        assertEquals(10, profile.xp)
        assertTrue(f.service.progressOf(id).first { it.first.id == "daily_doors" }.second.claimed)
    }

    @Test
    fun `expired daily task resets on view and blocks stale claim`() {
        val f = fixture(createTempDirectory("zr2-task-expire").toFile())
        val id = UUID.randomUUID()
        // 上一周期已完成且已领取的进度
        f.storage.store[id] = mutableMapOf(
            "daily_doors" to TaskProgress(
                taskId = "daily_doors",
                progress = 5,
                claimed = true,
                lastReset = "2000-01-01",
            ),
        )

        // 查看进度时按当前自然日重置
        val tasks = f.service.progressOf(id).associate { it.first.id to it.second }
        assertEquals(0, tasks["daily_doors"]?.progress)
        assertFalse(tasks["daily_doors"]!!.claimed)

        // 领取走的是当前周期判定，不能凭旧周期的 claimed 直接领取
        assertEquals("任务尚未完成", f.service.claim(id, "daily_doors"))
    }

    @Test
    fun `expanded task types increment from events`() {
        val f = fixture(
            createTempDirectory("zr2-task-expanded").toFile(),
            """
            tasks:
              daily_alpha:
                description: 击杀母体
                type: KILL_ALPHA
                target: 1
                reward-coins: 120
                reward-xp: 30
                period: DAILY
              daily_infect:
                description: 感染人类
                type: INFECT_HUMAN
                target: 2
                reward-coins: 80
                reward-xp: 20
                period: DAILY
              weekly_play:
                description: 参与对局
                type: PLAY_GAME
                target: 3
                reward-coins: 200
                reward-xp: 50
                period: WEEKLY
              weekly_win:
                description: 人类胜利
                type: HUMAN_WIN
                target: 2
                reward-coins: 250
                reward-xp: 60
                period: WEEKLY
              daily_damage:
                description: 造成伤害
                type: DEAL_DAMAGE
                target: 100
                reward-coins: 100
                reward-xp: 30
                period: DAILY
            """.trimIndent(),
        )
        val id = UUID.randomUUID()
        val victim = UUID.randomUUID()

        f.bus.publish(ZombieKilledEvent("w", id, victim, "ZOMBIE_MAIN"))
        f.bus.publish(InfectHumanEvent(id, victim))
        f.bus.publish(GameStartedEvent("w", mapOf(id to "HUMAN")))
        f.bus.publish(GameEndedEvent("w", "HUMAN", setOf(id)))
        repeat(2) {
            f.bus.publish(PlayerDamageDealtEvent(id, victim, 50.0))
        }

        val tasks = f.service.progressOf(id).associate { it.first.id to it.second }
        assertEquals(1, tasks["daily_alpha"]?.progress)
        assertEquals(1, tasks["daily_infect"]?.progress)
        assertEquals(1, tasks["weekly_play"]?.progress)
        assertEquals(1, tasks["weekly_win"]?.progress)
        assertEquals(100, tasks["daily_damage"]?.progress)
    }
}