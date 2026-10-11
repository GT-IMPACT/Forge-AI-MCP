package space.impact.forgeaimcp.adapter.minecraft

import com.google.gson.*
import space.impact.forgeaimcp.adapter.*
import java.util.Locale

data class CraftingIngredient(val alternatives: List<Any>, val oreDictionary: String? = null)
data class VanillaRecipe(
    val kind: String, val implementation: String, val inputs: List<CraftingIngredient?>,
    val output: Any?, val width: Int = 0, val height: Int = 0, val mirrored: Boolean = true,
    val copiesInputNbt: Boolean = false, val experience: Float = 0f, val warnings: List<String> = emptyList(), val registryIndex: Int = -1,
)
interface VanillaRecipeSource { fun recipes(): List<VanillaRecipe> }

/** Reads the shared vanilla/Forge registries, including recipes registered by other mods. */
class VanillaAdapter(private val source: VanillaRecipeSource = MinecraftRecipeSource) : RecipeAdapter {
    override val id = "minecraft"
    private val discovered = object : LinkedHashMap<String, VanillaRecipe>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VanillaRecipe>?) = size > 4096
    }
    override fun detect(environment: ModEnvironment) = AdapterDetection(
        environment.findClass("net.minecraft.item.crafting.CraftingManager") != null,
        "Minecraft 1.7.10 vanilla and Forge crafting/smelting registries",
    )
    override fun call(operation: String, arguments: JsonObject, environment: ModEnvironment, codec: RecipeValueCodec): JsonObject {
        if (operation == "get_recipe_authoring_guide") return JsonObject().apply {
            addProperty("adapter", id)
            addProperty("skill", javaClass.getResourceAsStream("/assets/forgeaimcp/mcp/skills/minecraft-recipes/SKILL.md")
                ?.use { it.reader(Charsets.UTF_8).readText() } ?: error("Missing Minecraft recipe skill"))
        }
        val raw = source.recipes()
        if (operation == "get_machine_recipe") {
            val recipeId = JsonSupport.text(arguments, "recipeId")
            val recipe = discovered[recipeId] ?: error("Run search_machine_recipes first; recipe ID expired or unknown")
            check(raw.any { it == recipe }) { "Recipe changed or removed; search again" }
            return encode(recipe, codec).also { check(it.get("id").asString == recipeId) { "Recipe changed; search again" } }
        }
        val maps = listOf("crafting-shaped", "crafting-shapeless", "furnace", "crafting-custom")
        if (operation == "list_recipe_maps") {
            val names = listOf("Верстак — shaped", "Верстак — shapeless", "Печка", "Нестандартные IRecipe (частичные данные)")
            val kinds = listOf("shaped", "shapeless", "furnace", "custom-crafting")
            return JsonSupport.page(maps.mapIndexed { i, map -> JsonObject().apply {
                addProperty("id", map); addProperty("adapter", id); addProperty("name", names[i]); addProperty("kind", kinds[i])
                addProperty("recipeCount", raw.count { map(it.kind) == map }); addProperty("authoringSupported", i != 3)
            } }.filter { it.toString().lowercase(Locale.ROOT).contains(JsonSupport.text(arguments, "query", "").lowercase(Locale.ROOT)) }, arguments)
        }
        require(operation == "search_machine_recipes") { "Unknown Minecraft operation: $operation" }
        val requestedMap = JsonSupport.text(arguments, "map", "")
        require(requestedMap.isEmpty() || requestedMap in maps) { "Unknown Minecraft map" }
        val direction = JsonSupport.text(arguments, "direction", "any")
        require(direction in listOf("input", "output", "any")) { "Invalid direction" }
        val requestedItem = JsonSupport.text(arguments, "item", "")
        if (arguments.has("metadata")) require(JsonSupport.int(arguments, "metadata") in 0..32767) { "Invalid metadata" }
        if (arguments.has("nbt")) JsonSupport.text(arguments, "nbt")
        val query = JsonSupport.text(arguments, "query", "").lowercase(Locale.ROOT)
        val warnings = mutableListOf<String>()
        val matches = mutableListOf<JsonObject>()
        val matchedRaw = mutableMapOf<String, VanillaRecipe>()
        for (recipe in raw) {
            if (requestedMap.isNotEmpty() && map(recipe.kind) != requestedMap) continue
            try {
                val value = encode(recipe, codec)
                val candidates = mutableListOf<JsonObject>()
                if (direction != "output") value.getAsJsonArray("itemInputs").forEach { slot ->
                    val obj = slot.asJsonObject
                    if (obj.has("item")) candidates.add(obj)
                    obj.getAsJsonArray("alternatives")?.forEach { candidates.add(it.asJsonObject) }
                }
                if (direction != "input") value.getAsJsonArray("itemOutputs").forEach { candidates.add(it.asJsonObject) }
                if (JsonSupport.text(arguments, "fluid", "").isNotEmpty()) continue
                if (requestedItem.isNotEmpty() && candidates.none { item ->
                    item.get("item").asString == requestedItem && (!arguments.has("metadata") ||
                        JsonSupport.int(arguments, "metadata") == 32767 || JsonSupport.int(item, "metadata") == 32767 ||
                        JsonSupport.int(item, "metadata") == JsonSupport.int(arguments, "metadata")) &&
                        (!arguments.has("nbt") || item.get("nbt")?.takeUnless { it.isJsonNull }?.asString == JsonSupport.text(arguments, "nbt"))
                }) continue
                if (query.isNotEmpty() && !value.toString().lowercase(Locale.ROOT).contains(query)) continue
                matches.add(value)
                matchedRaw[value.get("id").asString] = recipe
                value.getAsJsonArray("warnings").forEach { warnings.add("${recipe.implementation}: ${it.asString}") }
            } catch (e: Exception) { warnings.add("${recipe.implementation}: ${e.message}") }
        }
        return JsonSupport.page(matches, arguments).apply {
            getAsJsonArray("results").forEach { result ->
                val recipeId = result.asJsonObject.get("id").asString
                discovered[recipeId] = matchedRaw.getValue(recipeId)
            }
            add("warnings", JsonSupport.strings(warnings.distinct())); addProperty("complete", warnings.isEmpty())
            addProperty("scope", "CraftingManager and FurnaceRecipes, all registering mods. Custom IRecipe logic and container returns require source inspection; no matching simulation.")
        }
    }
    private fun map(kind: String) = when (kind) { "furnace" -> "furnace"; "custom-crafting" -> "crafting-custom"; else -> "crafting-$kind" }
    private fun encode(recipe: VanillaRecipe, codec: RecipeValueCodec): JsonObject = JsonObject().apply {
        addProperty("adapter", id); addProperty("map", map(recipe.kind)); addProperty("kind", recipe.kind)
        addProperty("implementation", recipe.implementation)
        addProperty("registryIndex", recipe.registryIndex)
        val inputs = JsonArray(); val cells = JsonArray()
        for (slot in recipe.inputs) {
            if (slot == null) { cells.add(JsonNull.INSTANCE); continue }
            cells.add(JsonPrimitive(inputs.size()))
            val alternatives = slot.alternatives.map { codec.item(it).apply {
                addProperty("declaredCount", JsonSupport.int(this, "count", 1)); addProperty("count", 1)
            } }
            val value = alternatives.firstOrNull()?.let { JsonParser().parse(it.toString()).asJsonObject } ?: JsonObject().apply { addProperty("count", 1) }
            slot.oreDictionary?.let { value.addProperty("oreDictionary", it) }
            if (slot.oreDictionary != null || alternatives.size != 1) value.add("alternatives", JsonArray().apply { alternatives.forEach { add(it) } })
            inputs.add(value)
        }
        add("itemInputs", inputs)
        add("itemOutputs", JsonArray().apply { recipe.output?.let { add(codec.item(it).apply { addProperty("chance", 10000) }) } })
        add("fluidInputs", JsonArray()); add("fluidOutputs", JsonArray())
        addProperty("durationTicks", if (recipe.kind == "furnace") 200 else 0); addProperty("eut", 0)
        if (recipe.kind == "shaped") add("grid", JsonObject().apply {
            addProperty("width", recipe.width); addProperty("height", recipe.height); add("cells", cells)
            addProperty("mirrored", recipe.mirrored)
        })
        addProperty("copiesInputNbt", recipe.copiesInputNbt)
        if (recipe.kind == "furnace") addProperty("experience", recipe.experience)
        val warnings = recipe.warnings +
            (if (recipe.kind == "custom-crafting") listOf("Custom IRecipe: input, matching and dynamic output rules unknown") else emptyList()) +
            (if (recipe.inputs.filterNotNull().any { it.alternatives.isEmpty() }) listOf("Empty input alternatives: ingredient cannot match") else emptyList())
        add("warnings", JsonSupport.strings(warnings)); addProperty("complete", warnings.isEmpty())
        addProperty("id", "$id:${map(recipe.kind)}:${JsonSupport.hash(this)}")
    }
}
