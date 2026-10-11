package space.impact.forgeaimcp.mcp

import codechicken.nei.ItemList
import codechicken.nei.PositionedStack
import codechicken.nei.recipe.GuiCraftingRecipe
import codechicken.nei.recipe.GuiUsageRecipe
import codechicken.nei.recipe.IRecipeHandler
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.nbt.JsonToNBT
import net.minecraft.nbt.NBTTagCompound
import net.minecraftforge.oredict.OreDictionary
import net.minecraftforge.fluids.FluidRegistry
import net.minecraftforge.fluids.FluidStack
import space.impact.forgeaimcp.adapter.RecipeAdapters
import space.impact.forgeaimcp.editor.RecipeDrafts
import java.util.Locale

/** Only called on the Minecraft client thread. Never exposes live stacks to the bridge. */
object ClientTools {
    private data class Entry(val stack: ItemStack, val id: String, val name: String)
    private var indexedList: List<ItemStack>? = null
    private var index = emptyList<Entry>()

    fun call(tool: String, args: JsonObject): JsonObject {
        if (tool == "list_recipe_drafts") return RecipeDrafts.store.list()
        if (tool == "get_recipe_draft") return RecipeDrafts.store.load(text(args, "id"))
        if (tool == "list_adapters") return RecipeAdapters.registry.list()
        if (tool == "get_recipe_authoring_guide") return RecipeAdapters.registry.call(tool, args)
        if (tool == "get_status") return JsonObject().apply {
            addProperty("running", McpRuntime.isRunning())
            addProperty("address", McpRuntime.address())
            addProperty("worldLoaded", Minecraft.getMinecraft().theWorld != null)
            addProperty("itemsReady", ItemList.loadFinished)
            addProperty("itemCount", ItemList.items.size)
        }
        check(Minecraft.getMinecraft().theWorld != null) { "Join a world before querying game data." }
        return when (tool) {
            "list_recipe_maps", "search_machine_recipes", "get_machine_recipe" -> RecipeAdapters.registry.call(tool, args)
            "search_fluids" -> {
                val query = text(args, "query", "").lowercase(Locale.ROOT)
                page(FluidRegistry.getRegisteredFluids().values.sortedBy { it.name }.filter {
                    it.name.lowercase(Locale.ROOT).contains(query) ||
                        it.getLocalizedName(FluidStack(it, 1000)).lowercase(Locale.ROOT).contains(query)
                }, args) { GameData.fluid(FluidStack(it, 1000)) }
            }
            "search_items" -> searchItems(args)
            "get_item" -> describe(resolve(args), true)
            "search_oredict" -> {
                val query = text(args, "query", "").lowercase(Locale.ROOT)
                page(OreDictionary.getOreNames().filter { it.lowercase(Locale.ROOT).contains(query) }.sorted(), args) {
                    JsonObject().apply { addProperty("name", it); addProperty("itemCount", OreDictionary.getOres(it, false).size) }
                }
            }
            "get_oredict" -> {
                val name = text(args, "name")
                require(OreDictionary.doesOreNameExist(name)) { "Unknown OreDictionary group: $name" }
                page(OreDictionary.getOres(name, false), args) { describe(it) }.apply { addProperty("name", name) }
            }
            "get_recipes", "get_usages" -> recipes(resolve(args), tool == "get_usages", args)
            else -> error("Unknown tool: $tool")
        }
    }

    private fun searchItems(args: JsonObject): JsonObject {
        check(ItemList.loadFinished) { "NEI item catalog is still loading. Retry shortly." }
        val items = ItemList.items
        if (indexedList !== items) {
            index = items.map { stack ->
                val copy = stack.copy()
                Entry(copy, Item.itemRegistry.getNameForObject(copy.item), safeName(copy))
            }
            indexedList = items
        }
        val query = text(args, "query", "").lowercase(Locale.ROOT)
        val mod = text(args, "mod", "").lowercase(Locale.ROOT)
        val matches = index.filter {
            (mod.isEmpty() || it.id.substringBefore(':').lowercase(Locale.ROOT) == mod) &&
                (it.id.lowercase(Locale.ROOT).contains(query) || it.name.lowercase(Locale.ROOT).contains(query))
        }
        return page(matches, args) { describe(it.stack) }
    }

    private fun resolve(args: JsonObject): ItemStack {
        val id = text(args, "item")
        require(id.contains(':')) { "Use a registry ID such as minecraft:iron_ingot." }
        val item = Item.itemRegistry.getObject(id) as? Item ?: error("Unknown item: $id")
        val metadata = number(args, "metadata", 0, 0, 32767)
        return ItemStack(item, 1, metadata).apply {
            if (args.has("nbt")) {
                val tag = JsonToNBT.func_150315_a(text(args, "nbt"))
                require(tag is NBTTagCompound) { "NBT must be an SNBT compound." }
                tagCompound = tag
            }
        }
    }

