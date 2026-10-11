package space.impact.forgeaimcp.mcp

import codechicken.nei.recipe.*
import com.google.gson.JsonObject
import space.impact.forgeaimcp.adapter.RuntimeModEnvironment

/** The same handler metadata and lookup order used by NEI's recipe tabs. Game thread only. */
object NeiPresentation {
    fun handlers(): List<IRecipeHandler> =
        (GuiCraftingRecipe.craftinghandlers.toList() + GuiCraftingRecipe.serialCraftingHandlers.toList())

    fun find(id: String, overlay: String): IRecipeHandler = handlers().firstOrNull {
        it.handlerId == id && (it.overlayIdentifier ?: "") == overlay
    } ?: error("NEI handler no longer registered")

    fun describe(handler: IRecipeHandler): JsonObject = JsonObject().apply {
        val info = GuiRecipeTab.getHandlerInfo(handler)
        val name = handler.recipeName.orEmpty().replace(Regex("§."), "").ifBlank { "??" }
        addProperty("name", name)
        addProperty("handler", handler.handlerId)
        addProperty("overlay", handler.overlayIdentifier ?: "")
        addProperty("mod", info.modName)
        add("icon", JsonObject().apply {
            when {
                info.image != null -> {
                    addProperty("kind", "handler")
                    addProperty("handler", handler.handlerId)
                    addProperty("overlay", handler.overlayIdentifier ?: "")
                }
                info.itemStack != null -> {
                    addProperty("kind", "item")
                    add("stack", GameData.describeItem(info.itemStack))
                }
                else -> { addProperty("kind", "text"); addProperty("text", name.take(2)) }
            }
        })
    }

    fun map(adapter: String, map: JsonObject): JsonObject? {
        val handler = when (adapter) {
            "gregtech-impact" -> {
                val type = RuntimeModEnvironment.findClass("gregtech.api.util.GT_Recipe\$GT_Recipe_Map") ?: return null
                val native = if (map.get("id").asString == "assembly-line") type.getField("sAssemblylineVisualRecipes").get(null)
                else if (map.get("javaSymbol")?.isJsonNull == false) type.getField(map.get("javaSymbol").asString.substringAfterLast('.')).get(null)
                else (type.getField("sMappings").get(null) as Collection<*>).filterNotNull().singleOrNull {
                    it.javaClass.name == map.get("javaType")?.asString &&
                        it.javaClass.getField("mUnlocalizedName").get(it).toString() == map.get("mUnlocalizedName")?.asString
                } ?: return null
                // Includes addon handlers backed by this exact GT Impact map, regardless of their package.
                val registered = handlers()
                registered.firstOrNull { handler ->
                    generateSequence(handler.javaClass as Class<*>?) { it.superclass }.any { owner ->
                        owner.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && type.isAssignableFrom(it.type) }.any { field ->
                            field.isAccessible = true; field.get(handler) === native
                        }
                    }
                } ?: registered.filter { it.overlayIdentifier == native.javaClass.getField("mNEIName").get(native).toString() }.singleOrNull()
            }
            "minecraft" -> {
                val className = when (map.get("id").asString) {
                    "crafting-shaped" -> "ShapedRecipeHandler"
                    "crafting-shapeless" -> "ShapelessRecipeHandler"
                    "furnace" -> "FurnaceRecipeHandler"
                    else -> return null
                }
                handlers().firstOrNull { it.javaClass.name == "codechicken.nei.recipe.$className" }
            }
            else -> {
                // Addon adapters can declare an explicit NEI binding without guessing from a display name.
                val id = map.get("neiHandler")?.asString ?: return null
                val overlay = map.get("neiOverlay")?.asString ?: ""
                handlers().firstOrNull { it.handlerId == id && (it.overlayIdentifier ?: "") == overlay }
            }
        }
        return handler?.let { describe(it) }
    }
}
