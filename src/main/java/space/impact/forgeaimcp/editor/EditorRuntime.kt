package space.impact.forgeaimcp.editor

import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import space.impact.forgeaimcp.config.McpConfig
import space.impact.forgeaimcp.mcp.ClientTools
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

object EditorRuntime {
    private var server: EditorServer? = null
    fun start(): String {
        server?.let { return it.address() }
        check(Minecraft.getMinecraft().theWorld != null) { "Войдите в мир перед запуском редактора" }
        ItemIcons.configure(File(Minecraft.getMinecraft().mcDataDir, "forgemcp"))
        val listener = EditorServer(McpConfig.webPort, RecipeDrafts.store,
            { tool, args -> onGameThread { ClientTools.call(tool, args) } },
            { args -> ItemIcons.png(args) },
            { start -> onGameThread { ItemIcons.export(start) } })
        try { listener.start() } catch (e: Exception) { ItemIcons.stop(); throw e }
        server = listener
        return listener.address()
    }
    fun stop() { server?.stop(); server = null; ItemIcons.stop() }
    fun address(): String? = server?.address()
    private fun onGameThread(query: () -> JsonObject): JsonObject {
        val future = Minecraft.getMinecraft().func_152343_a(Callable { query() })
        try { return future.get(10, TimeUnit.SECONDS) as JsonObject }
        finally { if (!future.isDone) future.cancel(false) }
    }
}
