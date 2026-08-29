package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.combat.CombatHealthService
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.game.GamePhase
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent

/**
 * 机关区监听：黑羊毛秒杀（对齐 v1）。
 *
 * 人类玩家脚下/下方 3 个采样点出现 BLACK_WOOL 即按自定义血量打 10000 点
 * （走死亡链保留感染/统计流程），僵尸与观战不受影响。
 */
class V2HazardListener(
    private val gameFlow: GameFlowService,
    private val healthService: CombatHealthService,
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val world = player.world.name
        if (gameFlow.phaseOf(world) != GamePhase.RUNNING) return
        val team = gameFlow.teamOf(world, player.uniqueId)
        if (team != GameTeam.HUMAN) return
        if (gameFlow.isProtected(player.uniqueId)) return
        // 同格微动（蹲起/转圈）不重复判定
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        val loc = player.location
        val samples = listOf(
            loc.block,
            loc.clone().subtract(0.0, 1.0, 0.0).block,
            loc.clone().subtract(0.0, 0.5, 0.0).block,
        )
        if (samples.any { it.type == Material.BLACK_WOOL }) {
            val dead = healthService.damage(player.uniqueId, HAZARD_DAMAGE, null)
            if (dead) player.health = 0.0
        }
    }

    companion object {
        private const val HAZARD_DAMAGE = 10000.0
    }
}
