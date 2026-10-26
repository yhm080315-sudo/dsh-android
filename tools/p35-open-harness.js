#!/usr/bin/env node
/*
 * P35c 的接线证据：Android 上的「打开 / 显示位置」到底会执行什么，失败时留下什么。
 *
 * 用 platform 注入而不是改 process.platform：native-command 的每个入口都接受
 * internals.platform / internals.run，这里把它们换成受控的假值，跑的是**真实**
 * 分派代码（不是抄一份逻辑）。
 *
 *   node tools/p35-open-harness.js /root/v39work/payload/dsh/node_modules
 *
 * 退出码 0 = 全部断言通过。
 */
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const root = process.argv[2];
if (root === undefined) {
    console.error("usage: node tools/p35-open-harness.js <node_modules>");
    process.exit(2);
}

const entry = join(root, "@deepseek-ai/dsh-native-command/lib/index.js");
const native = await import(pathToFileURL(entry).href);
const { openNativeAssociatedPath, openNativePath, revealNativePath, nativeFileApplications } = native;

let failures = 0;
const ok = (condition, label, detail = "") => {
    console.log(`  ${condition ? "OK  " : "FAIL"} ${label}${detail === "" ? "" : "  — " + detail}`);
    if (!condition) failures += 1;
};

/** A runner that records argv and then behaves as told. */
function recorder(outcome) {
    const calls = [];
    const run = (command, args) => {
        calls.push([command, [...args]]);
        return outcome === undefined ? Promise.resolve({ stdout: "", stderr: "" }) : Promise.reject(outcome);
    };
    return { calls, run };
}

const signal = new AbortController().signal;

const closed = (code, stderr) => Object.assign(new Error(`Command failed: dsh-open\n${stderr}`), { code, stderr });

console.log("== 1) Android 打开：落到 usr/bin/dsh-open ==");
{
    const { calls, run } = recorder();
    await openNativePath("/storage/emulated/0/DSH/a.md", signal, { platform: "android", run });
    ok(calls.length === 1 && calls[0][0] === "dsh-open" && calls[0][1][0] === "/storage/emulated/0/DSH/a.md",
        "open 在 Android 上执行 dsh-open <path>", JSON.stringify(calls));
}
{
    const { calls, run } = recorder();
    await openNativeAssociatedPath("/storage/emulated/0/DSH/a.md", signal, { platform: "android", run });
    ok(calls.length === 1 && calls[0][0] === "dsh-open", "卡片那条（associated）同样落到 dsh-open", JSON.stringify(calls));
}
{
    const { calls, run } = recorder();
    await revealNativePath("/storage/emulated/0/DSH/a.md", signal, { platform: "android", run });
    ok(calls.length === 1 && calls[0][0] === "dsh-open", "reveal 在 Android 上也落到 dsh-open（P34 之后不再渲染）", JSON.stringify(calls));
}

console.log("== 2) 失败时留下「哪一步」==");
for (const [code, stage, note] of [
    [4, "missing", "路径对 App 不可见"],
    [5, "token", "没有 device-token"],
    [6, "bridge", "设备桥拒绝"],
    [7, "bridge", "HTTP 调用失败"],
    [1, "launcher", "dsh-open 自己出错"],
    ["ENOENT", "launcher", "dsh-open 不在 PATH"],
]) {
    try {
        await openNativePath("/storage/emulated/0/DSH/a.md", signal, {
            platform: "android",
            run: recorder(closed(code, "dsh-open: 演示用 stderr\n第二行")).run,
        });
        ok(false, `exit ${code} 必须抛出（${note}）`);
    } catch (error) {
        ok(error.androidStage === stage, `exit ${code} → stage=${stage}`,
            `androidStage=${String(error.androidStage)} / message=${error.message}`);
    }
}
{
    try {
        await openNativePath("/storage/emulated/0/DSH/a.md", signal, {
            platform: "android",
            run: recorder(closed(5, "dsh-open: no device token at /data/x/.device-token")).run,
        });
        ok(false, "必须抛出");
    } catch (error) {
        ok(error.message.includes("exit 5") && error.message.includes("no device token"),
            "message 里带退出码和脚本 stderr 的第一行", error.message);
        ok(!error.message.includes("\n"), "message 是单行（不会把 stderr 整个塞进 toast）", error.message);
        ok(error.cause !== undefined, "保留 cause（宿主侧据此取 androidStage）");
    }
}

