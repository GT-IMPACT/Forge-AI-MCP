package space.impact.forgeaimcp.adapter

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import space.impact.forgeaimcp.adapter.minecraft.*

class VanillaAdapterTest {
    private val environment = object : ModEnvironment {
        override fun mod(id: String): ModIdentity? = null
        override fun findClass(name: String): Class<*>? = String::class.java
    }
    private val codec = object : RecipeValueCodec {
        override fun item(value: Any) = JsonParser().parse(value.toString()).asJsonObject
        override fun fluid(value: Any) = error("No fluids")
        override fun validateItem(value: JsonObject): String? = null
        override fun validateFluid(value: JsonObject): String? = null
    }
    private fun stack(item: String, meta: Int = 0, count: Int = 1) = """{"item":"minecraft:$item","metadata":$meta,"count":$count,"nbt":null}"""
    private fun args(value: String = "{}") = JsonParser().parse(value).asJsonObject
    private fun source(values: MutableList<VanillaRecipe>) = object : VanillaRecipeSource { override fun recipes() = values.toList() }
    private fun search(adapter: VanillaAdapter, value: String = "{}") = adapter.call("search_machine_recipes", args(value), environment, codec)

    @Test fun shapedPreservesEmptyCellsMirroringNbtAndOreAlternatives() {
        val recipe = VanillaRecipe("shaped", "ShapedOreRecipe", listOf(null,
            CraftingIngredient(listOf(stack("iron_ingot", count = 8), stack("gold_ingot")), "ingotMetal"), null,
            CraftingIngredient(listOf(stack("stick", 32767)))), stack("pickaxe"), 2, 2, false, true)
        val adapter = VanillaAdapter(source(mutableListOf(recipe)))
        val result = search(adapter).getAsJsonArray("results")[0].asJsonObject
        assertEquals("[null,0,null,1]", result.getAsJsonObject("grid").get("cells").toString())
        assertFalse(result.getAsJsonObject("grid").get("mirrored").asBoolean)
        assertTrue(result.get("copiesInputNbt").asBoolean)
        val input = result.getAsJsonArray("itemInputs")[0].asJsonObject
        assertEquals(1, input.get("count").asInt); assertEquals(8, input.get("declaredCount").asInt)
        assertEquals("ingotMetal", input.get("oreDictionary").asString)
        assertEquals(2, input.getAsJsonArray("alternatives").size())
        assertEquals(1, search(adapter, """{"item":"minecraft:gold_ingot","direction":"input"}""").get("total").asInt)
        assertEquals(1, search(adapter, """{"item":"minecraft:stick","metadata":3}""").get("total").asInt)
    }
    @Test fun shapelessPreservesRepeatedSlotsAndFurnaceExperience() {
        val recipes = mutableListOf(VanillaRecipe("shapeless", "ShapelessRecipes",
            List(2) { CraftingIngredient(listOf(stack("stick"))) }, stack("torch", count = 4)),
            VanillaRecipe("furnace", "FurnaceRecipes", listOf(CraftingIngredient(listOf(stack("iron_ore")))), stack("iron_ingot"), experience = .7f))
        val adapter = VanillaAdapter(source(recipes))
        val shapeless = search(adapter, """{"map":"crafting-shapeless"}""").getAsJsonArray("results")[0].asJsonObject
        assertEquals(2, shapeless.getAsJsonArray("itemInputs").size()); assertFalse(shapeless.has("grid"))
        assertEquals(4, shapeless.getAsJsonArray("itemOutputs")[0].asJsonObject.get("count").asInt)
        val furnace = search(adapter, """{"map":"furnace","item":"minecraft:iron_ingot","direction":"output"}""").getAsJsonArray("results")[0].asJsonObject
        assertEquals(.7f, furnace.get("experience").asFloat, 0f); assertEquals(200, furnace.get("durationTicks").asInt)
        assertEquals(0, search(adapter, """{"fluid":"water"}""").get("total").asInt)
    }
    @Test fun customRecipesArePartialAndRemovedOrModifiedIdsAreRejected() {
        val recipes = mutableListOf(VanillaRecipe("custom-crafting", "mod.ToolRecipe", emptyList(), stack("torch")))
        val adapter = VanillaAdapter(source(recipes))
        val found = search(adapter, """{"map":"crafting-custom"}""")
        assertFalse(found.get("complete").asBoolean)
        val recipe = found.getAsJsonArray("results")[0].asJsonObject
        assertFalse(recipe.get("complete").asBoolean)
        val id = recipe.get("id").asString
        assertEquals(recipe, adapter.call("get_machine_recipe", args("""{"recipeId":"$id"}"""), environment, codec))
        recipes[0] = recipes[0].copy(output = stack("stick"))
        try { adapter.call("get_machine_recipe", args("""{"recipeId":"$id"}"""), environment, codec); fail() }
        catch (_: IllegalStateException) { }
    }
    @Test fun onlyReturnedPageIdsAreDiscoverableAndMalformedFiltersFail() {
        val recipes = mutableListOf(VanillaRecipe("furnace", "FurnaceRecipes", listOf(CraftingIngredient(listOf(stack("iron_ore")))), stack("iron_ingot")),
            VanillaRecipe("furnace", "FurnaceRecipes", listOf(CraftingIngredient(listOf(stack("gold_ore")))), stack("gold_ingot")))
        val adapter = VanillaAdapter(source(recipes))
        val first = search(adapter, """{"limit":1}""")
        assertEquals(2, first.get("total").asInt); assertTrue(first.get("hasMore").asBoolean)
        for (bad in listOf("""{"map":"nope"}""", """{"direction":"nope"}""", """{"limit":0}""")) {
            try { search(adapter, bad); fail() } catch (_: IllegalArgumentException) { }
        }
        try { adapter.call("get_machine_recipe", args("""{"recipeId":"unknown"}"""), environment, codec); fail() }
        catch (_: IllegalStateException) { }
    }
    @Test fun registrationOwnerIsIndependentOfDataAdapterAndUnknownGregtechDoesNotFallBack() {
        val gtEnv = object : ModEnvironment {
            override fun mod(id: String) = if (id == "gregtech") ModIdentity(id,"unknown",null) else null
            override fun findClass(name: String): Class<*>? = String::class.java
        }
        val vanilla = VanillaAdapter(source(mutableListOf()))
        val unknown = AdapterRegistry(gtEnv,codec).apply { register(vanilla) }
        assertTrue(unknown.call("get_recipe_authoring_guide", args("""{"adapter":"minecraft"}""")).getAsJsonObject("registration").get("requiresVerifiedProfile").asBoolean)
        val owner = object : RecipeAdapter {
            override val id = "test-fork"
            override fun detect(environment: ModEnvironment) = AdapterDetection(true,"fixture")
            override fun call(operation: String, arguments: JsonObject, environment: ModEnvironment, codec: RecipeValueCodec) = JsonObject()
            override fun craftingRegistration(environment: ModEnvironment) = JsonObject().apply { addProperty("adapter",id) }
        }
        unknown.register(owner)
        assertEquals("test-fork",unknown.call("list_recipe_maps", args("""{"adapter":"minecraft"}""" )).getAsJsonObject("registration").get("adapter").asString)
        val absent = AdapterRegistry(environment,codec).apply { register(vanilla) }
        assertEquals("minecraft",absent.list().getAsJsonObject("craftingRegistration").get("adapter").asString)
    }
}
