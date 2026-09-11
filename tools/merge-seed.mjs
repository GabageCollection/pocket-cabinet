// 合并工具：把 tools/new-ingredients.mjs 与 tools/new-recipes.mjs 并入 assets/seed，并按方法生成默认步骤。
import { readFileSync, writeFileSync } from "fs";
import { newIngredients } from "./new-ingredients.mjs";
import { newRecipes } from "./new-recipes.mjs";

const dir = "app/src/main/assets/seed/";
const ingJson = JSON.parse(readFileSync(dir + "ingredients.json", "utf8"));
const recJson = JSON.parse(readFileSync(dir + "recipes.json", "utf8"));

/* ── 材料合并 ── */
const existingIng = new Set(ingJson.ingredients.map(i => i.id));
for (const [id, zh, en, cat, dim, unit, abv, aliases] of newIngredients) {
  if (existingIng.has(id)) throw new Error("重复材料 " + id);
  ingJson.ingredients.push({ id, zh, en, cat, dim, unit, abv, aliases });
}

/* ── 步骤模板 ── */
function defaultSteps(method, ings) {
  const needs = ings.filter(i => i[3] === "required" && i[1] > 0 && i[0] !== "ice")
    .map(i => [i[0], i[1], i[2]]);
  const garnish = ings.find(i => i[3] === "garnish" && i[1] > 0);
  if (method === "摇和") return [
    { t: "备料与冰杯", d: "杯子加冰静置降温，材料称量备齐。", need: [], timer: 0, vis: 0 },
    { t: "摇和", d: "全部材料入摇壶，加冰用力摇和。", need: needs, timer: 12, timerLabel: "摇和 10–12 秒", vis: 0 },
    { t: "滤出装杯", d: "滤掉冰块倒入杯中" + (garnish ? "，装饰后即可享用" : "即可"), need: garnish ? [[garnish[0], garnish[1], garnish[2]]] : [], timer: 0, vis: 0 }
  ];
  if (method === "搅拌") return [
    { t: "备料与冰杯", d: "杯子加冰静置降温，材料称量备齐。", need: [], timer: 0, vis: 0 },
    { t: "搅拌", d: "全部材料入搅拌杯，加冰搅拌至充分降温。", need: needs, timer: 20, timerLabel: "搅拌约 20 秒", vis: 0 },
    { t: "滤出装杯", d: "滤入杯中" + (garnish ? "，装饰后即可享用" : "即可"), need: garnish ? [[garnish[0], garnish[1], garnish[2]]] : [], timer: 0, vis: 0 }
  ];
  if (method === "捣压") return [
    { t: "捣压", d: "新鲜材料入杯轻捣出汁出香。", need: needs, timer: 0, vis: 0 },
    { t: "组合", d: "加入其余材料与冰。", need: [], timer: 0, vis: 0 },
    { t: "装杯", d: "拌匀" + (garnish ? "，装饰后即可享用" : "即可"), need: garnish ? [[garnish[0], garnish[1], garnish[2]]] : [], timer: 0, vis: 0 }
  ];
  if (method === "分层") return [
    { t: "分层", d: "按密度从大到小，沿吧匙背面缓缓注入，形成分层。", need: needs, timer: 0, vis: 0 },
    { t: "完成", d: "保持分层，上桌时再搅匀或分层饮用。", need: [], timer: 0, vis: 0 }
  ];
  /* 直调 */
  return [
    { t: "冰杯", d: "杯中加冰。", need: [], timer: 0, vis: 0 },
    { t: "组合", d: "依次倒入全部材料。", need: needs, timer: 0, vis: 0 },
    { t: "调和装杯", d: "吧匙轻提拌匀" + (garnish ? "，装饰后即可享用" : "即可"), need: garnish ? [[garnish[0], garnish[1], garnish[2]]] : [], timer: 0, vis: 0 }
  ];
}

/* ── 配方合并 ── */
const existingRec = new Set(recJson.recipes.map(r => r.id));
const seen = new Set();
for (const row of newRecipes) {
  const [id, zh, en, source, sourceNote, flavors, difficulty, method, glass, glassZh, liquid, abv, timeMin, ings, customSteps] = row;
  if (existingRec.has(id) || seen.has(id)) throw new Error("重复配方 " + id);
  seen.add(id);
  const ingredients = ings.map(([ing, qty, unit, role, freeOrNote]) => {
    const o = { ing, qty, unit, role };
    if (typeof freeOrNote === "string" && qty === 0) o.freeText = freeOrNote;
    else if (typeof freeOrNote === "string") o.note = freeOrNote;
    return o;
  });
  const steps = (customSteps || defaultSteps(method, ings)).map(s => Array.isArray(s)
    ? { t: s[0], d: s[1], need: s[2] || [], timer: s[3] || 0, timerLabel: s[4] || undefined, vis: 0 }
    : s);
  recJson.recipes.push({
    id, zh, en, source, sourceNote, flavors, difficulty, method, glass, glassZh, liquid, abv, timeMin,
    ingredients, steps
  });
}

writeFileSync(dir + "ingredients.json", JSON.stringify(ingJson, null, 2) + "\n");
writeFileSync(dir + "recipes.json", JSON.stringify(recJson, null, 2) + "\n");
console.log("ingredients:", ingJson.ingredients.length, "recipes:", recJson.recipes.length);
