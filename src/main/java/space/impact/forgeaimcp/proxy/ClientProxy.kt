@file:Suppress("unused")

package space.impact.forgeaimcp.proxy

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.event.FMLPostInitializationEvent
import net.minecraftforge.client.ClientCommandHandler
import space.impact.forgeaimcp.command.ForgeMcpCommand
import space.impact.forgeaimcp.config.McpConfig
import space.impact.forgeaimcp.mcp.McpRuntime

class ClientProxy : CommonProxy() {

    override fun init(event: FMLInitializationEvent) {
        super.init(event)
        ClientCommandHandler.instance.registerCommand(ForgeMcpCommand())
        FMLCommonHandler.instance().bus().register(McpRuntime)
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
