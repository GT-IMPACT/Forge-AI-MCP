package space.impact.modid.proxy

import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.event.FMLPostInitializationEvent
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent
import cpw.mods.fml.common.event.FMLServerStartedEvent
import cpw.mods.fml.common.event.FMLServerStartingEvent
import cpw.mods.fml.common.event.FMLServerStoppedEvent
import cpw.mods.fml.common.event.FMLServerStoppingEvent

open class CommonProxy {

    // common

    open fun init(event: FMLInitializationEvent) = Unit

    open fun preInit(event: FMLPreInitializationEvent) = Unit

    open fun postInit(event: FMLPostInitializationEvent) = Unit

    // server only

    fun serverAboutToStart(event: FMLServerAboutToStartEvent) = Unit

    fun serverStarting(event: FMLServerStartingEvent) = Unit

    fun serverStarted(event: FMLServerStartedEvent) = Unit

    fun serverStopping(event: FMLServerStoppingEvent) = Unit

    fun serverStopped(event: FMLServerStoppedEvent) = Unit
}
