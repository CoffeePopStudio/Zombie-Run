package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.game.GameFlowService
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class V2GameListenerTest {

    private val gameFlow = mock<GameFlowService>()
    private val listener = V2GameListener(gameFlow) { 20.0 }
    private val id = UUID.randomUUID()

    private fun player(worldName: String): Player {
        val world = mock<World>()
        whenever(world.name).thenReturn(worldName)
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(id)
        whenever(player.world).thenReturn(world)
        return player
    }

    @Test
    fun `join delegates to game flow with world and id`() {
        val p = player("w")
        val event = mock<PlayerJoinEvent>()
        whenever(event.player).thenReturn(p)

        listener.onJoin(event)

        verify(gameFlow).onPlayerJoin("w", id)
    }

    @Test
    fun `world change leaves old game before joining new one`() {
        val p = player("old")
        val join = mock<PlayerJoinEvent>()
        whenever(join.player).thenReturn(p)
        listener.onJoin(join)

        // 玩家切到新世界
        val newWorld = mock<World>()
        whenever(newWorld.name).thenReturn("new")
        whenever(p.world).thenReturn(newWorld)
        val change = mock<PlayerChangedWorldEvent>()
        whenever(change.player).thenReturn(p)

        listener.onWorldChange(change)

        val inOrder = Mockito.inOrder(gameFlow)
        inOrder.verify(gameFlow).onPlayerLeaveWorld("old", id)
        inOrder.verify(gameFlow).onPlayerJoin("new", id)
    }

    @Test
    fun `respawn delegates with respawn world`() {
        val p = player("w")
        val respawnWorld = mock<World>()
        whenever(respawnWorld.name).thenReturn("w")
        val loc = Location(respawnWorld, 0.0, 64.0, 0.0)
        val event = mock<PlayerRespawnEvent>()
        whenever(event.respawnLocation).thenReturn(loc)
        whenever(event.player).thenReturn(p)

        listener.onRespawn(event)

        verify(gameFlow).onPlayerRespawn("w", id)
    }

}
