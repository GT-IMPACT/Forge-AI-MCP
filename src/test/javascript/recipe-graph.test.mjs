import test from "node:test";
import assert from "node:assert/strict";
import {
  RecipeGraph,
  resourceKey,
  neiRecipe,
  MAX_NODES,
} from "../../main/resources/assets/forgeaimcp/editor/graph.js";

const item = (id, count = 1, extra = {}) => ({
  item: "test:" + id,
  metadata: 0,
  count,
  ...extra,
});
const recipe = (id, inputs, outputs) => ({
  id,
  adapter: "test",
  map: "machine",
  itemInputs: inputs,
  itemOutputs: outputs,
});

test("shares item identity but keeps each slot quantity and output chance", () => {
  const graph = new RecipeGraph(),
    target = graph.addResource(item("out"));
  graph.addRecipe(
    target,
    recipe(
      "first",
      [item("in", 2), item("in", 4)],
      [item("out", 3, { chance: 2500 })],
    ),
  );
  graph.addRecipe(target, recipe("second", [item("in", 8)], [item("out")]));
  assert.equal(
    [...graph.nodes.values()].filter((n) => n.type === "resource").length,
    2,
  );
  assert.deepEqual(
    [...graph.edges.values()]
      .filter((e) => e.role === "input")
      .map((e) => e.label),
    ["×2", "×4", "×8"],
  );
  assert.ok([...graph.edges.values()].some((e) => e.label === "×3 · 25%"));
});
test("metadata, NBT, fluids and alternative groups remain distinct", () => {
  const keys = [
    item("x"),
    item("x", 8, { metadata: 1 }),
    item("x", 1, { nbt: "{mode:1}" }),
    { fluid: "x" },
    { oreDictionary: "oreX", alternatives: [item("x"), item("y")] },
  ].map(resourceKey);
  assert.equal(new Set(keys).size, keys.length);
  assert.equal(resourceKey(item("x")), resourceKey(item("x", 16)));
  assert.equal(
    resourceKey({ alternatives: [item("x"), item("y")] }),
    resourceKey({ alternatives: [item("y"), item("x")] }),
  );
});
test("detects recycling cycles and does not duplicate repeated expansion", () => {
  const graph = new RecipeGraph(),
    a = graph.addResource(item("a"));
  const r = recipe("a-from-b", [item("b")], [item("a")]);
  graph.addRecipe(a, r);
  graph.addRecipe(a, r);
  const b = graph.nodes.get("resource:" + resourceKey(item("b")));
  graph.addRecipe(b, recipe("b-from-a", [item("a")], [item("b")]));
  assert.equal(graph.nodes.size, 4);
  assert.equal(graph.edges.size, 4);
  assert.ok([...graph.edges.values()].some((e) => e.cycle));
});
test("export/import preserves positions, edge identity and cycles", () => {
  const graph = new RecipeGraph(),
    a = graph.addResource(item("a"), -520, 123);
  graph.root = a.id;
  graph.addRecipe(a, recipe("recycle", [item("a", 0)], [item("a")]));
  const restored = RecipeGraph.restore(
    JSON.parse(JSON.stringify(graph.snapshot())),
  );
  assert.deepEqual(restored.snapshot(), graph.snapshot());
  restored.addRecipe(
    restored.nodes.get(a.id),
    recipe("recycle", [item("a", 0)], [item("a")]),
  );
  assert.equal(restored.edges.size, graph.edges.size);
  assert.throws(() =>
    RecipeGraph.restore({ ...graph.snapshot(), root: "missing" }),
  );
});
test("node budget rejects a branch atomically", () => {
  const graph = new RecipeGraph();
  for (let i = 0; i < MAX_NODES - 1; i++) graph.addResource(item(String(i)));
  const target = [...graph.nodes.values()][0];
  assert.throws(() =>
    graph.addRecipe(target, recipe("too-large", [item("new")], [target.stack])),
  );
  assert.equal(graph.nodes.size, MAX_NODES - 1);
  assert.equal(graph.edges.size, 0);
});
test("generic NEI keeps alternatives and auxiliary stacks without inventing fluid or energy semantics", () => {
  const raw = {
    handler: "mod.handler",
    presentation: { overlay: "press" },
    ingredients: [{ alternatives: [item("a"), item("b")] }],
    result: { alternatives: [item("c")] },
    otherStacks: [{ alternatives: [item("fuel")] }],
  };
  const value = neiRecipe(raw);
  assert.equal(value.itemInputs[0].alternatives.length, 2);
  assert.equal(value.otherStacks.length, 1);
  assert.equal(value.complete, false);
  assert.equal(value.eut, undefined);
  assert.deepEqual(value.fluidInputs, []);
  assert.notEqual(
    value.id,
    neiRecipe({ ...raw, presentation: { overlay: "other" } }).id,
  );
});
