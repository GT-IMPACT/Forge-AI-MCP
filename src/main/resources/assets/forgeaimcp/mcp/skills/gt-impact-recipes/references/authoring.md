# GT Impact: написание рецептов на Kotlin

## Верстак и ванильная печка: регистрацией управляет GT

При активном профиле Impact используй `gregtech.api.util.GT_ModHandler` для любых новых shaped/shapeless/furnace рецептов. Данные этих реестров читаются адаптером `minecraft`, но код регистрирует GT. Проверенные сигнатуры:

```kotlin
GT_ModHandler.addCraftingRecipe(output, bits, arrayOf<Any>("AB", " A", 'A', stackA, 'B', "ingotIron"))
GT_ModHandler.addShapelessCraftingRecipe(output, bits, arrayOf<Any>(stackA, "ingotIron"))
GT_ModHandler.addSmeltingRecipe(input, output)
```

Это `Object[]`, а не Java varargs: в Kotlin передавай `arrayOf<Any>(...)` без `*`. Возвращается boolean; false означает отказ. Результат может унифицироваться через GT OreDict, а буферизированные рецепты появляются после обработки буфера. Сохраняй структуру регистрации и фазу загрузки целевого проекта.

`bits` — Long, символические значения `GT_ModHandler.RecipeBits`, объединение Kotlin `or`. `MIRRORED` включай по grid.mirrored; по умолчанию shaped-перегрузка без bits использует 0 и НЕ зеркалит. Для нового рецепта без запроса замены используй `DO_NOT_CHECK_FOR_COLLISIONS`, иначе shaped helper способен удалять совпадающие старые рецепты. `BUFFERED` согласуй с фазой загрузки. `NOT_REMOVABLE` запрещает удаления средствами GT, `DISMANTLEABLE` сохраняет компоненты, `REVERSIBLE` добавляет информацию о материалах; не добавляй их без требуемого поведения. `DELETE_ALL_OTHER_*` и `ONLY_ADD_IF_*` не являются безопасными флагами по умолчанию: у них есть удаления/условия.

Строчные буквы в shaped-паттерне зарезервированы GT для инструментов: b blade, c crowbar, d screwdriver, f file, h hard hammer, i soldering iron, j soldering metal, k knife, m mortar, p drawplate, r soft hammer, s saw, w wrench, x wire cutter. Обычные ингредиенты обозначай заглавными буквами. Для инструмента сохрани его OreDictionary и поведение возврата/износа; count=0 машинного слота не выражает инструмент верстака.

`KEEPNBT` в GT отличается от vanilla copiesInputNbt: требует одинаковых тегов всех непустых входов и копирует первый тег в результат. GT-рецепты также обрабатывают заряд, dismantling и enchantments. Не переносить vanilla-флаг на GT молча при изменении семантики. GT_Shaped_Recipe/GT_Shapeless_Recipe пока видны как custom IRecipe с частичными данными: для изменения существующего рецепта изучи их исходники.

`addSmeltingRecipe(ItemStack, ItemStack)` регистрирует обычную печку с XP=0, проверяет GT-конфиг smelting, отвергает вход с container item и унифицирует выход. Перегрузки с experience НЕТ. Если черновик требует XP>0, объясни конфликт и согласуй изменение XP либо доработку API самого GT; не обнуляй опыт молча и не обходи helper вызовом GameRegistry. `addSmeltingAndAlloySmeltingRecipe` дополнительно затрагивает другие машины, поэтому не заменяет обычную печку без такого запроса. Длительность стандартной печки 200 тиков, EU/t и шанс этот API не задаёт.

