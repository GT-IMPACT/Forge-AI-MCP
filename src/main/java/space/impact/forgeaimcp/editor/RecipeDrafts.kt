package space.impact.forgeaimcp.editor

import net.minecraft.client.Minecraft
import java.io.File

/** Shared storage for MCP reads and editor writes; independent of HTTP server lifecycle. */
object RecipeDrafts {
    val store: DraftStore by lazy { DraftStore(File(Minecraft.getMinecraft().mcDataDir, "forgemcp/drafts")) }
}
