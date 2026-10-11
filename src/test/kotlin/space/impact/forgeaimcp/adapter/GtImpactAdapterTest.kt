package space.impact.forgeaimcp.adapter

import com.google.gson.*
import net.minecraft.item.ItemStack
import net.minecraftforge.fluids.FluidStack
import org.junit.Assert.*
import org.junit.Test
import space.impact.forgeaimcp.adapter.gtimpact.GtImpactAdapter

/** API-shape fixtures, not a replacement GregTech implementation. */
class ImpactMarker {
    companion object { @JvmField val MODID = "gregtech"; @JvmField val VERSION = "test-impact" }
}

class ImpactMap(@JvmField val mUnlocalizedName: String = "test") {
    @JvmField val mRecipeList = mutableListOf<ImpactRecipe>()
    @JvmField val mNEIName = "Test"
    @JvmField val mAmperage = 2
    @JvmField val mUsualInputCount = 1
    @JvmField val mUsualOutputCount = 1
    @JvmField val mMinimalInputItems = 1
    @JvmField val mMinimalInputFluids = 0
    @JvmField val mNEIAllowed = true
    @JvmField val mNEISpecialValuePre = ""
    @JvmField val mNEISpecialValuePost = " K"
    @JvmField val mNEISpecialValueMultiplier = 1
    fun recipeCreate() = Builder()
    class Builder {
        fun addInputItems(vararg values: ItemStack) = this
        fun addOutputItems(vararg values: ItemStack) = this
        fun addInputFluids(vararg values: FluidStack) = this
        fun addOutputFluids(vararg values: FluidStack) = this
        fun addDuration(value: Int) = this
        fun addEUt(value: Int) = this
        fun addSpecialValue(value: Int) = this
        fun addChance(vararg values: Int) = this
        fun addSpecialItem(vararg values: Any) = this
        fun build(optimize: Boolean) = Unit
    }
    companion object {
        @JvmField val sBlastRecipes = ImpactMap("blast")
        @JvmField val sCNCRecipes = ImpactMap("cnc")
        @JvmField val sSpaceResearchRecipes = ImpactMap("srs")
        @JvmField val sMappings = listOf(sBlastRecipes, sCNCRecipes, sSpaceResearchRecipes)
    }
}

class ImpactRecipe {
    @JvmField var mInputs = arrayOf<Any>("input")
    @JvmField var mOutputs = arrayOf<Any>("output")
    @JvmField var mFluidInputs = arrayOf<Any>("water")
    @JvmField var mFluidOutputs = emptyArray<Any>()
    @JvmField var mChances = intArrayOf(2500, 5000)
    @JvmField var mSpecialItems: Any? = null
    @JvmField var mDuration = 80
    @JvmField var mEUt = 30
    @JvmField var mSpecialValue = 1800
    @JvmField var mEnabled = true
    @JvmField var mHidden = false
    @JvmField var mFakeRecipe = false
    @JvmField var mCanBeBuffered = true
    @JvmField var mNeedsEmptyOutput = false
    fun getNeiDesc() = arrayOf("Description")
}

class ImpactAssembly {
    @JvmField var mInputs = arrayOf<Any>("input")
    @JvmField var mFluidInputs = arrayOf<Any>("water")
    @JvmField var mOutput: Any = "output"
    @JvmField var mResearchItem: Any = "research"
    @JvmField var mResearchTime = 400
    @JvmField var mDuration = 100
    @JvmField var mEUt = 120
    @JvmField var mOreDictAlt = arrayOf(arrayOf<Any>("input", "alternative"))
    companion object { @JvmField val sAssemblylineRecipes = mutableListOf<ImpactAssembly>() }
}

class GtImpactAdapterTest {
    private class Environment(val installed: Boolean = true, val marker: Boolean = true, val incompatible: Boolean = false) : ModEnvironment {
        override fun mod(id: String) = if (installed && id == "gregtech") ModIdentity(id, "MC1710", "impact.jar") else null
        override fun findClass(name: String): Class<*>? = when (name) {
            "space.impact.gregtech.BuildConfigKt" -> if (marker) ImpactMarker::class.java else null
            GtImpactAdapter.RECIPE_CLASS -> if (incompatible) String::class.java else ImpactRecipe::class.java
            GtImpactAdapter.MAP_CLASS -> ImpactMap::class.java
            GtImpactAdapter.ASSEMBLY_CLASS -> ImpactAssembly::class.java
            else -> null
        }
    }
    private val codec = object : RecipeValueCodec {
        override fun item(value: Any) = JsonObject().apply {
            addProperty("item", "test:$value"); addProperty("metadata", 0)
            addProperty("count", if (value == "input") 0 else 1); add("nbt", JsonNull.INSTANCE)
        }
        override fun fluid(value: Any) = JsonObject().apply { addProperty("fluid", value.toString()); addProperty("amount", 144); addProperty("unit", "mB") }
        override fun validateItem(value: JsonObject): String? = null
        override fun validateFluid(value: JsonObject): String? = null
    }
    private fun args(json: String) = JsonParser().parse(json).asJsonObject

    @Test fun detectionRejectsOtherForksAndIncompatibleApi() {
        val adapter = GtImpactAdapter()
        assertFalse(adapter.detect(Environment(installed = false)).available)
        assertFalse(adapter.detect(Environment(marker = false)).available)
        assertFalse(adapter.detect(Environment(incompatible = true)).available)
        assertTrue(adapter.detect(Environment()).available)
    }

