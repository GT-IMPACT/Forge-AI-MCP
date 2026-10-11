package space.impact.forgeaimcp.adapter

import com.google.gson.JsonObject

/** Implementations may use optional mods, but must not link their classes before detection. */
interface RecipeAdapter {
    val id: String
    fun detect(environment: ModEnvironment): AdapterDetection
    fun call(operation: String, arguments: JsonObject, environment: ModEnvironment, codec: RecipeValueCodec): JsonObject
    /** A verified fork can own registration of recipes read from the Minecraft registries. */
    fun craftingRegistration(environment: ModEnvironment): JsonObject? = null
}

data class ModIdentity(val id: String, val version: String, val artifact: String?)

interface ModEnvironment {
    fun mod(id: String): ModIdentity?
    fun findClass(name: String): Class<*>?
}

data class AdapterDetection(val available: Boolean, val reason: String, val evidence: List<String> = emptyList())

/** Separates native objects from the JSON contract so providers can be tested without a game. */
interface RecipeValueCodec {
    fun item(value: Any): JsonObject
    fun fluid(value: Any): JsonObject
    fun validateItem(value: JsonObject): String?
    fun validateFluid(value: JsonObject): String?
}
