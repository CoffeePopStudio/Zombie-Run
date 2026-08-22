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
        """.trimIndent() + "\n"
    }
}
