package space.impact.forgeaimcp.adapter.minecraft

import cpw.mods.fml.relauncher.ReflectionHelper
import net.minecraft.item.ItemStack
import net.minecraft.item.crafting.*
import net.minecraftforge.oredict.*

/** Exact implementation classes only: subclasses can override matches/getCraftingResult. */
object MinecraftRecipeSource : VanillaRecipeSource {
    override fun recipes(): List<VanillaRecipe> {
        val ores = OreDictionary.getOreNames().map { it to OreDictionary.getOres(it, false) }
        fun ingredient(value: Any?): CraftingIngredient? = when (value) {
            null -> null
            is ItemStack -> CraftingIngredient(listOf(value))
            is List<*> -> CraftingIngredient(value.filterIsInstance<ItemStack>(), ores.firstOrNull { it.second === value }?.first)
            else -> error("Unsupported crafting ingredient ${value.javaClass.name}")
        }
        val result = mutableListOf<VanillaRecipe>()
        for ((index, recipe) in CraftingManager.getInstance().recipeList.filterIsInstance<IRecipe>().withIndex()) {
            val type = recipe.javaClass
            try {
                result.add(when (type) {
                    ShapedRecipes::class.java -> (recipe as ShapedRecipes).let {
                        VanillaRecipe("shaped", type.name, it.recipeItems.map(::ingredient), it.recipeOutput,
                            it.recipeWidth, it.recipeHeight, copiesInputNbt = ReflectionHelper.getPrivateValue<Boolean, ShapedRecipes>(ShapedRecipes::class.java, it, "field_92101_f"))
                    }
                    ShapelessRecipes::class.java -> (recipe as ShapelessRecipes).let {
                        VanillaRecipe("shapeless", type.name, it.recipeItems.map(::ingredient), it.recipeOutput)
                    }
                    ShapedOreRecipe::class.java -> (recipe as ShapedOreRecipe).let {
                        VanillaRecipe("shaped", type.name, it.input.map(::ingredient), it.recipeOutput,
                            ReflectionHelper.getPrivateValue<Int, ShapedOreRecipe>(type, it, "width"),
                            ReflectionHelper.getPrivateValue<Int, ShapedOreRecipe>(type, it, "height"),
                            ReflectionHelper.getPrivateValue<Boolean, ShapedOreRecipe>(type, it, "mirrored"))
                    }
                    ShapelessOreRecipe::class.java -> (recipe as ShapelessOreRecipe).let {
                        VanillaRecipe("shapeless", type.name, it.input.map(::ingredient), it.recipeOutput)
                    }
                    else -> VanillaRecipe("custom-crafting", type.name, emptyList(), recipe.recipeOutput)
                }.copy(registryIndex = index))
            } catch (e: Exception) {
                result.add(VanillaRecipe("custom-crafting", type.name, emptyList(), null, warnings = listOf("Decode failed: ${e.message}"), registryIndex = index))
            }
        }
        val furnace = FurnaceRecipes.smelting()
        for ((input, output) in furnace.smeltingList) {
            val warnings = mutableListOf<String>()
            val xp = try {
                furnace.func_151398_b(output).let { value ->
                    if (value.isFinite() && value >= 0) value else {
                        warnings.add("Invalid runtime experience: $value"); 0f
                    }
                }
            } catch (e: Exception) { warnings.add("Experience lookup failed: ${e.message}"); 0f }
            result.add(VanillaRecipe("furnace", FurnaceRecipes::class.java.name,
                listOf(ingredient(input)), output, experience = xp, warnings = warnings))
        }
        return result
    }
}
