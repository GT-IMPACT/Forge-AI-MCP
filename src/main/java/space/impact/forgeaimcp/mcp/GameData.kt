package space.impact.forgeaimcp.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import net.minecraft.client.Minecraft
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.nbt.JsonToNBT
import net.minecraft.nbt.NBTTagCompound
import net.minecraftforge.fluids.FluidRegistry
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.oredict.OreDictionary
import space.impact.forgeaimcp.adapter.RecipeValueCodec

/** Native item/fluid identities shared by NEI and structured adapters. Client thread only. */
object GameData : RecipeValueCodec {
    fun resolveItem(args: JsonObject): ItemStack {
        val id = args.get("item").asString
        require(id.contains(':')) { "Use a registry ID such as minecraft:iron_ingot" }
        val item = Item.itemRegistry.getObject(id) as? Item ?: error("Unknown item: $id")
        val metadata = args.get("metadata")?.asBigDecimal?.intValueExact() ?: 0
        require(metadata in 0..32767) { "metadata must be between 0 and 32767" }
        val count = args.get("count")?.asBigDecimal?.intValueExact() ?: 1
        require(count >= 0) { "count must be nonnegative" }
        return ItemStack(item, count, metadata).apply {
            if (args.has("nbt") && !args.get("nbt").isJsonNull) {
                val tag = JsonToNBT.func_150315_a(args.get("nbt").asString)
                require(tag is NBTTagCompound) { "NBT must be an SNBT compound" }
                tagCompound = tag
            }
        }
    }

    fun describeItem(stack: ItemStack, detailed: Boolean = false): JsonObject = JsonObject().apply {
        val id = Item.itemRegistry.getNameForObject(stack.item)
        addProperty("item", id)
        addProperty("mod", id.substringBefore(':'))
        addProperty("metadata", stack.itemDamage)
        addProperty("count", stack.stackSize)
        addProperty("name", try { stack.displayName } catch (e: Exception) { id })
        add("nbt", stack.tagCompound?.let { JsonPrimitive(it.toString()) } ?: JsonNull.INSTANCE)
        add("oreDict", JsonArray().apply {
            OreDictionary.getOreIDs(stack).forEach { add(JsonPrimitive(OreDictionary.getOreName(it))) }
        })
        if (detailed) add("tooltip", JsonArray().apply {
            stack.getTooltip(Minecraft.getMinecraft().thePlayer, true).forEach { add(JsonPrimitive(it)) }
        })
    }

    override fun item(value: Any) = describeItem(value as ItemStack)
    override fun fluid(value: Any): JsonObject = (value as FluidStack).let { stack ->
        JsonObject().apply {
            addProperty("fluid", stack.fluid.name)
            addProperty("name", stack.localizedName)
            addProperty("amount", stack.amount)
            addProperty("unit", "mB")
            add("nbt", stack.tag?.let { JsonPrimitive(it.toString()) } ?: JsonNull.INSTANCE)
        }
    }
    override fun validateItem(value: JsonObject): String? = try { resolveItem(value); null } catch (e: Exception) { e.message }
    override fun validateFluid(value: JsonObject): String? = try {
        require(FluidRegistry.getFluid(value.get("fluid").asString) != null) { "Unknown fluid: ${value.get("fluid")}" }
        if (value.has("nbt")) require(JsonToNBT.func_150315_a(value.get("nbt").asString) is NBTTagCompound) { "Fluid NBT must be a compound" }
        null
    } catch (e: Exception) { e.message }
}
