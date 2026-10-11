package space.impact.forgeaimcp.config

import net.minecraftforge.common.config.Configuration
import java.io.File

object McpConfig {

    var autoStart: Boolean = false
        private set

    var port: Int = 25576
        private set
    var webPort: Int = 25577
        private set

    fun load(file: File) {
        val config = Configuration(file)
        config.load()
        autoStart = config.getBoolean(
            "autoStart",
            "mcp",
            false,
            "Start MCP automatically. When false, MCP must be started manually using a command.",
        )
        port = config.getInt("port", "mcp", 25576, 1024, 65535, "Local MCP bridge port (127.0.0.1 only).")
        webPort = config.getInt("webPort", "mcp", 25577, 1024, 65535, "Local visual editor port. Starts only with /forgemcp ui.")
        if (config.hasChanged()) {
            config.save()
        }
    }
}
