import {
  RecipeGraph,
  resourceKey,
  inputsOf,
  outputsOf,
  neiRecipe,
  quantity,
  GraphLimitError,
} from "./graph.js";

const STORAGE = "forgemcp-recipe-board-v1";
const svgNS = "http://www.w3.org/2000/svg";
export function createRecipeBoard({
  query,
  el,
  icon,
  itemName,
  dialog,
  toast,
}) {
  const $ = (id) => document.getElementById(id);
  let graph = new RecipeGraph(),
    view = { x: 650, y: 180, scale: 1 },
    generation = 0;
  let initialized = false,
    adapterData = null,
    metadataPromise = null,
    drag = null,
    pickerSequence = 0;
  const maps = new Map(),
    loading = new Set();
  let budgetReached = false;
  const status = (text) => {
    $("board-status").textContent = text;
  };
  function persist() {
    try {
      localStorage.setItem(
        STORAGE,
        JSON.stringify({ ...graph.snapshot(), view }),
      );
    } catch {
      status(
        "Схема слишком велика для хранилища браузера. Сохраните её через «Экспорт схемы».",
      );
    }
  }
  function transform() {
    $("board-world").style.transform =
      `translate(${view.x}px, ${view.y}px) scale(${view.scale})`;
    $("board-zoom").textContent = `${Math.round(view.scale * 100)}%`;
  }
  function mapIcon(presentation, name) {
    const value = presentation?.icon;
    if (value?.kind === "item" && value.stack?.item) return icon(value.stack);
    if (value?.kind === "handler") {
      const params = new URLSearchParams({
        v: "3",
        handler: value.handler,
        overlay: value.overlay || "",
      });
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
            el("span", { class: "icon-fallback", text: name.slice(0, 2) }),
          ),
        { once: true },
      );
      return image;
    }
    return el("span", {
      class: "icon-fallback",
      text: value?.text || name.slice(0, 2) || "??",
    });
  }
  function recipeMap(recipe) {
    return maps.get(`${recipe.adapter}:${recipe.map}`);
  }
  function recipeName(recipe) {
    const map = recipeMap(recipe);
    return (
      recipe.presentation?.name ||
      map?.presentation?.name ||
      map?.name ||
      map?.mNEIName ||
      recipe.map
    );
  }
  function stackLine(stack) {
    return el(
      "span",
      {
        class: "board-stack-line",
        title: `${itemName(stack)} · ${quantity(stack)}`,
      },
      icon(stack),
      el("span", {}, itemName(stack)),
      el("span", { class: "board-quantity", text: quantity(stack) }),
    );
  }
  function nodeCard(node) {
    const card = el("article", {
      class:
        "board-node " + node.type + (node.id === graph.root ? " is-root" : ""),
      "data-node": node.id,
    });
    card.style.left = `${node.x}px`;
    card.style.top = `${node.y}px`;
    if (node.type === "resource") {
      const group =
        node.stack.oreDictionary || node.stack.alternatives?.length > 1;
      card.append(
        el(
          "div",
          { class: "board-node-header" },
          icon(node.stack),
          el(
            "div",
            {},
            el(
              "strong",
              {},
              group
                ? node.stack.oreDictionary || "Любой из вариантов"
                : itemName(node.stack),
            ),
            el(
              "small",
              {},
              node.stack.fluid
                ? "Жидкость"
                : group
                  ? "Взаимозаменяемые предметы"
                  : `${node.stack.item} · ${node.stack.metadata || 0}`,
            ),
          ),
        ),
      );
      if (node.stack.nbt)
        card.append(
          el(
            "small",
            { class: "board-nbt", title: node.stack.nbt },
            "NBT: " + node.stack.nbt,
          ),
        );
      const pending = loading.has(node.id);
      card.append(
        el(
          "button",
          {
            class: "button full quiet",
            disabled: pending,
            onclick: () => (group ? chooseVariant(node) : expand(node)),
          },
          pending
            ? "Поиск…"
            : group
              ? "Выбрать вариант"
              : node.more
                ? "Ещё рецепты"
                : node.expanded
                  ? "Повторить поиск"
                  : "Рецепты получения",
        ),
      );
      if (node.message)
        card.append(el("small", { class: "board-node-message" }, node.message));
    } else {
      const recipe = node.recipe,
        map = recipeMap(recipe),
        name = recipeName(recipe);
      card.append(
        el(
          "div",
          { class: "board-node-header" },
          mapIcon(recipe.presentation || map?.presentation, name),
          el(
            "div",
            {},
            el("strong", {}, name),
            el(
              "small",
              {},
              recipe.adapter === "nei"
                ? "Хандлер NEI · частичные данные"
                : recipe.adapter,
            ),
          ),
        ),
      );
      const stats =
        recipe.adapter === "nei"
          ? "Время и энергия не определены"
          : [
              recipe.durationTicks
                ? `${recipe.durationTicks / 20} с`
                : "Верстак",
              `${recipe.eut || 0} EU/t`,
              recipe.experience != null ? `${recipe.experience} XP` : null,
            ]
              .filter(Boolean)
              .join(" · ");
      card.append(el("div", { class: "board-recipe-stats", text: stats }));
      const io = el(
        "div",
        { class: "board-recipe-io" },
        el("small", {}, "Входы"),
        ...inputsOf(recipe).map(stackLine),
        el("small", {}, "Выходы"),
        ...outputsOf(recipe).map(stackLine),
      );
      card.append(io);
      if (!outputsOf(recipe).length)
        card.append(
          el(
            "small",
            {},
            "Выбранный результат найден через NEI; отдельный выходной слот не предоставлен.",
          ),
        );
      if (recipe.research?.item)
        card.append(
          el(
            "small",
            {},
            "Исследование · " +
              itemName(recipe.research.item) +
              ` · ${(recipe.research.timeTicks || 0) / 20} с`,
          ),
        );
      if (recipe.special?.value)
        card.append(
          el(
            "small",
            {},
            `${recipe.special.meaning || "Условие"}: ${recipe.special.value}`,
          ),
        );
      if (recipe.otherStacks?.length)
        card.append(
          el(
            "small",
            {},
            "Дополнительные слоты NEI: " +
              recipe.otherStacks.length +
              " (назначение не определено)",
          ),
        );
      if (recipe.complete === false || recipe.warnings?.length)
        card.append(
          el(
            "small",
            {
              class: "source-warning",
              title: (recipe.warnings || []).join("\n"),
            },
            "Частичные данные · подробности по наведению",
          ),
        );
    }
    return card;
  }
  function edges() {
    const svg = $("board-edges");
    svg.replaceChildren();
    const marker = document.createElementNS(svgNS, "marker");
    marker.id = "board-arrow";
    marker.setAttribute("viewBox", "0 0 10 10");
    marker.setAttribute("refX", "9");
    marker.setAttribute("refY", "5");
    marker.setAttribute("markerWidth", "7");
    marker.setAttribute("markerHeight", "7");
    marker.setAttribute("orient", "auto-start-reverse");
    const tip = document.createElementNS(svgNS, "path");
    tip.setAttribute("d", "M 0 0 L 10 5 L 0 10 z");
    marker.append(tip);
    const defs = document.createElementNS(svgNS, "defs");
    defs.append(marker);
    svg.append(defs);
    const cards = new Map(
      [...$("board-nodes").children].map((c) => [
        c.getAttribute("data-node"),
        c,
      ]),
    );
    for (const edge of graph.edges.values()) {
      const a = graph.nodes.get(edge.from),
        b = graph.nodes.get(edge.to),
        ac = cards.get(edge.from),
        bc = cards.get(edge.to);
      if (!a || !b || !ac || !bc) continue;
      const forward = b.x >= a.x;
      const x1 = a.x + (forward ? ac.offsetWidth : 0),
        y1 = a.y + ac.offsetHeight / 2;
      const x2 = b.x + (forward ? 0 : bc.offsetWidth),
        y2 = b.y + bc.offsetHeight / 2;
      const bend = Math.max(65, Math.abs(x2 - x1) / 2) * (forward ? 1 : -1);
      const path = document.createElementNS(svgNS, "path");
      path.setAttribute(
        "d",
        `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`,
      );
      path.setAttribute(
        "class",
        "board-link" + (edge.cycle ? " is-cycle" : ""),
      );
      path.setAttribute("marker-end", "url(#board-arrow)");
      const title = document.createElementNS(svgNS, "title");
      title.textContent = edge.label;
      path.append(title);
      svg.append(path);
      if (edge.label) {
        const text = document.createElementNS(svgNS, "text");
        text.setAttribute("x", String((x1 + x2) / 2));
        text.setAttribute("y", String((y1 + y2) / 2 - 7));
        text.setAttribute("class", "board-link-label");
        text.textContent = edge.label + (edge.cycle ? " · цикл" : "");
        svg.append(text);
      }
    }
  }
  function render() {
    $("board-nodes").replaceChildren(
      ...[...graph.nodes.values()].map(nodeCard),
    );
    $("board-empty").hidden = graph.nodes.size > 0;
    $("board-title").textContent = graph.root
      ? itemName(graph.nodes.get(graph.root).stack)
      : "Схема получения";
    transform();
    edges();
  }
  function fit() {
    if (!graph.nodes.size) return;
    const nodes = [...graph.nodes.values()],
      cards = [...$("board-nodes").children];
    const left = Math.min(...nodes.map((n) => n.x)),
      top = Math.min(...nodes.map((n) => n.y));
    const right = Math.max(
      ...nodes.map((n, i) => n.x + (cards[i]?.offsetWidth || 300)),
    );
    const bottom = Math.max(
      ...nodes.map((n, i) => n.y + (cards[i]?.offsetHeight || 180)),
    );
    const viewport = $("board-viewport");
    view.scale = Math.min(
      1.2,
      Math.max(
        0.12,
        Math.min(
          (viewport.clientWidth - 90) / (right - left),
          (viewport.clientHeight - 90) / (bottom - top),
        ),
      ),
    );
    view.x =
      (viewport.clientWidth - (right - left) * view.scale) / 2 -
      left * view.scale;
    view.y =
      (viewport.clientHeight - (bottom - top) * view.scale) / 2 -
      top * view.scale;
    transform();
    persist();
  }
  async function metadata() {
    if (adapterData) return adapterData;
    if (!metadataPromise)
      metadataPromise = (async () => {
        const result = await query("list_adapters");
        const adapters = result.results.filter((a) => a.available);
        for (const adapter of adapters) {
          let offset = 0;
          while (true) {
            const page = await query("list_recipe_maps", {
              adapter: adapter.adapter,
              offset,
              limit: 100,
            });
            for (const map of page.results)
              maps.set(`${adapter.adapter}:${map.id}`, map);
            if (!page.hasMore) break;
            offset += 100;
          }
        }
        adapterData = adapters;
        return adapters;
      })().finally(() => {
        metadataPromise = null;
      });
    return metadataPromise;
  }
  function binding(presentation) {
    return presentation
      ? JSON.stringify([presentation.handler, presentation.overlay || ""])
      : null;
  }
  async function expand(node, more = Boolean(node.more)) {
    if (loading.has(node.id) || node.type !== "resource") return;
    if (budgetReached) {
      status(new GraphLimitError().message);
      return;
    }
    if (node.stack.oreDictionary || node.stack.alternatives?.length > 1)
      return chooseVariant(node);
    const current = generation,
      active = graph;
    loading.add(node.id);
    node.message = "Поиск по NEI и адаптерам…";
    render();
    try {
      const adapters = await metadata();
      if (current !== generation) return;
      if (!more || !node.pages)
        node.pages = [
          ...adapters.map((a) => ({
            adapter: a.adapter,
            offset: 0,
            more: true,
          })),
          ...(node.stack.item
            ? [{ adapter: "nei", offset: 0, more: true }]
            : []),
        ];
      const nativeBindings = new Set(node.nativeBindings || []),
        messages = [];
      let added = 0;
      for (const source of node.pages.filter((p) => p.more)) {
        const args = { direction: "output", offset: source.offset, limit: 100 };
        if (node.stack.fluid) args.fluid = node.stack.fluid;
        else {
          args.item = node.stack.item;
          args.metadata = node.stack.metadata || 0;
        }
        if (node.stack.nbt && !node.stack.fluid) args.nbt = node.stack.nbt;
        try {
          const page = await query(
            source.adapter === "nei" ? "get_recipes" : "search_machine_recipes",
            source.adapter === "nei"
              ? args
              : { ...args, adapter: source.adapter },
          );
          if (current !== generation) return;
          for (const raw of page.results) {
            let recipe = raw;
            if (source.adapter === "nei") {
              if (nativeBindings.has(binding(raw.presentation))) continue;
              recipe = neiRecipe(raw);
            } else {
              const map = maps.get(`${source.adapter}:${raw.map}`);
              if (map)
                recipe = {
                  ...raw,
                  presentation: map.presentation || {
                    name: map.name || map.mNEIName || raw.map,
                  },
                };
              if (map?.presentation) {
                nativeBindings.add(binding(map.presentation));
              }
            }
            active.addRecipe(node, recipe, added++);
          }
          source.offset += 100;
          source.more = page.hasMore;
          messages.push(...(page.warnings || []));
        } catch (error) {
          messages.push(`${source.adapter}: ${error.message}`);
          if (error instanceof GraphLimitError) {
            budgetReached = true;
            break;
          }
        }
      }
      if (current !== generation) return;
      node.nativeBindings = [...nativeBindings];
      node.more = node.pages.some((p) => p.more);
      node.expanded = true;
      const producers = [...active.edges.values()].filter(
        (e) => e.to === node.id && active.nodes.get(e.from)?.type === "recipe",
      ).length;
      node.message = producers
        ? `${producers} рецептов${node.more ? " · есть ещё" : ""}`
        : "Рецептов в доступных источниках не найдено";
      if (messages.length) node.message += " · есть предупреждения";
      status(
        messages.length
          ? messages.slice(0, 5).join(" · ")
          : `${active.nodes.size} карточек · ${active.edges.size} связей. Количества указаны на один запуск рецепта.`,
      );
    } catch (error) {
      if (current === generation) {
        node.message = error.message;
        status(error.message);
      }
    } finally {
      loading.delete(node.id);
      if (current === generation) {
        render();
        persist();
      }
    }
  }
  async function chooseVariant(node) {
    const current = generation;
    let offset = 0;
    async function page() {
      try {
        const result = node.stack.oreDictionary
          ? await query("get_oredict", {
              name: node.stack.oreDictionary,
              offset,
              limit: 100,
            })
          : {
              results: (node.stack.alternatives || []).slice(
                offset,
                offset + 100,
              ),
              hasMore: (node.stack.alternatives || []).length > offset + 100,
            };
        if (current !== generation) return;
        dialog(
          "Выберите взаимозаменяемый предмет",
          el("p", {}, "Варианты подходят для одного входа рецепта."),
          ...result.results.map((stack) =>
            el(
              "button",
              {
                class: "board-picker-row",
                onclick: async () => {
                  try {
                    $("dialog").close();
                    const variant = graph.addResource(
                      stack,
                      node.x - 290,
                      node.y,
                    );
                    graph.connect(
                      variant.id,
                      node.id,
                      "один из вариантов",
                      "alternative",
                    );
                    render();
                    persist();
                    await expand(variant);
                    fit();
                  } catch (error) {
                    toast(error.message, true);
                  }
                },
              },
              icon(stack),
              el("span", {}, itemName(stack)),
              el("small", {}, `${stack.item} · ${stack.metadata || 0}`),
            ),
          ),
          ...(result.hasMore
            ? [
                el(
                  "button",
                  {
                    class: "button",
                    onclick: () => {
                      offset += 100;
                      page();
                    },
                  },
                  "Следующие варианты",
                ),
              ]
            : []),
        );
      } catch (error) {
        toast(error.message, true);
      }
    }
    await page();
  }
  async function select(stack) {
    generation++;
    budgetReached = false;
    const current = generation;
    loading.clear();
    graph = new RecipeGraph();
    const root = graph.addResource(stack);
    graph.root = root.id;
    view = { x: $("board-viewport").clientWidth * 0.7, y: 100, scale: 1 };
    render();
    persist();
    await expand(root, false);
    if (current !== generation) return;
    // Start at a readable scale; the explicit Fit action can overview a large graph.
    const viewport = $("board-viewport");
    view.scale = Math.max(0.6, Math.min(1, (viewport.clientWidth - 80) / 1130));
    view.x = viewport.clientWidth - 50 - 220 * view.scale - root.x * view.scale;
    view.y = 35 - root.y * view.scale;
    transform();
    persist();
  }
  function picker() {
    const input = el("input", {
      type: "search",
      placeholder: "Название или registry ID",
      "aria-label": "Предмет для схемы",
    });
    const results = el("div", { class: "board-picker-results" }),
      summary = el("p", {}, "Поиск…");
    let offset = 0,
      timer;
    const previous = el(
      "button",
      {
        class: "button quiet",
        onclick: () => {
          offset = Math.max(0, offset - 20);
          search();
        },
      },
      "←",
    );
    const next = el(
      "button",
      {
        class: "button quiet",
        onclick: () => {
          offset += 20;
          search();
        },
      },
      "→",
    );
    async function search() {
      const sequence = ++pickerSequence;
      try {
        const page = await query("search_items", {
          query: input.value,
          offset,
          limit: 20,
        });
        if (
          sequence !== pickerSequence ||
          !$("dialog").open ||
          !input.isConnected
        )
          return;
        summary.textContent = `${page.total} предметов · ${Math.floor(offset / 20) + 1} страница`;
        previous.disabled = offset === 0;
        next.disabled = !page.hasMore;
        results.replaceChildren(
          ...page.results.map((stack) =>
            el(
              "button",
              {
                class: "board-picker-row",
                onclick: () => {
                  $("dialog").close();
                  select(stack);
                },
              },
              icon(stack),
              el("span", {}, itemName(stack)),
              el("small", {}, `${stack.item} · ${stack.metadata || 0}`),
            ),
          ),
        );
        if (!page.results.length)
          results.append(el("p", {}, "Предметы не найдены"));
      } catch (error) {
        if (sequence === pickerSequence) summary.textContent = error.message;
      }
    }
    input.oninput = () => {
      clearTimeout(timer);
      offset = 0;
      timer = setTimeout(search, 250);
    };
    dialog(
      "Выберите предмет из игры",
      input,
      summary,
      results,
      el("div", { class: "dialog-actions" }, previous, next),
    );
    input.focus();
    search();
  }
  async function expandLevels() {
    if (!graph.root) return picker();
    const current = generation;
    $("board-expand-levels").disabled = true;
    try {
      let frontier = [graph.nodes.get(graph.root)],
        seen = new Set();
      for (let depth = 0; depth < Number($("board-depth").value); depth++) {
        const next = [];
        for (const node of frontier) {
          if (current !== generation || budgetReached) return;
          if (seen.has(node.id)) continue;
          seen.add(node.id);
          if (node.stack.oreDictionary || node.stack.alternatives?.length > 1)
            continue;
          if (!node.expanded) await expand(node, false);
          if (current !== generation) return;
          for (const edge of graph.edges.values())
            if (
              edge.to === node.id &&
              graph.nodes.get(edge.from)?.type === "recipe"
            ) {
              for (const input of graph.edges.values())
                if (
                  input.to === edge.from &&
                  graph.nodes.get(input.from)?.type === "resource"
                )
                  next.push(graph.nodes.get(input.from));
            }
        }
        frontier = next;
      }
      status(
        `${graph.nodes.size} карточек · группы альтернатив раскрываются выбором конкретного предмета.`,
      );
      fit();
    } finally {
      $("board-expand-levels").disabled = false;
    }
  }
  function zoom(
    factor,
    x = $("board-viewport").clientWidth / 2,
    y = $("board-viewport").clientHeight / 2,
  ) {
    const scale = Math.max(0.12, Math.min(2, view.scale * factor)),
      ratio = scale / view.scale;
    view.x = x - (x - view.x) * ratio;
    view.y = y - (y - view.y) * ratio;
    view.scale = scale;
    transform();
    persist();
  }
  $("board-viewport").addEventListener(
    "wheel",
    (event) => {
      const scroll = event.target.closest(".board-recipe-io");
      if (scroll && scroll.scrollHeight > scroll.clientHeight) return;
      event.preventDefault();
      const rect = $("board-viewport").getBoundingClientRect();
      zoom(
        Math.exp(-event.deltaY * 0.001),
        event.clientX - rect.left,
        event.clientY - rect.top,
      );
    },
    { passive: false },
  );
  $("board-viewport").addEventListener("pointerdown", (event) => {
    if (event.button !== 0 || event.target.closest("button,input,select"))
      return;
    const card = event.target.closest(".board-node");
    if (card && !event.target.closest(".board-node-header")) return;
    const node = card ? graph.nodes.get(card.getAttribute("data-node")) : null;
    drag = {
      id: event.pointerId,
      node,
      card,
      x: event.clientX,
      y: event.clientY,
      originX: node ? node.x : view.x,
      originY: node ? node.y : view.y,
    };
    $("board-viewport").setPointerCapture(event.pointerId);
    event.preventDefault();
  });
  $("board-viewport").addEventListener("pointermove", (event) => {
    if (!drag || drag.id !== event.pointerId) return;
    const dx = event.clientX - drag.x,
      dy = event.clientY - drag.y;
    if (drag.node) {
      drag.node.x = drag.originX + dx / view.scale;
      drag.node.y = drag.originY + dy / view.scale;
      drag.card.style.left = `${drag.node.x}px`;
      drag.card.style.top = `${drag.node.y}px`;
      edges();
    } else {
      view.x = drag.originX + dx;
      view.y = drag.originY + dy;
      transform();
    }
  });
  for (const event of ["pointerup", "pointercancel"])
    $("board-viewport").addEventListener(event, () => {
      drag = null;
      persist();
    });
  $("board-pick").onclick = picker;
  $("board-clear").onclick = () => {
    generation++;
    budgetReached = false;
    loading.clear();
    drag = null;
    graph = new RecipeGraph();
    view = { x: 650, y: 180, scale: 1 };
    render();
    try {
      localStorage.removeItem(STORAGE);
      status("Схема очищена. Выберите предмет, чтобы начать новую.");
    } catch {
      status("Схема очищена, но сохранение в браузере удалить не удалось.");
    }
  };
  $("board-fit").onclick = fit;
  $("board-expand-levels").onclick = expandLevels;
  $("board-zoom-in").onclick = () => zoom(1.2);
  $("board-zoom-out").onclick = () => zoom(1 / 1.2);
  $("board-export").onclick = () => {
    const url = URL.createObjectURL(
      new Blob([JSON.stringify({ ...graph.snapshot(), view }, null, 2)], {
        type: "application/json",
      }),
    );
    const link = el("a", { href: url, download: "recipe-chain.json" });
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  $("board-import").onclick = () => $("board-file").click();
  $("board-file").onchange = async () => {
    const file = $("board-file").files[0];
    if (!file) return;
    try {
      if (file.size > 8 * 1024 * 1024) throw Error("Схема больше 8 МБ");
      const value = JSON.parse(await file.text()),
        restored = RecipeGraph.restore(value);
      generation++;
      budgetReached = false;
      loading.clear();
      graph = restored;
      render();
      fit();
      persist();
      status(
        "Схема импортирована. Данные рецептов сохранены снимком; для актуальных данных выберите предмет заново.",
      );
    } catch (error) {
      toast(error.message, true);
    } finally {
      $("board-file").value = "";
    }
  };
  window.addEventListener("resize", () => {
    if (!$("recipe-board").hidden) edges();
  });
  return {
    select,
    async open() {
      if (!initialized) {
        initialized = true;
        try {
          const saved = localStorage.getItem(STORAGE);
          if (saved) {
            const value = JSON.parse(saved);
            graph = RecipeGraph.restore(value);
            if (
              value.view &&
              [value.view.x, value.view.y, value.view.scale].every(
                Number.isFinite,
              ) &&
              value.view.scale >= 0.12 &&
              value.view.scale <= 2
            )
              view = value.view;
            status(
              "Восстановлена сохранённая схема. Для актуальных рецептов выберите предмет заново.",
            );
          }
        } catch {
          status("Сохранённая схема повреждена. Выберите предмет заново.");
        }
      }
      render();
      try {
        await metadata();
        render();
      } catch (error) {
        status(error.message);
      }
    },
  };
}
