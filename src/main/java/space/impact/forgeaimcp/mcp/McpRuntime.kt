package space.impact.forgeaimcp.mcp

import com.google.gson.JsonObject
import cpw.mods.fml.common.Loader
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.network.FMLNetworkEvent
import net.minecraft.client.Minecraft
import space.impact.forgeaimcp.config.McpConfig
import space.impact.forgeaimcp.editor.EditorRuntime
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** Client lifecycle and game-thread dispatch. Connecting the stdio process never starts it. */
object McpRuntime {
    @Volatile private var bridge: LocalBridge? = null

    fun isRunning() = bridge?.isRunning() == true
    fun address() = "127.0.0.1:${bridge?.localPort() ?: McpConfig.port}"

    @Synchronized
    fun start(): Boolean {
        if (isRunning()) return false
        McpInstallation.install(Minecraft.getMinecraft().mcDataDir, McpConfig.port)
        check(Loader.isModLoaded("NotEnoughItems")) { "NotEnoughItems is required." }
        val transport = LocalBridge(McpConfig.port) { tool, args ->
            val future = Minecraft.getMinecraft().func_152343_a(Callable { ClientTools.call(tool, args) })
            try {
                future.get(10, TimeUnit.SECONDS) as JsonObject
            } finally {
                if (!future.isDone) future.cancel(false)
            }
        }
        transport.start()
        bridge = transport
        return true
    }

    @Synchronized
    fun stop(): Boolean {
        val editorRunning = EditorRuntime.address() != null
        EditorRuntime.stop()
        val transport = bridge ?: return editorRunning
        bridge = null
        return transport.stop()
    }

    @SubscribeEvent
    fun disconnected(event: FMLNetworkEvent.ClientDisconnectionFromServerEvent) {
        stop()
    }
}
