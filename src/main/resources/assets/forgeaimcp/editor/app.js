const $ = (id) => document.getElementById(id);
const clone = (data) => JSON.parse(JSON.stringify(data));
const emptyDraft = () => ({
  schemaVersion: 1,
  title: "Новый рецепт",
  adapter: "gregtech-impact",
  map: "",
  kind: "machine",
  itemInputs: [],
  itemOutputs: [],
  fluidInputs: [],
  fluidOutputs: [],
  specialItems: [],
  durationTicks: 200,
  eut: 30,
  specialValue: 0,
  research: { item: null, timeTicks: 0 },
  notes: "",
});
const state = {
  draft: emptyDraft(),
  dirty: false,
  tab: "items",
  offset: 0,
  total: 0,
  limit: 64,
  maps: [],
  adapters: [],
  assignment: null,
  searchSequence: 0,
};
let toastTimer, searchTimer, exportTimer;
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key === "value") node.value = value;
    else if (key === "disabled") node.disabled = value;
    else node.setAttribute(key, value);
  }
  for (const child of children.flat())
    if (child != null)
      node.append(
        typeof child === "string" ? document.createTextNode(child) : child,
      );
  return node;
}
async function api(path, body) {
  const options =
    body === undefined
      ? {}
      : {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-ForgeMCP": "editor",
          },
          body: JSON.stringify(body),
        };
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok || data.error)
    throw Error(data.error || `HTTP ${response.status}`);
  return data;
}
const query = (tool, args = {}) => api("/api/query", { tool, arguments: args });
function toast(message, error = false) {
  clearTimeout(toastTimer);
  $("toast").textContent = message;
  $("toast").className = "toast" + (error ? " error" : "");
  $("toast").hidden = false;
  toastTimer = setTimeout(() => ($("toast").hidden = true), 5500);
}
function dirty() {
  state.dirty = true;
  try {
    localStorage.setItem("forgemcp-unsaved-draft", JSON.stringify(state.draft));
  } catch {}
  updateStatus();
}
function updateStatus() {
  const vanilla = isVanillaKind();
  $("draft-state").textContent = state.dirty
    ? "Есть изменения"
    : state.draft.id
      ? "Сохранён"
      : "Новый черновик";
  $("draft-state").className = "badge" + (state.dirty ? " dirty" : "");
  $("flow-time").textContent =
    state.draft.kind === "shaped" || state.draft.kind === "shapeless"
      ? "Крафт"
      : `${state.draft.durationTicks / 20} с`;
  $("duration-hint").textContent =
    `${state.draft.durationTicks / 20} секунд · ${state.draft.durationTicks} тиков`;
  $("energy-hint").textContent =
    `${(state.draft.durationTicks * state.draft.eut).toLocaleString("ru-RU")} EU за рецепт`;
  const issues = [];
  if (!state.draft.map) issues.push("Выберите карту рецептов");
  if (!state.draft.itemOutputs.length && !state.draft.fluidOutputs.length)
    issues.push("Добавьте хотя бы один выход");
  if (!state.draft.durationTicks && !vanilla)
    issues.push("Укажите длительность");
  if (vanilla) {
    if (state.draft.itemOutputs.length !== 1)
      issues.push("Нужен один выходной предмет");
    if (!state.draft.itemInputs.length)
      issues.push("Добавьте входные предметы");
    if (
      state.draft.itemInputs.length > (state.draft.kind === "furnace" ? 1 : 9)
    )
      issues.push("Слишком много входных слотов");
    if (
      state.draft.fluidInputs.length ||
      state.draft.fluidOutputs.length ||
      state.draft.specialItems.length
    )
      issues.push("Этот API не поддерживает жидкости и специальный слот");
    if (state.draft.itemInputs.some((s) => s.count !== 1))
      issues.push("В каждой клетке расходуется один предмет");
    if (state.draft.itemOutputs.some((s) => s.chance !== 10000))
      issues.push("Выход всегда гарантирован: шанс 10000");
    if (state.draft.itemInputs.some((s) => s.nbt))
      issues.push("Стандартное совпадение входа не проверяет NBT");
    if (
      state.registration?.adapter === "gregtech-impact" &&
      state.draft.kind === "furnace" &&
      state.draft.experience > 0
    )
      issues.push(
        "Печка GT Impact регистрируется с XP=0. Ненулевой опыт требует решения с ИИ.",
      );
    if (
      state.draft.kind === "furnace" &&
      state.draft.itemInputs.some((s) => s.oreDictionary || s.alternatives)
    )
      issues.push(
        "OreDictionary и альтернативы печки требуют отдельных регистраций",
      );
    if (
      state.draft.kind === "shaped" &&
      state.draft.copiesInputNbt &&
      (state.draft.grid?.mirrored === false ||
        state.draft.itemInputs.some((s) => s.oreDictionary || s.alternatives))
    )
      issues.push(
        "Копирование NBT — флаг Vanilla ShapedRecipes; Ore-рецептам нужен свой IRecipe",
      );
  }
  if (state.draft.kind === "assembly-line") {
    if (!state.draft.research?.item || !state.draft.research?.timeTicks)
      issues.push("Добавьте предмет и время исследования");
    if (state.draft.itemInputs.length < 4 || state.draft.itemInputs.length > 15)
      issues.push("Assembly Line: 4…15 входных слотов");
    if (state.draft.itemOutputs.length !== 1 || state.draft.fluidOutputs.length)
      issues.push(
        "Assembly Line: один выходной предмет, без выходных жидкостей",
      );
  }
  if (state.draft.sourceSpecial)
    issues.push(
      "Проверьте исходный special items в данных импортированного рецепта",
    );
  $("checks").replaceChildren(
    ...(issues.length
      ? issues.map((text) => el("div", { class: "issue" }, "• " + text))
      : [
          el("div", { class: "ok" }, "✓ Основные поля заполнены"),
          el(
            "div",
            {},
            "Совместимость рецепта с машиной проверит ИИ по API адаптера.",
          ),
        ]),
  );
  if (vanilla && state.registration)
    $("checks").append(
      el(
        "div",
        {},
        state.registration.adapter === "gregtech-impact"
          ? "Регистрация через GT Impact · GT_ModHandler"
          : state.registration.requiresVerifiedProfile
            ? "Нужен проверенный API установленного форка GregTech"
            : "Регистрация через Minecraft Forge",
      ),
    );
  $("ai-instruction").textContent = instruction();
}
function instruction() {
  return state.draft.id
    ? `Прочитай get_recipe_draft с id="${state.draft.id}". Прочитай get_recipe_authoring_guide для ${state.draft.adapter}. ${isVanillaKind() ? "Проверь list_adapters.craftingRegistration: если установлен GregTech, используй проверенные методы его адаптера и прочитай его руководство. " : ""}Напиши Kotlin-код этого рецепта в проекте с учётом заметок и правил форка.`
    : "Сохраните черновик и попросите ИИ написать рецепт по его данным.";
}
function icon(data) {
  if (data.fluid) return el("span", { class: "icon-fallback", text: "mB" });
  if (!data.item) return el("span", { class: "icon-fallback", text: "OD" });
  const params = new URLSearchParams({
    v: "2",
    item: data.item,
    metadata: String(data.metadata || 0),
  });
  if (data.nbt) params.set("nbt", data.nbt);
  const image = el("img", {
    class: "item-icon",
    src: "/api/icon?" + params,
    alt: "",
    loading: "lazy",
  });
  image.addEventListener(
    "error",
    () =>
      image.replaceWith(
        el("span", {
          class: "icon-fallback",
          text: (data.name || data.item.split(":")[1])
            .slice(0, 2)
            .toUpperCase(),
        }),
      ),
    { once: true },
  );
  return image;
}
function itemName(data) {
  return String(
    data.name || data.oreDictionary || data.fluid || data.item || "Без имени",
  );
}
function assignmentTarget(data) {
  if (state.assignment) return state.assignment;
  const key = $("target").value;
  return {
    key: data.fluid
      ? key === "itemOutputs"
        ? "fluidOutputs"
        : "fluidInputs"
      : key,
  };
}
function addStack(data, assignment = assignmentTarget(data)) {
  const value = clone(data);
  delete value.icon;
  delete value.consumed;
  if (value.fluid) value.amount = value.amount || 1000;
  else value.count = value.count ?? 1;
  if (
    isVanillaKind() &&
    (value.fluid || !["itemInputs", "itemOutputs"].includes(assignment.key))
  )
    return toast("Для этого рецепта доступны только предметы", true);
  if (assignment.alternative) {
    const slot = state.draft.itemInputs[assignment.index];
    if (!slot) return;
    if (value.fluid || value.oreDictionary)
      return toast("Альтернативой должен быть конкретный предмет", true);
    if (slot.alternatives?.length >= 100)
      return toast("Максимум 100 альтернатив в слоте", true);
    slot.alternatives = slot.alternatives || [clone(slot)];
    delete slot.alternatives[0].alternatives;
    value.count = slot.count;
    slot.alternatives.push(value);
  } else if (assignment.key === "research") {
    if (value.fluid || value.oreDictionary)
      return toast("Для исследования нужен конкретный предмет", true);
    state.draft.research = {
      ...(state.draft.research || {}),
      item: value,
      timeTicks: state.draft.research?.timeTicks || 0,
    };
  } else {
    if (assignment.key.startsWith("fluid") !== Boolean(value.fluid))
      return toast("Предметы и жидкости добавляются в разные слоты", true);
    if (assignment.key === "itemOutputs" && value.oreDictionary)
      return toast("Для выхода выберите конкретный предмет", true);
    if ((state.draft[assignment.key] || []).length >= 64)
      return toast("Максимум 64 слота", true);
    if (assignment.key === "itemOutputs") value.chance = value.chance || 10000;
    if (assignment.key === "itemInputs" && state.draft.kind === "shaped") {
      ensureGrid();
      const cell = assignment.cell ?? state.draft.grid.cells.indexOf(null);
      if (cell < 0) return toast("Сетка заполнена", true);
      const index = state.draft.grid.cells[cell];
      if (index === null) {
        state.draft.grid.cells[cell] = state.draft.itemInputs.length;
        state.draft.itemInputs.push(value);
      } else state.draft.itemInputs[index] = value;
    } else state.draft[assignment.key].push(value);
  }
  state.assignment = null;
  dirty();
  renderSlots();
  toast("Добавлено: " + itemName(value));
}
function chooseTarget(key, cell) {
  state.assignment = { key, cell };
  state.tab = key.startsWith("fluid") ? "fluids" : "items";
  document
    .querySelectorAll("[data-catalog]")
    .forEach((button) =>
      button.classList.toggle("active", button.dataset.catalog === state.tab),
    );
  state.offset = 0;
  loadCatalog();
  $("search").focus();
  toast(
    "Выберите " +
      (key.startsWith("fluid") ? "жидкость" : "предмет") +
      " в каталоге",
  );
}
function slotNode(value, key, index) {
  const research = key === "research";
  const node = el("div", {
    class: "slot",
    title:
      itemName(value) +
      " · " +
      (value.item || value.oreDictionary || value.fluid),
  });
  const name = el(
    "div",
    { class: "slot-name" },
    itemName(value),
    el(
      "small",
      {},
      value.oreDictionary
        ? "OreDictionary"
        : value.fluid || `${value.item} · ${value.metadata || 0}`,
    ),
  );
  const remove = () => {
    if (research) state.draft.research.item = null;
    else {
      state.draft[key].splice(index, 1);
      if (key === "itemInputs" && state.draft.kind === "shaped")
        state.draft.grid.cells = state.draft.grid.cells.map((i) =>
          i === index ? null : i > index ? i - 1 : i,
        );
    }
    dirty();
    renderSlots();
  };
  node.append(
    el(
      "div",
      { class: "slot-main" },
      icon(value),
      name,
      el(
        "button",
        { class: "slot-remove", title: "Убрать из рецепта", onclick: remove },
        "×",
      ),
    ),
  );
  const amount = el("input", {
    type: "number",
    min: key === "itemInputs" || key === "specialItems" ? 0 : 1,
    value: value.fluid ? value.amount : value.count,
    "aria-label": "Количество " + itemName(value),
    oninput: (event) => {
      const number = Number(event.target.value);
      if (!Number.isInteger(number) || number < Number(event.target.min))
        return;
      if (value.fluid) value.amount = number;
      else {
        value.count = number;
        if (value.alternatives)
          value.alternatives.forEach((alt) => (alt.count = number));
      }
      dirty();
    },
  });
  const options = el(
    "div",
    { class: "slot-options" },
    el("label", {}, value.fluid ? "mB" : "×", amount),
    el("button", { onclick: () => editStack(value, key, index) }, "Данные"),
  );
  if (key === "itemOutputs" && !isVanillaKind()) {
    const chance = el("input", {
      type: "number",
      min: 1,
      max: 10000,
      value: value.chance || 10000,
      "aria-label": "Шанс выхода",
      oninput: (event) => {
        const n = Number(event.target.value);
        if (Number.isInteger(n) && n >= 1 && n <= 10000) {
          value.chance = n;
          dirty();
        }
      },
    });
    options.append(el("label", {}, "Шанс", chance));
  }
  if (key === "itemInputs") {
    options.append(
      el(
        "button",
        {
          title: "Добавить альтернативный предмет в этот слот",
          onclick: () => {
            state.assignment = { key, index, alternative: true };
            state.tab = "items";
            loadCatalog();
            toast("Выберите альтернативный предмет в каталоге");
          },
        },
        value.alternatives
          ? `${value.alternatives.length} вариантов +`
          : "Вариант +",
      ),
    );
  }
  node.append(options);
  return node;
}
function isVanillaKind() {
  return ["shaped", "shapeless", "furnace"].includes(state.draft.kind);
}
function ensureGrid() {
  if (!state.draft.grid)
    state.draft.grid = {
      width: 3,
      height: 3,
      mirrored: true,
      cells: Array.from({ length: 9 }, (_, i) =>
        i < state.draft.itemInputs.length ? i : null,
      ),
    };
}
function resizeGrid() {
  ensureGrid();
  const old = state.draft.grid;
  const width = Number($("grid-width").value),
    height = Number($("grid-height").value);
  const cells = Array.from({ length: width * height }, (_, i) => {
    const x = i % width,
      y = Math.floor(i / width);
    return x < old.width && y < old.height
      ? old.cells[y * old.width + x]
      : null;
  });
  const inputs = [];
  state.draft.grid = {
    ...old,
    width,
    height,
    cells: cells.map((i) =>
      i == null ? null : inputs.push(state.draft.itemInputs[i]) - 1,
    ),
  };
  state.draft.itemInputs = inputs;
  dirty();
  renderSlots();
}
function renderSlots() {
  const vanilla = isVanillaKind(),
    shaped = state.draft.kind === "shaped";
  $("grid-controls").hidden = !shaped;
  $("experience-field").hidden = state.draft.kind !== "furnace";
  $("experience").value = state.draft.experience ?? 0;
  for (const id of ["eut", "special"]) $(id).closest("label").hidden = vanilla;
  $("duration").closest("label").hidden = vanilla;
  document.querySelector(".fluids-row").hidden = vanilla;
  $("specialItems").closest("section").hidden = vanilla;
  if (shaped) {
    ensureGrid();
    $("grid-width").value = state.draft.grid.width;
    $("grid-height").value = state.draft.grid.height;
    $("grid-mirrored").checked = state.draft.grid.mirrored !== false;
    $("copy-nbt").checked = Boolean(state.draft.copiesInputNbt);
  }
  for (const key of [
    "itemInputs",
    "itemOutputs",
    "fluidInputs",
    "fluidOutputs",
    "specialItems",
  ]) {
    const container = $(key);
    container.classList.toggle("crafting-grid", key === "itemInputs" && shaped);
    if (key === "itemInputs" && shaped) {
      container.style.gridTemplateColumns = `repeat(${state.draft.grid.width}, minmax(0, 1fr))`;
      container.replaceChildren(
        ...state.draft.grid.cells.map((index, cell) => {
          const node =
            index === null
              ? el(
                  "button",
                  {
                    class: "slot-empty",
                    onclick: () => chooseTarget(key, cell),
                  },
                  "＋",
                )
              : slotNode(state.draft.itemInputs[index], key, index);
          node.addEventListener("dragover", (e) => e.preventDefault());
          node.addEventListener("drop", (e) => {
            e.preventDefault();
            e.stopPropagation();
            container.classList.remove("drop-hover");
            try {
              addStack(
                JSON.parse(
                  e.dataTransfer.getData("application/x-forgemcp-stack"),
                ),
                { key, cell },
              );
            } catch {
              toast("Не удалось прочитать предмет", true);
            }
          });
          return node;
        }),
      );
      continue;
    }
    container.style.gridTemplateColumns = "";
    container.replaceChildren(
      ...state.draft[key].map((value, index) => slotNode(value, key, index)),
    );
    container.append(
      el(
        "button",
        { class: "slot-empty", onclick: () => chooseTarget(key) },
        state.draft[key].length
          ? "＋ Добавить ещё"
          : "＋ Перетащите сюда или выберите в каталоге",
      ),
    );
  }
  const assembly = state.draft.kind === "assembly-line";
  $("research-section").hidden = !assembly;
  $("research-slot").replaceChildren(
    state.draft.research?.item
      ? slotNode(state.draft.research.item, "research", 0)
      : el(
          "button",
          { class: "slot-empty", onclick: () => chooseTarget("research") },
          "＋ Предмет исследования",
        ),
  );
  $("research-time").value = state.draft.research?.timeTicks || 0;
  updateStatus();
}
function dialog(title, ...nodes) {
  $("dialog-title").textContent = title;
  $("dialog-body").replaceChildren(...nodes);
  if (!$("dialog").open) $("dialog").showModal();
}
function editStack(value, key, index) {
  const form = el("div", { class: "dialog-fields" });
  const identity = el("input", {
    value: value.fluid || value.oreDictionary || value.item,
  });
  form.append(
    el(
      "label",
      {},
      value.fluid
        ? "Fluid ID"
        : value.oreDictionary
          ? "Группа OreDictionary"
          : "Registry ID",
      identity,
    ),
  );
  const metadata = el("input", {
    type: "number",
    min: 0,
    max: 32767,
    value: value.metadata || 0,
  });
  if (!value.fluid && !value.oreDictionary)
    form.append(el("label", {}, "Metadata (32767 = wildcard)", metadata));
  const nbt = el("textarea", {
    value: value.nbt || "",
    placeholder: "{...} или пусто",
  });
  form.append(el("label", {}, "NBT (SNBT)", nbt));
  if (value.alternatives)
    form.append(
      el(
        "p",
        {},
        "Альтернативы: " + value.alternatives.map(itemName).join(", "),
      ),
    );
  const apply = () => {
    if (!identity.value.trim()) return toast("Укажите ID", true);
    if (value.fluid) value.fluid = identity.value.trim();
    else if (value.oreDictionary) value.oreDictionary = identity.value.trim();
    else {
      if (!identity.value.includes(":"))
        return toast("Укажите ID в формате mod:item", true);
      if (
        !Number.isInteger(Number(metadata.value)) ||
        Number(metadata.value) < 0 ||
        Number(metadata.value) > 32767
      )
        return toast("Неверная metadata", true);
      value.item = identity.value.trim();
      value.metadata = Number(metadata.value);
    }
    value.nbt = nbt.value.trim() || null;
    dirty();
    renderSlots();
    $("dialog").close();
  };
  dialog(
    "Данные слота",
    form,
    el(
      "div",
      { class: "dialog-actions" },
      el("button", { class: "button primary", onclick: apply }, "Применить"),
    ),
  );
}
async function loadCatalog() {
  const sequence = ++state.searchSequence;
  $("catalog-count").textContent = "Поиск…";
  try {
    const tool = {
      items: "search_items",
      fluids: "search_fluids",
      ores: "search_oredict",
    }[state.tab];
    const data = await query(tool, {
      query: $("search").value,
      offset: state.offset,
      limit: state.limit,
    });
    if (sequence !== state.searchSequence) return;
    state.total = data.total;
    $("catalog-count").textContent =
      data.total.toLocaleString("ru-RU") + " найдено";
    $("catalog-page").textContent =
      `${Math.floor(state.offset / state.limit) + 1} / ${Math.max(1, Math.ceil(data.total / state.limit))}`;
    $("previous").disabled = state.offset === 0;
    $("next").disabled = !data.hasMore;
    const nodes = data.results.map((value) => {
      const isOre = state.tab === "ores";
      const item = isOre ? { oreDictionary: value.name, count: 1 } : value;
      const button = el(
        "button",
        {
          class: "catalog-item " + (isOre ? "ore" : value.fluid ? "fluid" : ""),
          title:
            (itemName(item) || "") +
            "\n" +
            (item.item || item.fluid || item.oreDictionary) +
            "\nНажмите: добавить · правая кнопка: подробности",
          draggable: isOre ? "false" : "true",
          onclick: () => addStack(item),
          oncontextmenu: (event) => {
            event.preventDefault();
            catalogDetails(item);
          },
          ondragstart: (event) =>
            event.dataTransfer.setData(
              "application/x-forgemcp-stack",
              JSON.stringify(item),
            ),
        },
        icon(item),
        el("span", { class: "catalog-name" }, itemName(item)),
      );
      return button;
    });
    $("catalog-results").replaceChildren(
      ...(nodes.length
        ? nodes
        : [
            el(
              "div",
              { class: "catalog-empty" },
              "Ничего не найдено. Попробуйте название или registry ID.",
            ),
          ]),
    );
  } catch (error) {
    if (sequence !== state.searchSequence) return;
    $("catalog-count").textContent = "Нет данных";
    $("catalog-results").replaceChildren(
      el("div", { class: "catalog-empty" }, error.message),
    );
    toast(error.message, true);
  }
}
async function catalogDetails(item) {
  const content = el(
    "div",
    {},
    el("p", {}, item.item || item.fluid || item.oreDictionary),
    el("p", {}, "Metadata: " + (item.metadata || 0)),
    el("p", {}, "NBT: " + (item.nbt || "нет")),
  );
  const actions = el(
    "div",
    { class: "catalog-detail-actions" },
    el(
      "button",
      {
        class: "button",
        onclick: () => {
          addStack(item);
          $("dialog").close();
        },
      },
      "Добавить в рецепт",
    ),
  );
  if (item.item)
    actions.append(
      el(
        "button",
        { class: "button primary", onclick: () => findRecipes(item) },
        "Рецепты получения",
      ),
    );
  dialog(itemName(item), content, actions);
}
async function findRecipes(item) {
  dialog("Рецепты получения", el("p", {}, "Поиск…"));
  try {
    const data = await query("search_machine_recipes", {
      adapter: state.draft.adapter,
      item: item.item,
      metadata: item.metadata || 0,
      direction: "output",
      limit: 100,
    });
    dialog(
      "Рецепты получения",
      ...(data.results.length
        ? data.results.map((recipe) =>
            el(
              "button",
              {
                class: "recipe-row",
                onclick: () => {
                  if (!replaceAllowed()) return;
                  loadRecipe(recipe);
                  $("dialog").close();
                },
              },
              recipe.map,
              el(
                "small",
                {},
                `${recipe.durationSeconds} с · ${recipe.eut} EU/t · ${recipe.itemInputs.length} входов`,
              ),
            ),
          )
        : [el("p", {}, "Зарегистрированные машинные рецепты не найдены.")]),
      ...(data.warnings || []).map((w) =>
        el("p", { class: "source-warning" }, w),
      ),
    );
  } catch (error) {
    dialog("Поиск рецептов", el("p", {}, error.message));
  }
}
function replaceAllowed() {
  return (
    !state.dirty ||
    confirm("Открыть другой рецепт? Несохранённые изменения будут заменены.")
  );
}
function loadRecipe(recipe) {
  if (recipe.kind === "custom-crafting" || recipe.complete === false)
    return toast(
      "Правила этого рецепта декодированы не полностью. Откройте данные и исходный IRecipe для ИИ.",
      true,
    );
  const draft = emptyDraft();
  Object.assign(draft, {
    title: recipe.itemOutputs[0]?.name || "Импортированный рецепт",
    adapter: recipe.adapter,
    map: recipe.map,
    kind: recipe.kind,
    durationTicks: recipe.durationTicks,
    eut: recipe.eut,
    specialValue: recipe.special?.value || 0,
    sourceRecipeId: recipe.id,
    source: recipe,
    grid: recipe.grid ? clone(recipe.grid) : undefined,
    experience: recipe.experience ?? 0,
    copiesInputNbt: recipe.copiesInputNbt || false,
  });
  for (const key of [
    "itemInputs",
    "itemOutputs",
    "fluidInputs",
    "fluidOutputs",
  ])
    draft[key] = clone(recipe[key] || []).filter(Boolean);
  if (recipe.research) draft.research = clone(recipe.research);
  if (recipe.inputAlternatives)
    recipe.inputAlternatives.forEach((alts, i) => {
      if (alts?.length && draft.itemInputs[i])
        draft.itemInputs[i].alternatives = clone(alts);
    });
  const special = recipe.special?.items;
  if (special) {
    const values = Array.isArray(special) ? special : [special];
    if (values.every((v) => v?.item)) draft.specialItems = clone(values);
    else draft.sourceSpecial = clone(special);
  }
  state.draft = draft;
  state.dirty = true;
  loadFields();
  loadMaps();
  dirty();
  toast("Рецепт открыт как новый черновик");
}
function loadFields() {
  $("title").value = state.draft.title;
  $("adapter").value = state.draft.adapter;
  $("duration").value = state.draft.durationTicks;
  $("eut").value = state.draft.eut;
  $("special").value = state.draft.specialValue;
  $("notes").value = state.draft.notes || "";
  renderSlots();
}
async function loadMaps() {
  const adapter = state.draft.adapter;
  try {
    const maps = [];
    let offset = 0;
    while (true) {
      const page = await query("list_recipe_maps", {
        adapter,
        offset,
        limit: 100,
      });
      maps.push(...page.results);
      if (!page.hasMore) break;
      offset += 100;
    }
    if (adapter !== state.draft.adapter) return;
    state.maps = maps;
    $("map").replaceChildren(
      el("option", { value: "" }, "Выберите машину…"),
      ...maps.map((map) =>
        el(
          "option",
          { value: map.id, disabled: map.authoringSupported === false },
          map.mNEIName || map.name || map.id,
        ),
      ),
    );
    if (state.draft.map && !maps.some((map) => map.id === state.draft.map))
      $("map").append(
        el(
          "option",
          { value: state.draft.map },
          state.draft.map + " (нет в текущей сборке)",
        ),
      );
    $("map").value = state.draft.map;
    updateStatus();
  } catch (error) {
    $("map").replaceChildren(el("option", { value: "" }, "Карты недоступны"));
    toast(error.message, true);
  }
}
async function saveDraft() {
  $("save").disabled = true;
  const savingDraft = state.draft;
  const submitted = JSON.stringify(savingDraft);
  try {
    const saved = await api("/api/drafts", savingDraft);
    if (state.draft !== savingDraft) {
      toast("Предыдущий черновик сохранён");
      return;
    }
    if (JSON.stringify(state.draft) === submitted) {
      state.draft = saved;
      state.dirty = false;
      localStorage.removeItem("forgemcp-unsaved-draft");
      toast("Черновик сохранён. ИИ может прочитать его через MCP.");
    } else {
      state.draft.id = saved.id;
      state.draft.updatedAt = saved.updatedAt;
      dirty();
      toast("Черновик сохранён; новые изменения ещё не сохранены.");
    }
    loadFields();
  } catch (error) {
    toast(error.message, true);
  } finally {
    $("save").disabled = false;
  }
}
async function openDrafts() {
  try {
    const data = await api("/api/drafts");
    dialog(
      "Сохранённые черновики",
      ...(data.results.length
        ? data.results.map((draft) =>
            el(
              "button",
              {
                class: "draft-row",
                onclick: async () => {
                  if (!replaceAllowed()) return;
                  try {
                    state.draft = await api(
                      "/api/drafts/" + encodeURIComponent(draft.id),
                    );
                    normalizeDraft();
                    state.dirty = false;
                    localStorage.removeItem("forgemcp-unsaved-draft");
                    loadFields();
                    await loadMaps();
                    $("dialog").close();
                  } catch (error) {
                    toast(error.message, true);
                  }
                },
              },
              draft.title,
              el("small", {}, `${draft.adapter} · ${draft.map}`),
              el("small", {}, draft.id),
            ),
          )
        : [el("p", {}, "Пока нет сохранённых черновиков.")]),
    );
  } catch (error) {
    toast(error.message, true);
  }
}
function normalizeDraft() {
  const base = emptyDraft();
  state.draft = { ...base, ...state.draft };
  for (const key of [
    "itemInputs",
    "itemOutputs",
    "fluidInputs",
    "fluidOutputs",
    "specialItems",
  ]) {
    if (!Array.isArray(state.draft[key]) || state.draft[key].length > 64)
      throw Error("Неверные слоты: " + key);
  }
  state.draft.research = state.draft.research || base.research;
  if (state.draft.schemaVersion !== 1)
    throw Error("Неподдерживаемая версия черновика");
}
async function pollExport() {
  try {
    const status = await api("/api/export");
    $("export-progress").hidden = status.total === 0;
    $("export-progress").max = status.total || 1;
    $("export-progress").value = status.processed;
    if (status.total)
      $("export-status").textContent =
        `${status.processed.toLocaleString("ru-RU")} / ${status.total.toLocaleString("ru-RU")} · ошибок: ${status.failed}` +
        (status.running
          ? " · держите игру открытой"
          : status.total
            ? " · " + status.directory
            : "");
    $("export-icons").disabled = status.running;
    if (status.lastError)
      $("export-status").textContent += " · " + status.lastError;
    if (!status.running) {
      clearInterval(exportTimer);
      exportTimer = null;
    }
  } catch (error) {
    clearInterval(exportTimer);
    exportTimer = null;
    $("export-icons").disabled = false;
    toast(error.message, true);
  }
}
function downloadDraft() {
  const blob = new Blob([JSON.stringify(state.draft, null, 2)], {
    type: "application/json",
  });
  const url = URL.createObjectURL(blob);
  const link = el("a", {
    href: url,
    download: "recipe-" + (state.draft.id || "draft") + ".json",
  });
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
for (const key of [
  "itemInputs",
  "itemOutputs",
  "fluidInputs",
  "fluidOutputs",
  "specialItems",
  "research",
]) {
  const node = key === "research" ? $("research-slot") : $(key);
  node.addEventListener("dragover", (event) => {
    event.preventDefault();
    node.classList.add("drop-hover");
  });
  node.addEventListener("dragleave", () => node.classList.remove("drop-hover"));
  node.addEventListener("drop", (event) => {
    event.preventDefault();
    node.classList.remove("drop-hover");
    try {
      addStack(
        JSON.parse(event.dataTransfer.getData("application/x-forgemcp-stack")),
        { key },
      );
    } catch {
      toast("Не удалось прочитать перетаскиваемый предмет", true);
    }
  });
}
document.querySelectorAll("[data-catalog]").forEach((button) =>
  button.addEventListener("click", () => {
    state.tab = button.dataset.catalog;
    state.assignment = null;
    state.offset = 0;
    document
      .querySelectorAll("[data-catalog]")
      .forEach((tab) => tab.classList.toggle("active", tab === button));
    loadCatalog();
  }),
);
$("search").addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    state.offset = 0;
    loadCatalog();
  }, 220);
});
$("previous").onclick = () => {
  state.offset = Math.max(0, state.offset - state.limit);
  loadCatalog();
};
$("next").onclick = () => {
  state.offset += state.limit;
  loadCatalog();
};
$("target").onchange = () => (state.assignment = null);
$("title").oninput = () => {
  state.draft.title = $("title").value;
  dirty();
};
for (const [id, key] of [
  ["duration", "durationTicks"],
  ["eut", "eut"],
  ["special", "specialValue"],
])
  $(id).addEventListener("input", () => {
    const value = Number($(id).value);
    if (!Number.isInteger(value) || (key !== "specialValue" && value < 0))
      return;
    state.draft[key] = value;
    dirty();
  });
