#!/usr/bin/env node
/*
 * P34 harness —— 不靠猜，直接把**已落盘**的两段逻辑抽出来跑：
 *   1) deliverables/lib/client.js 里那句 `reveal: host === ... ? ... : host.fileManager !== "android"`
 *      （原样抽出来 new Function 编译，host 换不同 fileManager 喂进去）
 *   2) open-in-app/lib/client.js 里整个 OpenTargetButton 函数（原样抽出来，
 *      用 React/jsx-runtime/primitives 的最小替身渲染成结构树，看它到底渲染了什么）
 * 然后看：菜单项、footer 的 reveal、chevron、主按钮文案、点一下走 open 还是 reveal。
 *
 * 纯前端逻辑，不需要真机。
 *
 * 用法（容器里用 bionic node 跑，跟手机上是同一个引擎）：
 *   D=/tmp/deb/rootfs/data/data/com.termux/files/usr
 *   LD_LIBRARY_PATH=$D/lib $D/bin/node tools/p34-reveal-harness.js [node_modules 路径]
 */
"use strict";

const fs = require("node:fs");
const path = require("node:path");

const ROOT =
  process.argv[2] || process.env.P34_ROOT || "/root/v39work/payload/dsh/node_modules";
const DEL = path.join(ROOT, "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js");
const OIA = path.join(ROOT, "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js");

const delText = fs.readFileSync(DEL, "utf8");
const oiaText = fs.readFileSync(OIA, "utf8");

function must(cond, msg) {
  if (!cond) {
    console.error("HARNESS FAIL: " + msg);
    process.exit(1);
  }
}

/* ---------- 1) 卡片侧的判据：从 deliverables 里原样抽出来 ---------- */
const revealLine = delText
  .split("\n")
  .find((line) => /^\s*reveal: host === null \|\| host === "error"/.test(line));
must(revealLine, "deliverables 里找不到 P34 的 reveal: 行");
const revealExpr = revealLine.trim().replace(/^reveal:\s*/, "").replace(/,\s*$/, "");
console.log("抽出（deliverables）: reveal = " + revealExpr + "\n");
const decideReveal = new Function("host", "return (" + revealExpr + ");");

/* ---------- 2) 渲染侧的 OpenTargetButton：整函数抽出来 ---------- */
const start = oiaText.indexOf("\t\tfunction OpenTargetButton(props) {");
must(start >= 0, "open-in-app 里找不到 OpenTargetButton");
const endMark = "\n\t\t}\n\t\t//#endregion";
const end = oiaText.indexOf(endMark, start);
must(end > start, "找不到 OpenTargetButton 的结尾");
const buttonSrc = oiaText.slice(start, end + "\n\t\t}".length);

/* 操作 -> 会话动作 的映射，也从 FileRouteAction 里原样抽（桌面/安卓同一条线） */
const mapMatch = oiaText.match(/operation\.kind === "reveal" \? "reveal" : "open"/);
must(mapMatch, "找不到 operation -> action 的映射表达式");
const opToAction = new Function("operation", "return (" + mapMatch[0] + ");");

/* ---------- 最小替身 ---------- */
const executed = [];
const actions = [];
const stubExecute = (operation) => {
  executed.push(operation);
  return Promise.resolve(null);
};

function element(type, props) {
  return { type, props: props || {} };
}
const Fragment = Symbol("Fragment");
const jsxRuntime = {
  Fragment,
  jsx: element,
  jsxs: element,
  jsxDEV: element,
};

const primitives = new Proxy(
  {},
  {
    get: (_t, name) => {
      if (name === "Menu" || name === "Tooltip") return (props) => element(String(name), props);
      return (props) => element(String(name), props);
    },
  },
);

const react = {
  useState: (init) => [init, () => {}],
  useEffect: () => {},
  useRef: (v) => ({ current: v }),
  useMemo: (fn) => fn(),
  useCallback: (fn) => fn,
};

const cssDefault = new Proxy({}, { get: (_t, name) => String(name) });

/* 语言包：值必须真的来自包里的中文词典 */
const zhDict = {};
for (const m of oiaText.matchAll(/^\t{3}"([a-zA-Z.]+)": "([^"]*)",$/gm)) {
  if (!(m[1] in zhDict)) zhDict[m[1]] = m[2];
}
const t = (key, vars) => {
  let out = zhDict[key];
  must(out !== undefined, "中文词典里没有 " + key);
  for (const [k, v] of Object.entries(vars || {})) out = out.split("{" + k + "}").join(String(v));
  return out;
};

const factory = new Function(
  "react",
  "react_jsx_runtime",
  "_deepseek_ai_dsh_client_ui_primitives",
  "OpenTargetButton_module_css_default",
  "useOpenTargetGesture",
  "ApplicationIcon",
  "t",
  buttonSrc + "\n\t\treturn OpenTargetButton;",
);

const useOpenTargetGesture = (execute) => ({
  pending: false,
  toast: null,
  act: (operation) => {
    actions.length = 0;
    return execute(operation);
  },
});

const applicationIcon = (props) => element("ApplicationIcon", props);
const OpenTargetButton = factory(
  react,
  jsxRuntime,
  primitives,
  cssDefault,
  useOpenTargetGesture,
  applicationIcon,
  t,
);

