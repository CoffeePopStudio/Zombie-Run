package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
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
    fun `protected player damage is cancelled without combat handling`() {
        val victim = player("w")
        val event = mock<EntityDamageByEntityEvent>()
        whenever(event.entity).thenReturn(victim)
        whenever(gameFlow.isProtected(victim.uniqueId)).thenReturn(true)

        listener.onDamage(event)

        verify(event).setCancelled(true)
        assertEquals(0, onCombatCallCount())
    }

    @Test
    fun `lethal zombie attack on human is converted to infection`() {
        val victim = player("w")
        val attacker = mock<Player>()
        whenever(attacker.uniqueId).thenReturn(UUID.randomUUID())
        whenever(attacker.name).thenReturn("Zombie")

        val event = mock<EntityDamageByEntityEvent>()
        whenever(event.entity).thenReturn(victim)
        whenever(event.damager).thenReturn(attacker)
        whenever(event.finalDamage).thenReturn(20.0)
        whenever(victim.health).thenReturn(5.0)

        whenever(gameFlow.isProtected(victim.uniqueId)).thenReturn(false)
        whenever(gameFlow.teamOf("w", victim.uniqueId)).thenReturn(GameTeam.HUMAN)
        whenever(gameFlow.teamOf("w", attacker.uniqueId)).thenReturn(GameTeam.ZOMBIE_MAIN)
        whenever(gameFlow.isMotherReleased("w")).thenReturn(true)
        whenever(gameFlow.onCombat(victim.uniqueId, attacker.uniqueId, "w", true)).thenReturn(true)

        listener.onDamage(event)

        verify(gameFlow).onCombat(victim.uniqueId, attacker.uniqueId, "w", true)
        verify(event).setCancelled(true)
    }

    @Test
    fun `unreleased mother cannot damage human`() {
        val victim = player("w")
        val attacker = mock<Player>()
        whenever(attacker.uniqueId).thenReturn(UUID.randomUUID())

        val event = mock<EntityDamageByEntityEvent>()
        whenever(event.entity).thenReturn(victim)
        whenever(event.damager).thenReturn(attacker)
        whenever(event.finalDamage).thenReturn(20.0)

        whenever(gameFlow.isProtected(victim.uniqueId)).thenReturn(false)
        whenever(gameFlow.teamOf("w", victim.uniqueId)).thenReturn(GameTeam.HUMAN)
        whenever(gameFlow.teamOf("w", attacker.uniqueId)).thenReturn(GameTeam.ZOMBIE_MAIN)
        whenever(gameFlow.isMotherReleased("w")).thenReturn(false)

        listener.onDamage(event)

        verify(event).setCancelled(true)
        assertEquals(0, onCombatCallCount())
    }

    @Test
    fun `human death delegates to game flow`() {
        val victim = player("w")
        val event = mock<PlayerDeathEvent>()
        whenever(event.entity).thenReturn(victim)
        whenever(gameFlow.teamOf("w", victim.uniqueId)).thenReturn(GameTeam.HUMAN)

        listener.onDeath(event)

        verify(gameFlow).onHumanDied("w", victim.uniqueId)
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

    private fun onCombatCallCount(): Int =
        Mockito.mockingDetails(gameFlow).invocations.count { it.method.name == "onCombat" }
}