console.log("== 3) 桌面端一个字没变 ==");
{
    const { calls, run } = recorder();
    await openNativePath("/tmp/a.md", signal, { platform: "darwin", run });
    ok(calls.length === 1 && calls[0][0] === "open" && calls[0][1][0] === "/tmp/a.md",
        "macOS 仍是 open <path>", JSON.stringify(calls));
}
{
    const { calls, run } = recorder();
    await revealNativePath("/tmp/a.md", signal, { platform: "darwin", run });
    ok(calls.length === 1 && calls[0][0] === "open" && calls[0][1][0] === "-R",
        "macOS reveal 仍是 open -R <path>", JSON.stringify(calls));
    ok(calls.every(([command]) => command !== "dsh-open"), "macOS 上不会碰 dsh-open");
}
{
    let error;
    try {
        await openNativePath("/tmp/a.md", signal, { platform: "linux", run: recorder(closed(2, "boom")).run });
    } catch (thrown) {
        error = thrown;
    }
    ok(error !== undefined && error.androidStage === undefined,
        "非 Android 的失败没有 androidStage（宿主侧仍按原样 500）",
        String(error?.message));
}

console.log("== 4) Android 的 applications 列表为空（所以卡片只有「打开」）==");
{
    const apps = await nativeFileApplications("/storage/emulated/0/DSH/a.md", signal, { platform: "android" });
    ok(Array.isArray(apps) && apps.length === 0, "nativeFileApplications 在 Android 上是 []", JSON.stringify(apps));
}

console.log("== 5) 落盘检查（编译产物里的字面量）==");
{
    const oia = await readFile(join(root, "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js"), "utf8");
    ok(oia.includes('reveal: props.reveal !== false && fileManager !== "android",'), "预览页按宿主判据决定 reveal");
    const delivClient = await readFile(join(root, "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js"), "utf8");
    // deliverables 的浏览器侧路由是 PRESENT_HOST_PATH.slice(1)，这里是同一个值的手抄；
    // 抄错前缀（少 "api/"）就会 404，fileManager 恒为 null，P35b 静默失效。
    const hostPath = /const PRESENT_HOST_PATH = "([^"]+)";/.exec(delivClient)?.[1];
    const expectedRoute = hostPath === undefined ? undefined : hostPath.slice(1);
    ok(expectedRoute !== undefined && oia.includes(`const PRESENT_HOST_ROUTE = "${expectedRoute}";`),
        "判据路由与交付卡片逐字一致（" + String(expectedRoute) + "）",
        "缺它就会 404，fileManager 恒为 null，P35b 静默失效");
    ok(oia.includes("openInAppFileManager: paths.fileManager"), "插槽注入 fileManager hook");
    const del = await readFile(join(root, "@deepseek-ai/dsh-client-ui-deliverables/lib/index.js"), "utf8");
    ok(del.includes('step: "android-open"') && del.includes("status: 502"), "宿主侧 502 + 失败步骤");
    ok(del.includes('if (ctx.sessionController.workspaceDesktop().fileManager !== "android") throw error;'),
        "非 Android 的失败原样抛出（桌面端不变）");
    const dcl = await readFile(join(root, "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js"), "utf8");
    ok(dcl.includes('refusal.step === "android-open"'), "客户端读 502 的步骤");
    ok(dcl.includes('phase === "androidOpenFailed" ? "openShareFailed"'), "失败键不再是 openError");
}

console.log();
if (failures > 0) {
    console.log(`FAILED: ${failures} 条断言没过`);
    process.exit(1);
}
console.log("全部断言通过");
