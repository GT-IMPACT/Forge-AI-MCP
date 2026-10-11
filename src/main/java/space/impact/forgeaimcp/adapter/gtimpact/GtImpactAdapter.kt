package space.impact.forgeaimcp.adapter.gtimpact

import com.google.gson.*
import net.minecraft.item.ItemStack
import net.minecraftforge.fluids.FluidStack
import space.impact.forgeaimcp.adapter.*
import space.impact.forgeaimcp.adapter.JsonSupport.array
import space.impact.forgeaimcp.adapter.JsonSupport.bool
import space.impact.forgeaimcp.adapter.JsonSupport.int
import space.impact.forgeaimcp.adapter.JsonSupport.text
import java.lang.reflect.Modifier
import java.util.Locale

class GtImpactAdapter : RecipeAdapter {
    override val id = "gregtech-impact"
    companion object {
        const val REPOSITORY = "https://github.com/GT-IMPACT/GT5.09OFFBEAT"
        const val SOURCE_COMMIT = "b8ab69ba9486e1b1f4913ca95dab1c64e4d07552"
        const val MAP_CLASS = "gregtech.api.util.GT_Recipe\$GT_Recipe_Map"
        const val RECIPE_CLASS = "gregtech.api.util.GT_Recipe"
        const val ASSEMBLY_CLASS = "gregtech.api.util.GT_Recipe\$GT_Recipe_AssemblyLine"
    }
    internal data class MapHandle(val id: String, val raw: Any?, val symbol: String?, val recipes: List<Any>, val assembly: Boolean = false)
    private data class CachedRecipe(val mapId: String, val raw: Any)
    private val discovered = object : LinkedHashMap<String, CachedRecipe>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRecipe>?) = size > 4096
    }

    override fun detect(environment: ModEnvironment): AdapterDetection {
        if (environment.mod("gregtech") == null) return AdapterDetection(false, "GregTech is not installed")
        val marker = environment.findClass("space.impact.gregtech.BuildConfigKt")
            ?: return AdapterDetection(false, "GregTech fork is unknown: GT Impact build marker is missing")
        if (marker.getField("MODID").get(null) != "gregtech") return AdapterDetection(false, "Invalid GT Impact build marker")
        val recipe = environment.findClass(RECIPE_CLASS) ?: return AdapterDetection(false, "GT Impact recipe class missing")
        val map = environment.findClass(MAP_CLASS) ?: return AdapterDetection(false, "Unsupported GT Impact recipe map API")
        try {
            for (name in listOf("mInputs", "mOutputs", "mFluidInputs", "mFluidOutputs", "mChances", "mSpecialItems",
                "mDuration", "mEUt", "mSpecialValue", "mEnabled", "mHidden", "mFakeRecipe", "mCanBeBuffered", "mNeedsEmptyOutput")) recipe.getField(name)
            for (name in listOf("mDuration", "mEUt", "mSpecialValue"))
                if (recipe.getField(name).type != Int::class.javaPrimitiveType) return AdapterDetection(false, "Incompatible integer field: $name")
            for (name in listOf("mEnabled", "mHidden", "mFakeRecipe", "mCanBeBuffered", "mNeedsEmptyOutput"))
                if (recipe.getField(name).type != Boolean::class.javaPrimitiveType) return AdapterDetection(false, "Incompatible flag: $name")
            for (name in listOf("mInputs", "mOutputs", "mFluidInputs", "mFluidOutputs"))
                if (!recipe.getField(name).type.isArray) return AdapterDetection(false, "Incompatible stack array: $name")
            if (recipe.getField("mChances").type != IntArray::class.java) return AdapterDetection(false, "Incompatible chance array")
            for (name in listOf("sMappings", "mRecipeList", "mUnlocalizedName", "mNEIName", "mAmperage",
                "mUsualInputCount", "mUsualOutputCount", "mMinimalInputItems", "mMinimalInputFluids", "mNEIAllowed",
                "mNEISpecialValuePre", "mNEISpecialValuePost", "mNEISpecialValueMultiplier", "sCNCRecipes", "sSpaceResearchRecipes")) map.getField(name)
            val builder = map.getMethod("recipeCreate").returnType
            builder.getMethod("addInputItems", Array<ItemStack>::class.java)
            builder.getMethod("addOutputItems", Array<ItemStack>::class.java)
            builder.getMethod("addInputFluids", Array<FluidStack>::class.java)
            builder.getMethod("addOutputFluids", Array<FluidStack>::class.java)
            builder.getMethod("addDuration", Int::class.javaPrimitiveType)
            builder.getMethod("addEUt", Int::class.javaPrimitiveType)
            builder.getMethod("addSpecialValue", Int::class.javaPrimitiveType)
            builder.getMethod("addChance", IntArray::class.java)
            builder.getMethod("addSpecialItem", Array<Any>::class.java)
            builder.getMethod("build", Boolean::class.javaPrimitiveType)
            recipe.getMethod("getNeiDesc")
            val assembly = environment.findClass(ASSEMBLY_CLASS) ?: return AdapterDetection(false, "Assembly line API missing")
            for (name in listOf("sAssemblylineRecipes", "mResearchItem", "mResearchTime", "mInputs", "mFluidInputs",
                "mOutput", "mDuration", "mEUt", "mOreDictAlt")) assembly.getField(name)
        } catch (e: ReflectiveOperationException) {
            return AdapterDetection(false, "GT Impact lineage detected but API is incompatible: ${e.message}")
        }
        return AdapterDetection(true, "GT Impact lineage and recipe API detected (not generic GT5/GTNH compatibility)",
            listOf("space.impact.gregtech.BuildConfigKt.MODID=gregtech", "recipeCreate/addInputItems/addEUt/build(boolean)",
                "sCNCRecipes", "sSpaceResearchRecipes", "source profile $SOURCE_COMMIT"))
    }

    override fun call(operation: String, arguments: JsonObject, environment: ModEnvironment, codec: RecipeValueCodec): JsonObject {
        if (operation == "get_recipe_authoring_guide") return guide(environment)
        val maps = maps(environment)
        return when (operation) {
            "list_recipe_maps" -> {
                val query = text(arguments, "query", "").lowercase(Locale.ROOT)
                JsonSupport.page(maps.map { describeMap(it) }.filter { it.toString().lowercase(Locale.ROOT).contains(query) }, arguments)
                    .apply { add("provenance", provenance(environment)); addProperty("scope", "registered collections; dynamically computed recipes are not enumerated") }
            }
            "search_machine_recipes" -> search(maps, arguments, environment, codec)
            "get_machine_recipe" -> {
                val recipeId = text(arguments, "recipeId")
                val cached = discovered[recipeId] ?: error("Recipe ID not discovered in this session or expired. Run search_machine_recipes first.")
                val map = maps.find { it.id == cached.mapId } ?: error("Recipe map no longer exists")
                check(map.recipes.any { it === cached.raw }) { "Recipe was removed. Repeat the search." }
                val recipe = read(map, cached.raw, environment, codec)
                check(recipe.get("id").asString == recipeId) { "Recipe changed. Repeat the search." }
                recipe
            }
            "get_recipe_authoring_guide" -> guide(environment)
            else -> error("Unsupported adapter operation: $operation")
        }
    }

    internal fun maps(environment: ModEnvironment): List<MapHandle> {
        val type = environment.findClass(MAP_CLASS) ?: error("GT Impact map class missing")
        val symbols = type.fields.filter { Modifier.isStatic(it.modifiers) && type.isAssignableFrom(it.type) }.sortedBy { it.name }
        val nativeMaps = (type.getField("sMappings").get(null) as Collection<*>).filterNotNull()
        val maps = nativeMaps.map { map ->
            val symbol = symbols.firstOrNull { it.get(null) === map }?.name
            val key = symbol ?: (map.javaClass.name + "/" + field(map, "mUnlocalizedName"))
            MapHandle(key, map, symbol, (field(map, "mRecipeList") as Collection<*>).filterNotNull())
        }
        require(maps.map { it.id }.distinct().size == maps.size) { "Ambiguous GT recipe map IDs; a dedicated addon adapter is required" }
        val assembly = environment.findClass(ASSEMBLY_CLASS) ?: error("Assembly line class missing")
        return (maps + MapHandle("assembly-line", null, null,
            (assembly.getField("sAssemblylineRecipes").get(null) as Collection<*>).filterNotNull(), true)).sortedBy { it.id }
    }

    internal fun describeMap(map: MapHandle): JsonObject = JsonObject().apply {
        addProperty("id", map.id); addProperty("adapter", id); addProperty("kind", if (map.assembly) "assembly-line" else "machine")
        addProperty("recipeCount", map.recipes.size)
        addProperty("javaSymbol", map.symbol?.let { "GT_Recipe.GT_Recipe_Map.$it" })
        if (map.raw != null) {
            addProperty("javaType", map.raw.javaClass.name)
            for (name in listOf("mUnlocalizedName", "mNEIName", "mAmperage", "mUsualInputCount", "mUsualOutputCount",
                "mMinimalInputItems", "mMinimalInputFluids", "mNEIAllowed", "mNEISpecialValuePre", "mNEISpecialValuePost", "mNEISpecialValueMultiplier"))
                add(name, primitive(field(map.raw, name)))
            addProperty("slotCountsAre", "usual GUI layout, not machine capacity limits")
        } else addProperty("name", "Assembly line: native recipes, including research and alternatives")
    }

    private fun search(maps: List<MapHandle>, args: JsonObject, environment: ModEnvironment, codec: RecipeValueCodec): JsonObject {
        val mapId = text(args, "map", "")
        if (mapId.isNotEmpty()) require(maps.any { it.id == mapId }) { "Unknown map: $mapId. Call list_recipe_maps." }
        val direction = text(args, "direction", "any")
        require(direction in listOf("input", "output", "any")) { "Invalid direction" }
        val item = text(args, "item", "")
        val fluid = text(args, "fluid", "")
        val query = text(args, "query", "").lowercase(Locale.ROOT)
        val offset = int(args, "offset"); val limit = int(args, "limit", 20)
        require(offset >= 0 && limit in 1..100) { "Invalid pagination" }
        val results = JsonArray(); val warnings = mutableListOf<String>(); var total = 0
        for (map in maps.filter { mapId.isEmpty() || it.id == mapId }) for (raw in map.recipes) {
            try {
                if (!map.assembly && ((!bool(args, "includeHidden") && field(raw, "mHidden") == true)
                    || (!bool(args, "includeFake") && field(raw, "mFakeRecipe") == true)
                    || (!bool(args, "includeDisabled") && field(raw, "mEnabled") == false))) continue
                if (item.isNotEmpty() && candidates(map, raw, direction, false).none {
                    val value = codec.item(it)
                    value.get("item").asString == item && (!args.has("metadata") || int(args, "metadata") == 32767 || int(value, "metadata") == 32767 || int(value, "metadata") == int(args, "metadata")) &&
                        (!args.has("nbt") || value.get("nbt")?.takeUnless { it.isJsonNull }?.asString == text(args, "nbt"))
                }) continue
                if (fluid.isNotEmpty() && candidates(map, raw, direction, true).none { codec.fluid(it).get("fluid").asString == fluid }) continue
                if (query.isNotEmpty() && !map.id.lowercase(Locale.ROOT).contains(query) &&
                    candidates(map, raw, "any", false).none { codec.item(it).toString().lowercase(Locale.ROOT).contains(query) } &&
                    candidates(map, raw, "any", true).none { codec.fluid(it).toString().lowercase(Locale.ROOT).contains(query) }) continue
                if (total >= offset && total.toLong() < offset.toLong() + limit) {
                    val value = read(map, raw, environment, codec)
                    results.add(value)
                    if (!value.get("complete").asBoolean) warnings.add("${map.id}: recipe contains unsupported values; see recipe warnings")
                }
                total++
            } catch (e: Exception) { warnings.add("${map.id}: ${e.cause?.message ?: e.message}") }
        }
        return JsonObject().apply {
            add("results", results); addProperty("total", total); addProperty("offset", offset); addProperty("limit", limit)
            addProperty("hasMore", offset.toLong() + limit < total); add("warnings", JsonSupport.strings(warnings)); addProperty("complete", warnings.isEmpty())
            addProperty("scope", "registered native recipes; dynamic findRecipe results not enumerated; identity filtering does not simulate GT ore unification")
            add("provenance", provenance(environment))
        }
    }

    private fun candidates(map: MapHandle, raw: Any, direction: String, fluids: Boolean): List<Any> {
        val result = mutableListOf<Any>()
        if (direction != "output") {
            result.addAll(elements(field(raw, if (fluids) "mFluidInputs" else "mInputs")).filterNotNull())
            if (map.assembly && !fluids) elements(field(raw, "mOreDictAlt")).filterNotNull().forEach { result.addAll(elements(it).filterNotNull()) }
        }
        if (direction != "input") {
            if (map.assembly) { if (!fluids) field(raw, "mOutput")?.let { result.add(it) } }
            else result.addAll(elements(field(raw, if (fluids) "mFluidOutputs" else "mOutputs")).filterNotNull())
        }
        return result
    }

    internal fun read(map: MapHandle, raw: Any, environment: ModEnvironment, codec: RecipeValueCodec): JsonObject {
        val warnings = mutableListOf<String>()
        val recipe = JsonObject().apply {
            addProperty("adapter", id); addProperty("map", map.id); addProperty("kind", if (map.assembly) "assembly-line" else "machine")
            addProperty("javaType", raw.javaClass.name)
            add("itemInputs", JsonArray().apply { elements(field(raw, "mInputs")).forEach { value ->
                add(value?.let { codec.item(it).apply { addProperty("consumed", int(this, "count", 1) != 0) } } ?: JsonNull.INSTANCE)
            } })
            val outputs = if (map.assembly) listOf(field(raw, "mOutput")) else elements(field(raw, "mOutputs"))
            val chances = if (map.assembly) emptyList() else elements(field(raw, "mChances"))
            if (!map.assembly) add("rawOutputChances", rawValue(field(raw, "mChances"), codec, warnings))
            add("itemOutputs", JsonArray().apply { outputs.forEachIndexed { i, value ->
                add(value?.let { codec.item(it).apply {
                    val chance = (chances.getOrNull(i) as? Number)?.toInt() ?: 10000
                    addProperty("chance", chance); addProperty("chanceScale", 10000); addProperty("probability", chance / 10000.0)
                } } ?: JsonNull.INSTANCE)
            } })
            add("fluidInputs", rawArray(field(raw, "mFluidInputs")) { codec.fluid(it) })
            add("fluidOutputs", if (map.assembly) JsonArray() else rawArray(field(raw, "mFluidOutputs")) { codec.fluid(it) })
            val duration = (field(raw, "mDuration") as Number).toInt(); val eut = (field(raw, "mEUt") as Number).toInt()
            addProperty("durationTicks", duration); addProperty("durationSeconds", duration / 20.0)
            addProperty("eut", eut); addProperty("totalEu", duration.toLong() * eut)
            addProperty("energyBasis", "base registered recipe, without machine overclocking; eut already includes recipe amperage")
            if (map.assembly) {
                add("research", JsonObject().apply {
                    add("item", field(raw, "mResearchItem")?.let { codec.item(it) } ?: JsonNull.INSTANCE)
                    addProperty("timeTicks", (field(raw, "mResearchTime") as Number).toInt())
                })
                add("inputAlternatives", JsonArray().apply { elements(field(raw, "mOreDictAlt")).forEach { add(rawArray(it) { codec.item(it) }) } })
            } else {
                add("flags", JsonObject().apply {
                    for (name in listOf("mEnabled", "mHidden", "mFakeRecipe", "mCanBeBuffered", "mNeedsEmptyOutput")) add(name, primitive(field(raw, name)))
                })
                val special = (field(raw, "mSpecialValue") as Number).toInt()
                add("special", JsonObject().apply {
                    addProperty("value", special)
                    addProperty("meaning", specialMeaning(map.id, special))
                    add("items", rawValue(field(raw, "mSpecialItems"), codec, warnings))
                    addProperty("configurationDependent", special < 0)
                    addProperty("displayPrefix", field(map.raw!!, "mNEISpecialValuePre")?.toString())
                    addProperty("displaySuffix", field(map.raw, "mNEISpecialValuePost")?.toString())
                    addProperty("displayMultiplier", (field(map.raw, "mNEISpecialValueMultiplier") as Number).toInt())
                })
                addProperty("amperage", (field(map.raw!!, "mAmperage") as Number).toInt())
                add("neiDescription", rawValue(raw.javaClass.getMethod("getNeiDesc").invoke(raw), codec, warnings))
            }
            val known = setOf("mInputs", "mOutputs", "mFluidInputs", "mFluidOutputs", "mChances", "mSpecialItems", "mDuration", "mEUt", "mSpecialValue",
                "mEnabled", "mHidden", "mFakeRecipe", "mCanBeBuffered", "mNeedsEmptyOutput", "mResearchItem", "mResearchTime", "mOutput", "mOreDictAlt")
            add("extensions", JsonObject().apply {
                raw.javaClass.fields.filter { !Modifier.isStatic(it.modifiers) && it.name !in known }.forEach {
                    add(it.name, rawValue(it.get(raw), codec, warnings))
                }
            })
            add("provenance", provenance(environment)); add("warnings", JsonSupport.strings(warnings)); addProperty("complete", warnings.isEmpty())
        }
        val recipeId = "$id:${map.id}:${JsonSupport.hash(recipe)}"
        recipe.addProperty("id", recipeId)
        discovered[recipeId] = CachedRecipe(map.id, raw)
        return recipe
    }

    private fun rawValue(value: Any?, codec: RecipeValueCodec, warnings: MutableList<String>, depth: Int = 0): JsonElement = when {
        value == null -> JsonNull.INSTANCE
        value is ItemStack -> codec.item(value)
        value is FluidStack -> codec.fluid(value)
        value is String || value is Number || value is Boolean -> primitive(value)
        depth < 8 && (value.javaClass.isArray || value is Collection<*>) -> JsonArray().apply { elements(value).forEach { add(rawValue(it, codec, warnings, depth + 1)) } }
        else -> JsonObject().apply { addProperty("javaType", value.javaClass.name); addProperty("unsupported", true); warnings.add("Opaque special/extension value: ${value.javaClass.name}") }
    }

    internal fun provenance(environment: ModEnvironment) = JsonObject().apply {
        addProperty("adapter", id); addProperty("fork", "GT Impact")
        addProperty("repository", REPOSITORY); addProperty("sourceProfileCommit", SOURCE_COMMIT)
        addProperty("modVersion", environment.mod("gregtech")?.version)
        addProperty("buildVersion", try { environment.findClass("space.impact.gregtech.BuildConfigKt")?.getField("VERSION")?.get(null)?.toString() } catch (_: ReflectiveOperationException) { null })
        addProperty("artifact", environment.mod("gregtech")?.artifact)
        addProperty("identityAssurance", "Impact lineage markers and API shape; not an artifact signature or proof of exact source commit")
    }

    private fun guide(environment: ModEnvironment) = JsonObject().apply {
        add("provenance", provenance(environment))
        addProperty("text", GtImpactAdapter::class.java.getResourceAsStream("/assets/forgeaimcp/mcp/skills/gt-impact-recipes/references/authoring.md")
            ?.use { it.reader(Charsets.UTF_8).readText() } ?: error("Missing bundled authoring guide"))
        addProperty("workflow", "list_adapters -> list_recipe_maps -> search items/fluids -> search_machine_recipes/get_machine_recipe -> read the guide -> AI writes Kotlin using verified runtime data and the GT Impact API")
        addProperty("doesNotApplyRecipes", true)
        addProperty("skill", GtImpactAdapter::class.java.getResourceAsStream("/assets/forgeaimcp/mcp/skills/gt-impact-recipes/SKILL.md")
            ?.use { it.reader(Charsets.UTF_8).readText() } ?: error("Missing bundled skill"))
    }

    internal fun specialMeaning(map: String, value: Int): String = when (value) {
        -100 -> "low_gravity"; -200 -> "cleanroom"; -300 -> "cleanroom_and_low_gravity"; -201 -> "assembly_line_scan"; -400 -> "deprecated"
        in -1100..-500 -> if (value % 100 == 0) "planet_and_srs_tier_${(-value - 400) / 100}" else "unknown"
        else -> when { map in listOf("sBlastRecipes", "sBlastSmelterRecipes") -> "heat_kelvin"; map == "sFusionRecipes" -> "startup_eu"; value == 0 -> "none"; else -> "map_specific_raw_value" }
    }

    internal fun field(value: Any, name: String): Any? = value.javaClass.getField(name).get(value)
    private fun elements(value: Any?): List<Any?> = when {
        value == null -> emptyList(); value is Collection<*> -> value.toList()
        value.javaClass.isArray -> (0 until java.lang.reflect.Array.getLength(value)).map { java.lang.reflect.Array.get(value, it) }
        else -> listOf(value)
    }
    private fun rawArray(value: Any?, encoder: (Any) -> JsonElement) = JsonArray().apply { elements(value).forEach { add(it?.let(encoder) ?: JsonNull.INSTANCE) } }
    private fun primitive(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE; is Number -> JsonPrimitive(value); is Boolean -> JsonPrimitive(value); else -> JsonPrimitive(value.toString())
    }
}