Профиль: [GT-IMPACT/GT5.09OFFBEAT](https://github.com/GT-IMPACT/GT5.09OFFBEAT), исходники `b8ab69ba9486e1b1f4913ca95dab1c64e4d07552`.
Это опорная версия API, а не утверждение, что запущенный JAR собран из этого коммита.
Основные источники: [GT_Recipe](https://github.com/GT-IMPACT/GT5.09OFFBEAT/blob/b8ab69ba9486e1b1f4913ca95dab1c64e4d07552/src/main/java/gregtech/api/util/GT_Recipe.java), [IGT_RecipeAdder](https://github.com/GT-IMPACT/GT5.09OFFBEAT/blob/b8ab69ba9486e1b1f4913ca95dab1c64e4d07552/src/main/java/gregtech/api/interfaces/internal/IGT_RecipeAdder.java), [GT_RecipeAdder](https://github.com/GT-IMPACT/GT5.09OFFBEAT/blob/b8ab69ba9486e1b1f4913ca95dab1c64e4d07552/src/main/java/gregtech/common/GT_RecipeAdder.java).

## Выбор профиля и данных

Сначала `list_adapters`; требуются маркер `space.impact.gregtech.BuildConfigKt`, специфичные карты Impact и совместимый API. Это проверка происхождения и формы API, не криптографическая подпись. Не переноси сюда GTNH RecipeBuilder/RecipeMaps и API других форков.

`list_recipe_maps` сообщает ID карты и `javaSymbol` для обращения к ней из Kotlin. Поле называется javaSymbol, потому что API библиотеки написан на Java; язык рецепта — Kotlin. Карта `assembly-line` представляет реальные Assembly Line рецепты, а не визуальную NEI карту. `search_machine_recipes` возвращает ID содержимого; `get_machine_recipe` принимает ID из поиска в текущей сессии. Повтори поиск после изменения рецептов или истечения кэша.

MCP читает зарегистрированные коллекции. Некоторые карты вычисляют рецепты в `findRecipe`, такие результаты не перечисляются. Поиск по ID предмета не симулирует GT ore unification. Нужные группы проверяй через OreDictionary. `includeHidden`, `includeFake`, `includeDisabled` по умолчанию false. Пагинация: offset/limit, максимум 100. `search_fluids` показывает образец 1000 mB; количество для нового рецепта выбирается по задаче.

## Машинные рецепты

Регистрация: `GT_Recipe.GT_Recipe_Map.<проверенное static поле>.recipeCreate()`.
Методы builder:

- `addInputItems(ItemStack...)`, `addOutputItems(ItemStack...)`;
- `addInputFluids(FluidStack...)`, `addOutputFluids(FluidStack...)`;
- `addChance(int...)`: порядок соответствует выходам;
- `addSpecialItem(Object...)`: специальный слот, не ordinary item input;
- `addDuration(int)`, `addEUt(int)`, `addSpecialValue(int)`;
- `build(boolean)`, `build()` — по умолчанию оптимизация включена.

Kotlin может вызывать Java varargs напрямую; для готового массива используй spread `*array`. Собирай цепочку самостоятельно из требований задачи и проверенных игровых стеков. Не подменяй эту работу вызовом генератора.

`build(false)` сохраняет выбранные количества и длительность без уменьшения партии оптимизатором. При этом GT всё равно может нормализовать стеки, сокращать совпадающие входы/выходы и выполнять логику подкласса карты. Не обещай точное сохранение первоначальной декларации.

Обычные количества слотов `mUsualInputCount/mUsualOutputCount` описывают GUI, не ограничения машины. Проверка базовой карты отвергает рецепт, когда одновременно недостаточно минимальных item inputs И fluid inputs. У подклассов дополнительные правила; выясняй их в исходниках нужной карты. Fake/Visual карты служат отображению; fuel maps и custom maps требуют отдельного понимания API, их нельзя автоматически считать обычными машинными рецептами.

## Стеки, единицы и условия

- Предмет: registry ID + metadata + count + SNBT. `Item.itemRegistry.getObject(id)` возвращает Item; создай `ItemStack(item, count, metadata)`. Проверяй, что ID существует. SNBT разбирается `JsonToNBT.func_150315_a`, результат должен быть NBTTagCompound.
- Жидкость: `FluidRegistry.getFluid(name)` + количество mB; создай FluidStack, сохрани tag при необходимости. Не путай fluid name с локализованной подписью.
- Metadata 32767 — wildcard. Нулевой count во входе GT означает требование наличия без расходования. Не исправляй его на 1 при чтении существующего рецепта.
- Chance scale 10000: 10000 = 100%, 2500 = 25%. GT нормализует отсутствующие/неположительные вероятности в 10000; сверяй итоговый runtime рецепт. `rawOutputChances` сохраняет исходный массив зарегистрированного объекта.
- Длительность — тики, 20 ticks = 1 sec. EU/t — энергия зарегистрированного рецепта до оверклока; totalEu = durationTicks * eut. Amperage карты уже учтён в базовом EU/t рецепта, не умножай totalEu повторно на amperage.
- Blast furnace special value — температура в K; fusion — стартовая энергия EU. У других карт смысл определяется картой и её машиной, а не единым глобальным правилом.
- Отрицательные special value: -100 low gravity, -200 cleanroom, -300 оба условия; -201 scanner/Assembly Line, -400 deprecated; -500…-1100 шагом 100 — планета/SRS tier 1…7. Реальное применение зависит от машины и настроек.
- В опорном исходнике `addCleanRoom()` присваивает -200 в `mSpecialItems`, а логика special value использует `mSpecialValue`. Для условия чистой комнаты проверяй машину и используй `addSpecialValue(-200)`; не копируй helper без проверки версии. `addLowGravity()` имеет аналогичную особенность.

## Assembly Line

Реальная точка регистрации — `GT_Values.RA.addAssemblylineRecipe(...)`, не обычный builder. Проверенные перегрузки:

```text
ItemStack researchItem, int researchTime,
ItemStack[] inputs ИЛИ Object[] inputs,
FluidStack[] fluidInputs, ItemStack output,
int duration, int eut
```

Для Kotlin: `arrayOf<ItemStack>(...)` для конкретных входов; `arrayOf<Any>(...)` для смешанных альтернатив. Элемент Object[] может быть ItemStack, ItemStack[] альтернатив или Object[]{oreName, amount}; в Kotlin последний выражается `arrayOf<Any>(oreName, amount)`. Для жидкости — `arrayOf<FluidStack>(...)`. Никаких fluid outputs у этой регистрации нет.

Требуются 4…15 входных позиций, ненулевой researchItem, положительный researchTime и один выход. Исследование не равно обычной длительности обработки. Настройки могут отключать рецепт; проверяй boolean результат регистрации и наличие реального рецепта в runtime. Добавляются сопутствующие fake/visual рецепты; не считай их независимыми реальными производственными путями. `inputAlternatives` соответствует позициям `itemInputs`, сохраняй альтернативы по слотам.

## Интеграция и проверка

Внешний addon должен зависеть от gregtech и регистрировать рецепты один раз после GT post-init загрузки рецептов (порядок after:gregtech). В исходном GT проекте используй существующий loader рецептов; не создавай второй вызов регистрации.
Прежде чем менять рецепт, найди коллизии по карте, входам, NBT, жидкостям и условиям. MCP не выполняет симуляцию findRecipe и не доказывает отсутствие коллизий. После сборки перезапусти игру и проверь реальную карту, выходы, время, EU/t и условия через MCP. Если есть только исходники без запущенного совместимого GT, явно укажи предел проверки.
