---
name: minecraft-recipes
description: Write Kotlin recipes for Minecraft 1.7.10 Forge crafting and furnaces using ForgeMCP runtime data or saved visual drafts. Covers shaped, shapeless and OreDictionary variants; custom IRecipe implementations need their own source inspection.
---

# Minecraft 1.7.10 recipes

Use adapter `minecraft`. It reads shared CraftingManager/FurnaceRecipes registries, including other mods' registrations; it does not mean every recipe belongs to vanilla Minecraft. Use `list_recipe_maps`, `search_machine_recipes`, `get_machine_recipe` and item/OreDictionary tools to verify IDs, metadata, output counts and alternatives. Saved editor drafts are desired recipes, not proof of runtime registration. Read `get_recipe_draft` and its notes, then write Kotlin yourself in the target project's registration phase.

Check the runtime `registration` context (also `list_adapters.craftingRegistration`) BEFORE choosing an authoring API. When GT Impact is present and its methods are verified, read `get_recipe_authoring_guide` with adapter `gregtech-impact` and register ALL crafting/furnace recipes through its GT_ModHandler methods, even when the draft's data adapter is `minecraft`. Data source and registration owner are separate. The direct Forge calls below apply when GregTech is absent. An unknown GregTech fork or incompatible helper API requires its own verified profile/source inspection; do not guess or silently bypass its recipe management.

## Crafting

- `crafting-shaped` / kind `shaped`: `grid.width`, `grid.height` (1..3), `grid.cells` row-major indices into `itemInputs`; null is an empty cell. Preserve spaces and positions. `grid.mirrored` is horizontal mirroring; patterns can also be translated within the 3×3 grid.
- `crafting-shapeless` / kind `shapeless`: each itemInputs entry is one occupied slot, including repeated ingredients. Order does not matter; 1..9 slots. Vanilla crafting consumes ONE item per occupied slot, regardless of the ItemStack's declared size (`declaredCount` is retained for inspection). Container item returns are determined by the item implementation, not a recipe output slot.
- For concrete stacks, use `GameRegistry.addRecipe(output, "AB", " A", 'A', stackA, 'B', stackB)` or `GameRegistry.addShapelessRecipe(output, stackA, stackB)` (Kotlin varargs, chars stay Char). Imports: `cpw.mods.fml.common.registry.GameRegistry`, `net.minecraft.item.ItemStack`.
- For OreDictionary, register `GameRegistry.addRecipe(ShapedOreRecipe(output, "AB", " A", 'A', "ingotIron", 'B', stackB).setMirrored(mirrored))`, or `GameRegistry.addRecipe(ShapelessOreRecipe(output, "ingotIron", stackB))`. Imports: `net.minecraftforge.oredict.ShapedOreRecipe`, `ShapelessOreRecipe`. Verify the group with `get_oredict`; an empty group cannot match.
- Vanilla ShapedRecipes is always mirrored. For mirrored=false use ShapedOreRecipe even with all concrete stacks. Vanilla ShapedRecipes `func_92100_c()` enables input NBT copying: preserve `copiesInputNbt`; the last tagged input in inventory iteration supplies the copied NBT. Forge Ore recipes do not implement this flag.
- Ore ingredient `alternatives` are the full current group contents; `oreDictionary` is only returned when the original list is the actual OreDictionary list (reference identity). Do not infer a group from overlapping item memberships. Arbitrary alternative lists without an exact group require inspecting the target API or a custom IRecipe, not inventing an ore group.
- Input matching in these standard implementations uses item+metadata, with metadata 32767 as wildcard, and ignores ingredient NBT. Output NBT is retained. An NBT-constrained request needs a custom recipe implementation; writing SNBT into an input stack does not enforce it.
- `crafting-custom` / `custom-crafting` is partial discovery only. Subclasses and dynamic IRecipe rules are not decoded. Inspect `implementation` source to understand tool damage, containers, conditions and getCraftingResult. Do not silently turn them into plain shaped/shapeless recipes.

## Furnace

Use `GameRegistry.addSmelting(inputStack, outputStack, experience.toFloat())`. One input item is consumed, one output stack is produced. Output must be a concrete stack; the furnace registry has no OreDictionary input or alternative list API. For an ore group explicitly register each verified member, mind wildcard overlap and duplicate registrations. Input matching is item+metadata (32767 wildcard), not NBT or stack count. Furnace experience is obtained from the runtime output lookup, including item overrides; it is not guaranteed to be the original registration argument.

`experience` is XP per result as used by Minecraft, not EU. `durationTicks=200` is the standard TileEntityFurnace cooking behavior; the registration API cannot set duration. `eut=0` means no EU parameter. Fuel burn times are a separate registry/mechanism, not part of the smelting recipe. A custom time, fuel condition, fluid, secondary output or chance requires another machine/API.

Avoid changing existing recipes without a request. Report what code was written and compile it against the target project's real Forge API. Runtime registration and craftability remain separate verification steps.