    private fun describe(stack: ItemStack, detailed: Boolean = false): JsonObject = JsonObject().apply {
        val id = Item.itemRegistry.getNameForObject(stack.item)
        addProperty("item", id)
        addProperty("mod", id.substringBefore(':'))
        addProperty("metadata", stack.itemDamage)
        addProperty("count", stack.stackSize)
        addProperty("name", safeName(stack))
        add("nbt", stack.tagCompound?.let { com.google.gson.JsonPrimitive(it.toString()) } ?: JsonNull.INSTANCE)
        add("oreDict", JsonArray().apply {
            OreDictionary.getOreIDs(stack).forEach { add(com.google.gson.JsonPrimitive(OreDictionary.getOreName(it))) }
        })
        if (detailed) {
            add("tooltip", JsonArray().apply {
                stack.getTooltip(Minecraft.getMinecraft().thePlayer, true).forEach {
                    add(com.google.gson.JsonPrimitive(it))
                }
            })
        }
    }

    private fun safeName(stack: ItemStack): String = try { stack.displayName } catch (e: Exception) {
        Item.itemRegistry.getNameForObject(stack.item)
    }

    private fun recipes(stack: ItemStack, usages: Boolean, args: JsonObject): JsonObject {
        check(ItemList.loadFinished) { "NEI is still loading. Retry shortly." }
        val offset = number(args, "offset", 0, 0, Int.MAX_VALUE)
        val limit = number(args, "limit", 20, 1, 100)
        val handlerFilter = text(args, "handler", "")
        val results = JsonArray()
        val warnings = JsonArray()
        var total = 0
        val handlers: List<IRecipeHandler> = if (usages) {
            (GuiUsageRecipe.usagehandlers.toList() + GuiUsageRecipe.serialUsageHandlers.toList())
        } else {
            (GuiCraftingRecipe.craftinghandlers.toList() + GuiCraftingRecipe.serialCraftingHandlers.toList())
        }
        for (handler in handlers.distinctBy { it.handlerId }) {
            if (handlerFilter.isNotEmpty() && handler.handlerId != handlerFilter) continue
            try {
                val queried = if (usages) {
                    (handler as codechicken.nei.recipe.IUsageHandler).getUsageHandler("item", stack.copy())
                } else {
                    (handler as codechicken.nei.recipe.ICraftingHandler).getRecipeHandler("item", stack.copy())
                } ?: continue
                val count = queried.numRecipes()
                for (i in 0 until count) {
                    if (total >= offset && total.toLong() < offset.toLong() + limit) {
                        try {
                            results.add(JsonObject().apply {
                                addProperty("handler", queried.handlerId)
                                addProperty("category", queried.recipeName)
                                addProperty("recipeIndex", i)
                                add("ingredients", slots(queried.getIngredientStacks(i)))
                                add("result", queried.getResultStack(i)?.let { slot(it) } ?: JsonNull.INSTANCE)
                                add("otherStacks", slots(queried.getOtherStacks(i)))
                            })
                        } catch (e: Exception) {
                            warnings.add(com.google.gson.JsonPrimitive("${handler.handlerId} recipe $i: ${e.message}"))
                        }
                    }
                    total++
                }
            } catch (e: Exception) {
                warnings.add(com.google.gson.JsonPrimitive("${handler.handlerId}: ${e.message}"))
            }
        }
        return JsonObject().apply {
            add("results", results)
            addProperty("total", total)
            addProperty("offset", offset)
            addProperty("limit", limit)
            addProperty("hasMore", offset.toLong() + limit < total)
            add("warnings", warnings)
            addProperty("complete", warnings.size() == 0)
            addProperty("details", "Generic NEI stacks only; machine energy, duration and fluid semantics may require mod adapters.")
        }
    }

    private fun slots(stacks: List<PositionedStack>?): JsonArray = JsonArray().apply {
        stacks?.forEach { add(slot(it)) }
    }

    private fun slot(stack: PositionedStack): JsonObject = JsonObject().apply {
        addProperty("x", stack.relx)
        addProperty("y", stack.rely)
        add("alternatives", JsonArray().apply { stack.items.take(100).forEach { add(describe(it)) } })
        addProperty("alternativeCount", stack.items.size)
        addProperty("alternativesTruncated", stack.items.size > 100)
    }

    private fun <T> page(values: List<T>, args: JsonObject, encode: (T) -> JsonElement): JsonObject {
        val offset = number(args, "offset", 0, 0, Int.MAX_VALUE)
        val limit = number(args, "limit", 20, 1, 100)
        return JsonObject().apply {
            add("results", JsonArray().apply { values.drop(offset).take(limit).forEach { add(encode(it)) } })
            addProperty("total", values.size)
            addProperty("offset", offset)
            addProperty("limit", limit)
            addProperty("hasMore", offset.toLong() + limit < values.size)
        }
    }

    private fun text(args: JsonObject, key: String, default: String? = null): String {
        val value = args.get(key) ?: return default ?: error("Missing argument: $key")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string" }
        return value.asString
    }

    private fun number(args: JsonObject, key: String, default: Int, min: Int, max: Int): Int {
        val value = args.get(key) ?: return default
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key must be an integer" }
        val integer = value.asBigDecimal.intValueExact()
        require(integer in min..max) { "$key must be between $min and $max" }
        return integer
    }
}