    @Test fun exportsNativeRecipeDetailsAndRejectsChangedRecipeIds() {
        val adapter = GtImpactAdapter(); val env = Environment()
        val map = ImpactMap.sBlastRecipes
        map.mRecipeList.clear()
        val original = ImpactRecipe(); val second = ImpactRecipe().apply { mEUt = 60 }
        map.mRecipeList.addAll(listOf(original, second))
        try {
            val query = args("""{"adapter":"gregtech-impact","map":"sBlastRecipes","item":"test:input","direction":"input","limit":1}""")
            val found = adapter.call("search_machine_recipes", query, env, codec)
            assertEquals(2, found.get("total").asInt); assertTrue(found.get("hasMore").asBoolean)
            val recipe = found.getAsJsonArray("results")[0].asJsonObject
            assertEquals(2400, recipe.get("totalEu").asInt)
            assertFalse(recipe.getAsJsonArray("itemInputs")[0].asJsonObject.get("consumed").asBoolean)
            assertEquals(2500, recipe.getAsJsonArray("itemOutputs")[0].asJsonObject.get("chance").asInt)
            assertEquals(2, recipe.getAsJsonArray("rawOutputChances").size())
            assertEquals(144, recipe.getAsJsonArray("fluidInputs")[0].asJsonObject.get("amount").asInt)
            assertEquals("heat_kelvin", recipe.getAsJsonObject("special").get("meaning").asString)
            val id = recipe.get("id").asString
            map.mRecipeList.reverse()
            assertEquals(id, adapter.read(adapter.maps(env).first { it.id == "sBlastRecipes" }, original, env, codec).get("id").asString)
            original.mEUt++
            try { adapter.call("get_machine_recipe", args("""{"recipeId":"$id"}"""), env, codec); fail("Changed recipe must be rejected") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("changed")) }
            query.addProperty("nbt", "{}")
            val filtered = adapter.call("search_machine_recipes", query, env, codec)
            assertEquals(0, filtered.get("total").asInt); assertTrue(filtered.get("complete").asBoolean)
        } finally { map.mRecipeList.clear() }
    }

    @Test fun assemblyResearchAndAlternativesRemainDistinctFromMachineRecipes() {
        val adapter = GtImpactAdapter(); val env = Environment()
        ImpactAssembly.sAssemblylineRecipes.add(ImpactAssembly())
        try {
            val result = adapter.call("search_machine_recipes", args("""{"map":"assembly-line","item":"test:alternative"}"""), env, codec)
            val recipe = result.getAsJsonArray("results")[0].asJsonObject
            assertEquals("assembly-line", recipe.get("kind").asString)
            assertEquals(400, recipe.getAsJsonObject("research").get("timeTicks").asInt)
            assertEquals(2, recipe.getAsJsonArray("inputAlternatives")[0].asJsonArray.size())
            assertEquals(0, recipe.getAsJsonArray("fluidOutputs").size())
        } finally { ImpactAssembly.sAssemblylineRecipes.clear() }
    }

    @Test fun registryRequiresExplicitForkAndGuideWorksWithoutInstalledGt() {
        val registry = AdapterRegistry(Environment(installed = false), codec).apply { register(GtImpactAdapter()) }
        assertFalse(registry.list().getAsJsonArray("results")[0].asJsonObject.get("available").asBoolean)
        val guide = registry.call("get_recipe_authoring_guide", args("""{"adapter":"gregtech-impact"}"""))
        assertFalse(guide.get("runtimeAvailable").asBoolean)
        assertTrue(guide.get("text").asString.contains("Kotlin"))
        assertTrue(guide.get("skill").asString.contains("name: gt-impact-recipes"))
        try { registry.call("list_recipe_maps", args("""{"adapter":"gregtech-impact"}""")); fail() }
        catch (_: IllegalStateException) { }
        try { registry.register(GtImpactAdapter()); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun opaqueFieldsPropagateIncompleteAndHiddenRecipesRequireExplicitOptIn() {
        val adapter = GtImpactAdapter(); val env = Environment()
        val map = ImpactMap.sCNCRecipes
        val raw = ImpactRecipe().apply { mHidden = true; mSpecialItems = Any() }
        map.mRecipeList.add(raw)
        try {
            val query = args("""{"map":"sCNCRecipes"}""")
            assertEquals(0, adapter.call("search_machine_recipes", query, env, codec).get("total").asInt)
            query.addProperty("includeHidden", true)
            val result = adapter.call("search_machine_recipes", query, env, codec)
            assertFalse(result.get("complete").asBoolean)
            assertFalse(result.getAsJsonArray("results")[0].asJsonObject.get("complete").asBoolean)
            assertTrue(result.getAsJsonArray("warnings").size() > 0)
        } finally { map.mRecipeList.clear() }
    }

    @Test fun registryDispatchesDifferentForkIdsIndependently() {
        val registry = AdapterRegistry(Environment(), codec).apply { register(GtImpactAdapter()) }
        registry.register(object : RecipeAdapter {
            override val id = "another-fork"
            override fun detect(environment: ModEnvironment) = AdapterDetection(true, "fixture")
            override fun call(operation: String, arguments: JsonObject, environment: ModEnvironment, codec: RecipeValueCodec) =
                JsonObject().apply { addProperty("adapter", id) }
        })
        assertEquals(2, registry.list().getAsJsonArray("results").size())
        assertEquals("another-fork", registry.call("list_recipe_maps", args("""{"adapter":"another-fork"}""")).get("adapter").asString)
        assertTrue(registry.call("list_recipe_maps", args("""{"adapter":"gregtech-impact"}""")).getAsJsonArray("results").size() > 0)
    }
}
