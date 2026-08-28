package cn.oneachina.zombierun.v2.infrastructure.config

import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class V2Settings(
    val schema: Int,
    val debug: Boolean,
    val defaultWorld: String,
    val startDelaySeconds: Int,
    val minPlayers: Int,
    val maxDurationSeconds: Int,
    val staminaMax: Double,
    val staminaSprintDrain: Double,
    val staminaRegen: Double,
    val staminaExhaustDelayTicks: Int,
    val economy: cn.oneachina.zombierun.v2.domain.combat.EconomyRules,
)

class V2SettingsLoader(
    private val dataFolder: File,
    private val logger: V2Logger,
) {
    private val file: File
        get() = File(dataFolder, "config/settings.yml")

    fun load(): V2Settings {
        val f = file
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(DEFAULT_SETTINGS)
        }
        val yaml = YamlConfiguration.loadConfiguration(f)
        return V2Settings(
            schema = yaml.getInt("schema", 2),
            debug = yaml.getBoolean("debug", false),
            defaultWorld = yaml.getString("game.default-world", "world") ?: "world",
            startDelaySeconds = yaml.getInt("game.start-delay", 30),
            minPlayers = yaml.getInt("game.min-players", 8),
            maxDurationSeconds = yaml.getInt("game.max-duration", 1800),
            staminaMax = yaml.getDouble("stamina.max", 20.0),
            staminaSprintDrain = yaml.getDouble("stamina.sprint-drain", 0.25),
            staminaRegen = yaml.getDouble("stamina.regen", 0.08),
            staminaExhaustDelayTicks = yaml.getInt("stamina.exhaust-delay-ticks", 40),
            economy = cn.oneachina.zombierun.v2.domain.combat.EconomyRules(
                killZombieCoins = yaml.getInt("economy.kill-zombie-coins", 50),
                killZombieXp = yaml.getInt("economy.kill-zombie-xp", 30),
                killZombieMainCoins = yaml.getInt("economy.kill-zombie-main-coins", 150),
                killZombieMainXp = yaml.getInt("economy.kill-zombie-main-xp", 30),
                infectHumanCoins = yaml.getInt("economy.infect-human-coins", 50),
                infectHumanXp = yaml.getInt("economy.infect-human-xp", 20),
                headshotXp = yaml.getInt("economy.headshot-xp", 5),
                passDoorXp = yaml.getInt("economy.pass-door-xp", 5),
                surviveHumanCoins = yaml.getInt("economy.survive-human-coins", 200),
                rankRewardCoins = yaml.getIntegerList("economy.rank-reward-coins").ifEmpty { listOf(200, 150, 100) },
            ),
        ).also {
            logger.info("settings loaded: schema=${it.schema}, world=${it.defaultWorld}")
        }
    }

    companion object {
        private val DEFAULT_SETTINGS = """
            schema: 2
            debug: false
            game:
              default-world: world
              start-delay: 30
              min-players: 8
              max-duration: 1800
            stamina:
              max: 20.0
              sprint-drain: 0.25
              regen: 0.08
              exhaust-delay-ticks: 40
            economy:
              kill-zombie-coins: 50
              kill-zombie-xp: 30
              kill-zombie-main-coins: 150
              kill-zombie-main-xp: 30
              infect-human-coins: 50
              infect-human-xp: 20
              headshot-xp: 5
              pass-door-xp: 5
              survive-human-coins: 200
              rank-reward-coins: [200, 150, 100]
        """.trimIndent() + "\n"
    }
}