/* ---------- 3) 场景 ---------- */
function renderCard({ fileManager, apps }) {
  const host =
    fileManager === undefined ? null : { name: "android", available: true, fileManager };
  const reveal = decideReveal(host);
  const actionUrl = "/api/presented/file/1/0";
  executed.length = 0;
  actions.length = 0;

  const tree = OpenTargetButton({
    kind: "file",
    reveal,
    applications: apps,
    defaultId: apps.find((a) => a.default)?.id,
    failed: false,
    loading: false,
    busy: false,
    refresh: () => {},
    t,
    execute: (operation) => {
      actions.push(opToAction(operation));
      return stubExecute(opToAction(operation));
    },
  });

  const [menuEl] = tree.props.children;
  const menu = menuEl.props;
  const anchor = menu.anchor;
  const anchorKids = [].concat(anchor.props.children).filter(Boolean);
  const tooltip = anchorKids[0];
  const mainButton = tooltip.props.children;
  const chevron = anchorKids[1];

  const items = (menu.items || []).map((item) => item.label);
  const footer = (menu.footer || []).map((item) => item.id + ":" + item.label);

  mainButton.props.onClick();
  const primaryOps = actions.slice();

  return {
    revealFlag: reveal,
    items,
    footer,
    chevron: Boolean(chevron),
    mainLabel: mainButton.props["aria-label"],
    mainText: [].concat(mainButton.props.children).filter((x) => typeof x === "string"),
    primaryOps,
    revealRendered: footer.some((f) => f.startsWith("reveal:")) || items.some((i) => i.includes("显示文件位置")),
  };
}

const APP = [{ id: "app.markor", name: "Markor", default: true, icon: null }];
const scenarios = [
  ["android + 无可用应用（现场）", { fileManager: "android", apps: [] }],
  ["android + 有一个应用", { fileManager: "android", apps: APP }],
  ["finder (macOS) + 无应用", { fileManager: "finder", apps: [] }],
  ["explorer (Windows) + 无应用", { fileManager: "explorer", apps: [] }],
  ["directory (Linux) + 无应用", { fileManager: "directory", apps: [] }],
  ["fileManager=null + 无应用", { fileManager: null, apps: [] }],
];

let failures = 0;
function expect(label, got, want, note) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) failures += 1;
  console.log(
    "  " + (ok ? "PASS" : "FAIL") + "  " + label + " = " + JSON.stringify(got) + (ok ? "" : "   期望 " + JSON.stringify(want)) + (note ? "   " + note : ""),
  );
}

for (const [name, scenario] of scenarios) {
  const out = renderCard(scenario);
  console.log("== " + name);
  console.log("   host.fileManager = " + JSON.stringify(scenario.fileManager) + "  →  reveal 标记 = " + out.revealFlag);
  console.log("   渲染出的动作：菜单项 " + JSON.stringify(out.items) + " / footer " + JSON.stringify(out.footer) + " / chevron " + out.chevron);
  console.log("   主按钮：aria-label=" + JSON.stringify(out.mainLabel) + " 文字=" + JSON.stringify(out.mainText) + "  点一下 → " + JSON.stringify(out.primaryOps));
  console.log("   「显示文件位置」是否出现在卡片上：" + (out.revealRendered ? "是" : "否"));

  if (scenario.fileManager === "android") {
    expect("reveal 不出现", out.revealRendered, false);
    expect("footer 里没有 reveal", out.footer.filter((f) => f.startsWith("reveal:")), []);
    expect("主按钮不点 reveal", out.primaryOps.includes("reveal"), false);
    expect("主按钮落到 open", out.primaryOps, ["open"]);
    if (scenario.apps.length === 0) {
      expect("主按钮文案", out.mainLabel, "打开");
      expect("没有可展开的菜单", out.chevron, false);
    } else {
      expect("应用条目照常渲染", out.items, ["Markor（默认）"]);
    }
  } else {
    expect("footer 里有 reveal", out.footer.map((f) => f.split(":")[0]), ["reveal"]);
    expect("reveal 文案", out.footer[0].includes("显示文件位置"), true);
    expect("主按钮点 reveal", out.primaryOps, ["reveal"]);
    expect("主按钮文案", out.mainLabel, "显示文件位置");
  }
  console.log();
}

/* ---------- 4) 结构断言：只隐藏 reveal，别的渲染路径没被改 ---------- */
console.log("== 结构断言（其它调用方不受影响）");
const otherCallSites = [
  ["FileOpenTarget（文档头）", /applications: apps,\s*\n\s*defaultId: apps\.find/],
  ["OpenPathEmptyAction（不可预览文件）", /function OpenPathEmptyAction\(props\) \{[\s\S]{0,200}?empty: true/],
];
for (const [label, re] of otherCallSites) {
  const ok = re.test(oiaText);
  if (!ok) failures += 1;
  console.log("  " + (ok ? "PASS" : "FAIL") + "  " + label + " 仍然不传 reveal（默认 = 保留 reveal）");
}

const guards = [
  ["只有卡片动作传 reveal 标记", (oiaText.match(/reveal: props\.reveal !== false,/g) || []).length === 1],
  ["footer 条件带 revealOffered", oiaText.includes('footer: kind === "file" && revealOffered ? [{')],
  ["应用条目数组没有被改", oiaText.includes("items: [...applications.map((app) => ({\n\t\t\t\t\tid: `app:${app.id}`,")],
  ["列表失败重试项没有被改", oiaText.includes('id: "unavailable",')],
  ["主按钮的 open 回落仍在", oiaText.includes('run({ kind: "default" });')],
  ["置灰/disabled 逻辑没有被拿来当修法", !oiaText.includes("revealDisabled")],
];
for (const [label, ok] of guards) {
  if (!ok) failures += 1;
  console.log("  " + (ok ? "PASS" : "FAIL") + "  " + label);
}

console.log();
if (failures > 0) {
  console.log("结果：" + failures + " 项不符");
  process.exit(1);
}
console.log("结果：全部通过（android 上不渲染 reveal，其它平台照旧；只挡 reveal 一项）");
