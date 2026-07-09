package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import cn.oneachina.zombieRun.model.QuestType
import org.bukkit.entity.Player

class ProgressionListener(private val plugin: ZombieRun) {

    fun onKillZombie(killer: Player, victim: Player, isAlpha: Boolean) {
        val pm = plugin.progressionManager
        val qm = plugin.questManager

        pm.addTotalKill(killer.uniqueId)
        val xp = if (isAlpha) 30 else 10
        pm.addXp(killer, xp, if (isAlpha) "击杀母体" else "击杀僵尸")

        qm.incrementProgress(killer, QuestType.KILL_ZOMBIE, 1)
        if (isAlpha) {
            qm.incrementProgress(killer, QuestType.KILL_ALPHA, 1)
        }
    }

    fun onInfectHuman(attacker: Player, victim: Player) {
        val pm = plugin.progressionManager
        val qm = plugin.questManager

        pm.addTotalInfection(attacker.uniqueId)
        pm.addXp(attacker, 20, "感染人类")
        qm.incrementProgress(attacker, QuestType.INFECT_HUMAN, 1)
    }

    fun onPassDoor(player: Player) {
        plugin.questManager.incrementProgress(player, QuestType.PASS_DOOR, 1)
        plugin.progressionManager.addXp(player, 5, "通过门")
    }

    fun onHumanWin() {
        val pm = plugin.progressionManager
        val qm = plugin.questManager
        org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
            val team = plugin.gameManager.getPlayerTeam(player)
            if (team == GameManager.Team.HUMAN) {
                pm.addHumanWin(player.uniqueId)
                pm.addXp(player, 100, "人类获胜")
                qm.incrementProgress(player, QuestType.HUMAN_WIN, 1)
            }
        }
    }

    fun onGameEnd() {
        val pm = plugin.progressionManager
        val qm = plugin.questManager
        org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
            pm.addGamePlayed(player.uniqueId)
            pm.addXp(player, 50, "参与对局")
            qm.incrementProgress(player, QuestType.PLAY_GAME, 1)
        }
    }

    fun onDealDamage(player: Player, damage: Double) {
        val rounded = damage.toInt()
        if (rounded > 0) {
            plugin.questManager.incrementProgress(player, QuestType.DEAL_DAMAGE, rounded)
        }
    }
}
