@file:Suppress("unused")

package space.impact.forgeaimcp.proxy

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.event.FMLPostInitializationEvent
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import net.minecraft.client.Minecraft
import net.minecraftforge.client.ClientCommandHandler
import space.impact.forgeaimcp.command.ForgeMcpCommand
import space.impact.forgeaimcp.config.McpConfig
import space.impact.forgeaimcp.mcp.McpRuntime
import space.impact.forgeaimcp.mcp.McpInstallation
import space.impact.forgeaimcp.editor.ItemIcons

class ClientProxy : CommonProxy() {

    override fun preInit(event: FMLPreInitializationEvent) {
        super.preInit(event)
        try {
            McpInstallation.install(Minecraft.getMinecraft().mcDataDir, McpConfig.port)
        } catch (e: Exception) {
            cpw.mods.fml.common.FMLLog.getLogger().error("ForgeMCP installation failed; /forgemcp start will retry", e)
        }
    }

    override fun init(event: FMLInitializationEvent) {
        super.init(event)
        ClientCommandHandler.instance.registerCommand(ForgeMcpCommand())
        FMLCommonHandler.instance().bus().register(McpRuntime)
        FMLCommonHandler.instance().bus().register(ItemIcons)
    }

    override fun postInit(event: FMLPostInitializationEvent) {
        super.postInit(event)
        if (McpConfig.autoStart) {
            try {
                McpRuntime.start()
            } catch (e: Exception) {
                cpw.mods.fml.common.FMLLog.getLogger().error("ForgeMCP auto-start failed", e)
            }
        }
    }
}