$("notes").oninput = () => {
  state.draft.notes = $("notes").value;
  dirty();
};
$("research-time").oninput = () => {
  const value = Number($("research-time").value);
  if (!Number.isInteger(value) || value < 0) return;
  state.draft.research = { ...(state.draft.research || {}), timeTicks: value };
  dirty();
};
$("adapter").onchange = () => {
  state.draft.adapter = $("adapter").value;
  state.draft.map = "";
  state.draft.kind = "machine";
  dirty();
  loadMaps();
  renderSlots();
};
$("map").onchange = () => {
  const previousKind = state.draft.kind;
  state.draft.map = $("map").value;
  state.draft.kind =
    state.maps.find((map) => map.id === state.draft.map)?.kind || "machine";
  if (isVanillaKind()) {
    state.draft.durationTicks = state.draft.kind === "furnace" ? 200 : 0;
    state.draft.eut = 0;
  }
  if (state.draft.kind === "shaped") {
    if (previousKind !== "shaped") delete state.draft.grid;
    ensureGrid();
  }
  dirty();
  renderSlots();
};
$("grid-width").onchange = resizeGrid;
$("grid-height").onchange = resizeGrid;
$("grid-mirrored").onchange = () => {
  ensureGrid();
  state.draft.grid.mirrored = $("grid-mirrored").checked;
  dirty();
};
$("copy-nbt").onchange = () => {
  state.draft.copiesInputNbt = $("copy-nbt").checked;
  dirty();
};
$("experience").oninput = () => {
  const xp = Number($("experience").value);
  if (Number.isFinite(xp) && xp >= 0) {
    state.draft.experience = xp;
    dirty();
  }
};
$("save").onclick = saveDraft;
$("saved-drafts").onclick = openDrafts;
$("new-draft").onclick = () => {
  if (!replaceAllowed()) return;
  const adapter = state.draft.adapter;
  state.draft = emptyDraft();
  state.draft.adapter = adapter;
  state.assignment = null;
  state.dirty = false;
  localStorage.removeItem("forgemcp-unsaved-draft");
  loadFields();
  loadMaps();
};
$("dialog-close").onclick = () => $("dialog").close();
$("download").onclick = downloadDraft;
$("import").onclick = () => $("import-file").click();
$("import-file").onchange = async () => {
  const file = $("import-file").files[0];
  if (!file) return;
  if (file.size > 262144) return toast("JSON должен быть меньше 256 KiB", true);
  if (!replaceAllowed()) return;
  const previous = state.draft;
  try {
    const value = JSON.parse(await file.text());
    state.draft = value;
    normalizeDraft();
    loadFields();
    await loadMaps();
    dirty();
    toast("Черновик импортирован");
  } catch (error) {
    state.draft = previous;
    loadFields();
    toast(error.message, true);
  } finally {
    $("import-file").value = "";
  }
};
$("guide").onclick = async () => {
  try {
    const data = await query("get_recipe_authoring_guide", {
      adapter: state.draft.adapter,
    });
    dialog(
      "API " + state.draft.adapter,
      el("pre", { class: "guide-text" }, data.text),
    );
  } catch (error) {
    toast(error.message, true);
  }
};
$("copy-instruction").onclick = async () => {
  if (!state.draft.id || state.dirty)
    return toast("Сначала сохраните текущие изменения", true);
  try {
    await navigator.clipboard.writeText(instruction());
    toast("Запрос для ИИ скопирован");
  } catch {
    dialog(
      "Запрос для ИИ",
      el("textarea", {
        class: "guide-text",
        readonly: "",
        value: instruction(),
      }),
    );
  }
};
$("export-icons").onclick = async () => {
  try {
    $("export-icons").disabled = true;
    await api("/api/export", {});
    await pollExport();
    if (!exportTimer) exportTimer = setInterval(pollExport, 1500);
  } catch (error) {
    $("export-icons").disabled = false;
    toast(error.message, true);
  }
};
window.addEventListener("beforeunload", (event) => {
  if (state.dirty) {
    event.preventDefault();
    event.returnValue = "";
  }
});
async function init() {
  try {
    const unsaved = localStorage.getItem("forgemcp-unsaved-draft");
    if (unsaved) {
      state.draft = JSON.parse(unsaved);
      normalizeDraft();
      state.dirty = true;
    }
  } catch {
    state.draft = emptyDraft();
  }
  loadFields();
  try {
    const status = await query("get_status");
    $("connection").replaceChildren(
      el("span", { class: "dot" }),
      status.developmentPreview
        ? "Предпросмотр · тестовый каталог"
        : `${status.itemCount.toLocaleString("ru-RU")} предметов · игра подключена`,
    );
    const adapters = await query("list_adapters");
    state.registration = adapters.craftingRegistration;
    state.adapters = adapters.results.filter((a) => a.available);
    $("adapter").replaceChildren(
      ...state.adapters.map((adapter) =>
        el("option", { value: adapter.adapter }, adapter.adapter),
      ),
    );
    if (!state.adapters.length) {
      $("adapter").append(
        el(
          "option",
          { value: state.draft.adapter },
          "Нет совместимого адаптера",
        ),
      );
      toast("Каталог доступен, но адаптер рецептов не обнаружен", true);
    } else if (
      !state.adapters.some((a) => a.adapter === state.draft.adapter) &&
      !state.draft.id &&
      !state.dirty
    )
      state.draft.adapter = state.adapters[0].adapter;
    $("adapter").value = state.draft.adapter;
    await loadMaps();
    await loadCatalog();
    await pollExport();
  } catch (error) {
    $("connection").classList.add("error");
    $("connection").replaceChildren(
      el("span", { class: "dot" }),
      "Игра недоступна",
    );
    toast(error.message, true);
  }
}
init();
