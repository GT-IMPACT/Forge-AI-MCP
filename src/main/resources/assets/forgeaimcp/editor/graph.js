// Pure graph data: shared items are merged; quantities belong to recipe edges.
export const MAX_NODES = 300;
export class GraphLimitError extends Error {
  constructor() {
    super(`Схема достигла лимита ${MAX_NODES} карточек. Начните новую схему.`);
  }
}
export function resourceKey(stack) {
  if (stack.oreDictionary || stack.alternatives?.length > 1) {
    return JSON.stringify([
      "group",
      stack.oreDictionary || "",
      [...new Set((stack.alternatives || []).map(resourceKey))].sort(),
    ]);
  }
  if (stack.fluid)
    return JSON.stringify(["fluid", stack.fluid, stack.nbt || null]);
  if (stack.item)
    return JSON.stringify([
      "item",
      stack.item,
      stack.metadata || 0,
      stack.nbt || null,
    ]);
  if (stack.alternatives?.length === 1)
    return resourceKey(stack.alternatives[0]);
  throw Error("Нет данных об ингредиенте");
}
export function neiRecipe(recipe) {
  const slot = (value) => {
    const alternatives = (value?.alternatives || []).filter((v) => v?.item);
    return alternatives.length
      ? {
          ...alternatives[0],
          ...(alternatives.length > 1 ? { alternatives } : {}),
          alternativesTruncated: value.alternativesTruncated,
        }
      : null;
  };
  const result = slot(recipe.result);
  const inputs = (recipe.ingredients || []).map(slot).filter(Boolean);
  return {
    id:
      "nei:" +
      JSON.stringify([
        recipe.handler,
        recipe.presentation?.overlay || "",
        inputs,
        result,
        recipe.otherStacks,
      ]),
    adapter: "nei",
    map: recipe.handler,
    kind: "nei",
    presentation: recipe.presentation || {
      name: recipe.category,
      handler: recipe.handler,
    },
    itemInputs: inputs,
    itemOutputs: result ? [result] : [],
    fluidInputs: [],
    fluidOutputs: [],
    otherStacks: recipe.otherStacks || [],
    complete: false,
    warnings: [
      "NEI: показанные предметы. Время, энергия, жидкости и назначение дополнительных слотов требуют адаптера.",
    ],
  };
}
export function inputsOf(recipe) {
  return [
    ...(recipe.itemInputs || []).map((stack, index) => {
      const alternatives = recipe.inputAlternatives?.[index];
      return stack && alternatives?.length ? { ...stack, alternatives } : stack;
    }),
    ...(recipe.fluidInputs || []),
  ].filter(Boolean);
}
export function outputsOf(recipe) {
  return [...(recipe.itemOutputs || []), ...(recipe.fluidOutputs || [])].filter(
    Boolean,
  );
}
export function quantity(stack) {
  if (stack.fluid) return `${stack.amount} mB`;
  if (stack.consumed === false || stack.count === 0) return "не расходуется";
  const chance =
    stack.chance != null && stack.chance !== 10000
      ? ` · ${(stack.chance / (stack.chanceScale || 10000)) * 100}%`
      : "";
  return `×${stack.count ?? 1}${chance}`;
}
export class RecipeGraph {
  constructor() {
    this.nodes = new Map();
    this.edges = new Map();
    this.outgoing = new Map();
    this.root = null;
  }
  addResource(stack, x = 0, y = 0) {
    const id = "resource:" + resourceKey(stack);
    if (!this.nodes.has(id)) {
      if (this.nodes.size >= MAX_NODES) throw new GraphLimitError();
      while (
        [...this.nodes.values()].some(
          (node) => node.x === x && Math.abs(node.y - y) < 135,
        )
      )
        y += 145;
      this.nodes.set(id, {
        id,
        type: "resource",
        stack: JSON.parse(JSON.stringify(stack)),
        x,
        y,
        expanded: false,
      });
    }
    return this.nodes.get(id);
  }
  reachable(from, to) {
    const seen = new Set(),
      pending = [from];
    while (pending.length) {
      const id = pending.pop();
      if (id === to) return true;
      if (seen.has(id)) continue;
      seen.add(id);
      for (const next of this.outgoing.get(id) || []) pending.push(next);
    }
    return false;
  }
  connect(from, to, label = "", role = "", index = 0) {
    const id = JSON.stringify([from, to, role, index]);
    if (!this.edges.has(id)) {
      this.edges.set(id, {
        id,
        from,
        to,
        label,
        role,
        index,
        cycle: this.reachable(to, from),
      });
      if (!this.outgoing.has(from)) this.outgoing.set(from, new Set());
      this.outgoing.get(from).add(to);
    }
  }
  addRecipe(target, recipe, index = 0) {
    const id = "recipe:" + recipe.id;
    let node = this.nodes.get(id);
    const inputs = inputsOf(recipe),
      outputs = outputsOf(recipe);
    const missing = new Set(
      [...inputs, ...outputs]
        .map((s) => "resource:" + resourceKey(s))
        .filter((key) => !this.nodes.has(key)),
    );
    if (this.nodes.size + missing.size + (node ? 0 : 1) > MAX_NODES)
      throw new GraphLimitError();
    if (!node) {
      node = {
        id,
        type: "recipe",
        recipe,
        x: target.x - 370,
        y: target.y + index * 450,
      };
      // Keep new alternatives clear of recipes already placed in this column.
      while (
        [...this.nodes.values()].some(
          (n) => n.x === node.x && Math.abs(n.y - node.y) < 430,
        )
      )
        node.y += 450;
      this.nodes.set(id, node);
    }
    inputs.forEach((stack, i) => {
      const input = this.addResource(
        stack,
        node.x - 290 - (i % 2) * 250,
        node.y + Math.floor(i / 2) * 145,
      );
      this.connect(input.id, node.id, quantity(stack), "input", i);
    });
    outputs.forEach((stack, i) => {
      const output = this.addResource(stack, target.x, node.y + i * 145);
      this.connect(node.id, output.id, quantity(stack), "output", i);
    });
    // A wildcard/alternative displayed by NEI can represent the specifically requested item.
    if (!outputs.some((s) => resourceKey(s) === resourceKey(target.stack)))
      this.connect(
        node.id,
        target.id,
        outputs.length ? "вариант результата" : "запрошенный результат",
        "requested",
      );
    return node;
  }
  snapshot() {
    return {
      schemaVersion: 1,
      root: this.root,
      nodes: [...this.nodes.values()],
      edges: [...this.edges.values()],
    };
  }
  static restore(value) {
    if (
      value?.schemaVersion !== 1 ||
      !Array.isArray(value.nodes) ||
      value.nodes.length > MAX_NODES ||
      !Array.isArray(value.edges) ||
      value.edges.length > 5000
    )
      throw Error("Неверный файл схемы");
    const graph = new RecipeGraph();
    for (const node of value.nodes) {
      if (
        !node ||
        typeof node.id !== "string" ||
        graph.nodes.has(node.id) ||
        !Number.isFinite(node.x) ||
        !Number.isFinite(node.y) ||
        Math.abs(node.x) > 1e6 ||
        Math.abs(node.y) > 1e6
      )
        throw Error("Неверная карточка");
      if (node.type === "resource") {
        if (node.id !== "resource:" + resourceKey(node.stack))
          throw Error("Неверный предмет");
      } else if (node.type === "recipe") {
        if (
          !node.recipe ||
          typeof node.recipe.id !== "string" ||
          node.id !== "recipe:" + node.recipe.id
        )
          throw Error("Неверный рецепт");
        inputsOf(node.recipe).forEach(resourceKey);
        outputsOf(node.recipe).forEach(resourceKey);
      } else throw Error("Неверный тип карточки");
      graph.nodes.set(node.id, JSON.parse(JSON.stringify(node)));
    }
    for (const edge of value.edges) {
      if (
        !graph.nodes.has(edge.from) ||
        !graph.nodes.has(edge.to) ||
        typeof edge.label !== "string"
      )
        throw Error("Неверная связь");
      graph.connect(
        edge.from,
        edge.to,
        edge.label,
        edge.role || "",
        edge.index ?? 0,
      );
    }
    if (value.root != null && graph.nodes.get(value.root)?.type !== "resource")
      throw Error("Неверный корень схемы");
    graph.root = value.root;
    return graph;
  }
}
