package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.door.DoorApplicationService
import cn.oneachina.zombierun.v2.application.door.TriggerResult
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonMode
import cn.oneachina.zombierun.v2.domain.door.Vec3
import cn.oneachina.zombierun.v2.infrastructure.config.ArenaYamlRepository
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.Test

class V2DoorListenerTest {

    private val doorService = mock<DoorApplicationService>()
    private val arenaRepository = mock<ArenaYamlRepository>()
    private val gameFlow = mock<GameFlowService>()
    private val listener = V2DoorListener(doorService, arenaRepository, gameFlow)
    private val id = UUID.randomUUID()

    private fun player(worldName: String): Player {
        val world = mock<World>()
        whenever(world.name).thenReturn(worldName)
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(id)
        whenever(player.world).thenReturn(world)
        whenever(player.name).thenReturn("Steve")
        return player
    }

    @Test
    fun `move event translates locations into vec and world`() {
        val p = player("w")
        val from = Location(p.world, 99.0, 65.0, 15.0)
        val to = Location(p.world, 101.0, 65.0, 15.0)
        val event = mock<PlayerMoveEvent>()
        whenever(event.player).thenReturn(p)
        whenever(event.from).thenReturn(from)
        whenever(event.to).thenReturn(to)

        listener.onPlayerMove(event)

        verify(doorService).onPlayerMove("w", id, Vec3(99.0, 65.0, 15.0), Vec3(101.0, 65.0, 15.0))
    }

    @Test
    fun `teleport marks player in origin world`() {
        val p = player("w")
        val from = Location(p.world, 0.0, 65.0, 0.0)
        val otherWorld = mock<World>()
        whenever(otherWorld.name).thenReturn("other")
        val to = Location(otherWorld, 10.0, 65.0, 0.0)
        val event = mock<PlayerTeleportEvent>()
        whenever(event.player).thenReturn(p)
        whenever(event.from).thenReturn(from)
        whenever(event.to).thenReturn(to)

        listener.onPlayerTeleport(event)

        verify(doorService).onPlayerTeleport("w", id)
    }

    @Test
    fun `normal button click triggers its door and cancels interaction`() {
        val p = player("w")
        val block = mock<Block>()
        whenever(block.type).thenReturn(Material.REDSTONE_LAMP)
        whenever(block.x).thenReturn(12)
        whenever(block.y).thenReturn(64)
        whenever(block.z).thenReturn(12)

        val arena = ArenaDefinition(
            name = "a",
            world = "w",
            buttons = listOf(ButtonDefinition("b1", "w", 12, 64, 12, ButtonMode.NORMAL, listOf(3))),
        )
        whenever(arenaRepository.byWorld("w")).thenReturn(listOf(arena))
        whenever(doorService.triggerDoor("w", 3, "Steve")).thenReturn(TriggerResult(true, "ok"))

        val event = mock<PlayerInteractEvent>()
        whenever(event.action).thenReturn(Action.RIGHT_CLICK_BLOCK)
        whenever(event.clickedBlock).thenReturn(block)
        whenever(event.player).thenReturn(p)

        listener.onPlayerInteract(event)

        verify(doorService).triggerDoor("w", 3, "Steve")
        verify(event).setCancelled(true)
    }

    @Test
    fun `escape button click starts helicopter extraction`() {
        val p = player("w")
        val block = mock<Block>()
        whenever(block.type).thenReturn(Material.LEVER)
        whenever(block.x).thenReturn(5)
        whenever(block.y).thenReturn(64)
        whenever(block.z).thenReturn(5)

        val arena = ArenaDefinition(
            name = "a",
            world = "w",
            buttons = listOf(ButtonDefinition("esc", "w", 5, 64, 5, ButtonMode.ESCAPE, emptyList())),
        )
        whenever(arenaRepository.byWorld("w")).thenReturn(listOf(arena))
        whenever(gameFlow.triggerEscape("w", "Steve")).thenReturn(true)

        val event = mock<PlayerInteractEvent>()
        whenever(event.action).thenReturn(Action.RIGHT_CLICK_BLOCK)
        whenever(event.clickedBlock).thenReturn(block)
        whenever(event.player).thenReturn(p)

        listener.onPlayerInteract(event)

        verify(gameFlow).triggerEscape("w", "Steve")
        verify(event).setCancelled(true)
    }

    @Test
    fun `interaction with non button block is ignored`() {
        val p = player("w")
        val block = mock<Block>()
        whenever(block.type).thenReturn(Material.REDSTONE_LAMP)
        whenever(block.x).thenReturn(99)
        whenever(block.y).thenReturn(64)
        whenever(block.z).thenReturn(99)
        whenever(arenaRepository.byWorld("w")).thenReturn(emptyList())

        val event = mock<PlayerInteractEvent>()
        whenever(event.action).thenReturn(Action.RIGHT_CLICK_BLOCK)
        whenever(event.clickedBlock).thenReturn(block)
        whenever(event.player).thenReturn(p)

        listener.onPlayerInteract(event)

        verify(event, never()).setCancelled(true)
        verify(doorService, never()).triggerDoor(any<String>(), any<Int>(), any<String>())
    }
}
