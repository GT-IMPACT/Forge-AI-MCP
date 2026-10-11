package space.impact.forgeaimcp.adapter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import cpw.mods.fml.common.Loader
import space.impact.forgeaimcp.adapter.gtimpact.GtImpactAdapter
import space.impact.forgeaimcp.adapter.minecraft.VanillaAdapter
import space.impact.forgeaimcp.mcp.GameData

/** Addon mods register providers during init; each fork gets a distinct adapter ID. */
class AdapterRegistry(private val environment: ModEnvironment, private val codec: RecipeValueCodec) {
    private val providers = linkedMapOf<String, RecipeAdapter>()

    fun register(adapter: RecipeAdapter) {
        require(adapter.id.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "Invalid adapter ID: ${adapter.id}" }
        require(!providers.containsKey(adapter.id)) { "Duplicate adapter ID: ${adapter.id}" }
        providers[adapter.id] = adapter
    }

    fun list(): JsonObject = JsonObject().apply {
        add("craftingRegistration", craftingRegistration())
        add("results", JsonArray().apply {
            providers.values.forEach { provider ->
                val detection = detection(provider)
                add(JsonObject().apply {
                    addProperty("adapter", provider.id)
                    addProperty("available", detection.available)
                    addProperty("reason", detection.reason)
                    add("evidence", JsonArray().apply { detection.evidence.forEach { add(JsonPrimitive(it)) } })
                })
            }
        })
    }

    fun call(operation: String, arguments: JsonObject): JsonObject {
        val id = arguments.get("adapter")?.asString ?: error("Specify an adapter ID from list_adapters")
        val provider = providers[id] ?: error("Unknown adapter: $id. Call list_adapters.")
        val detection = detection(provider)
        check(detection.available || operation == "get_recipe_authoring_guide") { "$id unavailable: ${detection.reason}" }
        return provider.call(operation, arguments, environment, codec).apply {
            if (id == "minecraft") add("registration", craftingRegistration())
            if (operation == "get_recipe_authoring_guide") {
                addProperty("runtimeAvailable", detection.available)
                addProperty("runtimeDetection", detection.reason)
            }
        }
    }

    private fun craftingRegistration(): JsonObject {
        val policies = providers.values.filter { detection(it).available }.mapNotNull { it.craftingRegistration(environment) }
        if (policies.size == 1) return policies.single()
        return JsonObject().apply {
            val unresolved = policies.size > 1 || environment.mod("gregtech") != null
            addProperty("adapter", if (unresolved) "unresolved-gregtech" else "minecraft")
            addProperty("requiresVerifiedProfile", unresolved)
            addProperty("instruction", if (unresolved) "GregTech is installed, but a unique verified crafting registration API was not detected. Inspect the fork's adapter/API; do not bypass it with GameRegistry or guess GT methods."
                else "Use the Minecraft/Forge GameRegistry authoring guide.")
        }
    }

    private fun detection(provider: RecipeAdapter) = try {
        provider.detect(environment)
    } catch (e: Exception) {
        AdapterDetection(false, "Detection failed: ${e.message}")
    } catch (e: LinkageError) {
        AdapterDetection(false, "Incompatible runtime: ${e.message}")
    }
}

object RuntimeModEnvironment : ModEnvironment {
    override fun mod(id: String): ModIdentity? = Loader.instance().indexedModList[id]?.let {
        ModIdentity(it.modId, it.version, it.source?.name)
    }
    override fun findClass(name: String): Class<*>? = try {
        Class.forName(name, false, Loader.instance().modClassLoader)
    } catch (e: ClassNotFoundException) { null }
}

object RecipeAdapters {
    val registry = AdapterRegistry(RuntimeModEnvironment, GameData).apply { register(GtImpactAdapter()); register(VanillaAdapter()) }
    @JvmStatic fun register(adapter: RecipeAdapter) = registry.register(adapter)
}
