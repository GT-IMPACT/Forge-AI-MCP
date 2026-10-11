package space.impact.forgeaimcp.adapter

import com.google.gson.*
import java.security.MessageDigest

object JsonSupport {
    fun text(obj: JsonObject, key: String, default: String? = null): String {
        val value = obj.get(key) ?: return default ?: error("Missing argument: $key")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string" }
        return value.asString
    }
    fun int(obj: JsonObject, key: String, default: Int = 0): Int = obj.get(key)?.asBigDecimal?.intValueExact() ?: default
    fun bool(obj: JsonObject, key: String, default: Boolean = false): Boolean = obj.get(key)?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "$key must be boolean" }; it.asBoolean
    } ?: default
    fun array(obj: JsonObject, key: String): JsonArray = obj.getAsJsonArray(key) ?: JsonArray()
    fun strings(values: Iterable<String>) = JsonArray().apply { values.forEach { add(JsonPrimitive(it)) } }
    fun page(values: List<JsonObject>, args: JsonObject): JsonObject {
        val offset = int(args, "offset")
        val limit = int(args, "limit", 20)
        require(offset >= 0 && limit in 1..100) { "Invalid pagination" }
        return JsonObject().apply {
            add("results", JsonArray().apply { values.drop(offset).take(limit).forEach { add(it) } })
            addProperty("total", values.size); addProperty("offset", offset); addProperty("limit", limit)
            addProperty("hasMore", offset.toLong() + limit < values.size)
        }
    }
    fun canonical(value: JsonElement, omitDisplay: Boolean = false): String = when {
        value.isJsonObject -> value.asJsonObject.entrySet().filter {
            !omitDisplay || it.key !in setOf("name", "oreDict", "provenance", "neiDescription", "warnings", "complete", "id")
        }.sortedBy { it.key }.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value, omitDisplay) }
        value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]") { canonical(it, omitDisplay) }
        else -> value.toString()
    }
    fun hash(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value, true).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
