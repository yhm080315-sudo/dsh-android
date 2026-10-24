package com.dsh.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Zero-install input channel: an in-app AccessibilityService that injects taps,
 * swipes and text and can read the active window's node tree.
 *
 * Why this exists: `input tap` needs uid 2000 (Shizuku) or uid 0 (root). The
 * accessibility API needs neither -- the user flips one system switch and the
 * app can dispatch gestures and set text directly. It is the only channel that
 * works on a phone with nothing installed, so it is first in the input routing
 * table (see DeviceShell).
 *
 * What it deliberately cannot do: screenshots. An AccessibilityService has no
 * framebuffer access; capture goes through MediaProjection instead
 * (ScreenProjection). `capture()` therefore answers UNSUPPORTED, never a silent
 * empty PNG.
 *
 * Threading: every entry point here is called from the device-bridge thread and
 * blocks it, never the main thread. dispatchGesture() is asynchronous, so the
 * caller waits on a latch that the gesture callback counts down.
 */
public class DshAccessibilityService extends AccessibilityService {

    private static final String TAG = "DSH";

    /** Set while the system has this service bound; null otherwise. */
    private static volatile DshAccessibilityService instance;

    /** How long a gesture may take before the caller is told it timed out. */
    private static final long GESTURE_TIMEOUT_MS = 5000;

    /** A tap is a degenerate path; 60 ms is short enough to never feel like a press. */
    private static final long TAP_MS = 60;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        DeviceShell.onAccessibilityChanged("accessibility: service connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        DeviceShell.onAccessibilityChanged("accessibility: service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        DeviceShell.onAccessibilityChanged("accessibility: service destroyed");
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Events are not needed: every operation here is pull-based. Overriding is
        // still required, and the config asks for no event types at all.
    }

    @Override
    public void onInterrupt() {
    }

    // --------------------------------------------------------------- status

    static boolean connected() {
        return instance != null;
    }

    /** Where the user turns this on, quoted verbatim in errors and the console. */
    static String enablePath() {
        return "设置 → 无障碍 → 已安装的应用 → DeepSeek Harness（或在无障碍里搜 DeepSeek Harness）";
    }

    /** Opens the system accessibility list. Must be called from an Activity. */
    static android.content.Intent settingsIntent() {
        return new android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    // -------------------------------------------------------------- outcome

    /**
     * Result of one accessibility operation.
     *
     * `unsupported` is the important flag: it means "this channel cannot serve
     * this operation at all" (a screenshot, a keycode that has no global action,
     * no focused text field) as opposed to "it tried and failed". DeviceShell
     * falls through to the next channel only for unsupported, so a text field
     * that is missing here can still be served by `input text` when root exists.
     */
    static final class Outcome {
        final boolean ok;
        final boolean unsupported;
        final String message;

        private Outcome(boolean ok, boolean unsupported, String message) {
            this.ok = ok;
            this.unsupported = unsupported;
            this.message = message;
        }

        static Outcome ok() {
            return new Outcome(true, false, "");
        }

        static Outcome failed(String message) {
            return new Outcome(false, false, message);
        }

        static Outcome unsupported(String message) {
            return new Outcome(false, true, message);
        }
    }

    // -------------------------------------------------------------- gestures

    static Outcome tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        return dispatch(path, TAP_MS, "tap " + x + "," + y);
    }

    static Outcome swipe(int x1, int y1, int x2, int y2, int ms) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        return dispatch(path, Math.max(1, ms), "swipe " + x1 + "," + y1 + " -> " + x2 + "," + y2);
    }

    private static Outcome dispatch(Path path, long durationMs, String what) {
        DshAccessibilityService service = instance;
        if (service == null) {
            return Outcome.failed("accessibility service is not enabled（" + enablePath() + "）");
        }
        GestureDescription.Builder builder = new GestureDescription.Builder();
        try {
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
        } catch (Throwable t) {
            // A zero-length path on some OEM builds is rejected here, not at dispatch.
            return Outcome.failed("cannot build gesture for " + what + ": " + t);
        }
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] completed = new boolean[1];
        GestureDescription gesture = builder.build();
        AccessibilityService.GestureResultCallback callback =
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription description) {
                        completed[0] = true;
                        done.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription description) {
                        completed[0] = false;
                        done.countDown();
                    }
                };
        boolean accepted;
        try {
            accepted = service.dispatchGesture(gesture, callback, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            return Outcome.failed("dispatchGesture threw for " + what + ": " + t);
        }
        if (!accepted) {
            return Outcome.failed("dispatchGesture refused " + what
                    + " (service not ready or another gesture in flight)");
        }
        try {
            if (!done.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Outcome.failed("gesture timed out: " + what);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.failed("gesture interrupted: " + what);
        }
        if (!completed[0]) {
            return Outcome.failed("gesture cancelled by the system: " + what);
        }
        return Outcome.ok();
    }

    // ------------------------------------------------------------------ text

    /**
     * Write `value` into the focused editable field with ACTION_SET_TEXT.
     *
     * Unlike `input text` this takes Unicode directly -- no ASCII-only limit and
     * no IME needed -- which is most of the reason to prefer this channel. When
     * no editable node has focus the answer is unsupported (not failed) so a
     * rooted phone can still fall back to `input text`.
     */
    static Outcome text(String value) {
        DshAccessibilityService service = instance;
        if (service == null) {
            return Outcome.failed("accessibility service is not enabled（" + enablePath() + "）");
        }
        if (value == null || value.isEmpty()) {
            return Outcome.unsupported("no text to write");
        }
        AccessibilityNodeInfo root;
        try {
            root = service.getRootInActiveWindow();
        } catch (Throwable t) {
            return Outcome.failed("cannot read the active window: " + t);
        }
        if (root == null) {
            return Outcome.unsupported("no active window to type into");
        }
        AccessibilityNodeInfo target = null;
        try {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        } catch (Throwable ignored) {
        }
        if (target == null || !target.isEditable()) {
            AccessibilityNodeInfo editable = findEditable(root, 400);
            if (editable != null) {
                target = editable;
            }
        }
        if (target == null) {
            return Outcome.unsupported("no editable field on screen");
        }
        if (!target.isEditable()) {
            return Outcome.unsupported("focused node is not editable ("
                    + target.getClassName() + ")");
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean performed;
        try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            performed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Throwable t) {
            return Outcome.failed("ACTION_SET_TEXT threw: " + t);
        }
        if (!performed) {
            return Outcome.failed("ACTION_SET_TEXT was rejected by "
                    + target.getPackageName() + "/" + target.getClassName());
        }
        // Read the field back: a WebView-hosted controlled input (React and friends)
        // accepts the action, returns true, and then throws the value away on the
        // next render -- measured on this very app's own chat box. Reporting success
        // there would be a lie, and it would stop the shell channel from typing it,
        // so an unchanged field counts as unsupported and falls through.
        CharSequence after = null;
        try {
            target.refresh();
            after = target.getText();
        } catch (Throwable ignored) {
        }
        if (after != null && !after.toString().equals(value)) {
            return Outcome.unsupported("ACTION_SET_TEXT 没落到字段里（当前值「"
                    + trim(after) + "」，WebView/React 输入框常见）；交给下一条通道");
        }
        return Outcome.ok();
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root, int budget) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < budget) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            if (node == null) {
                continue;
            }
            if (node.isEditable() && node.isVisibleToUser()) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- keys

    /**
     * Only global actions exist here: an AccessibilityService cannot inject raw
     * key events (that needs INJECT_EVENTS). Anything without a global action is
     * reported unsupported so the shell channel can handle it.
     */
    static Outcome key(String keycode) {
        DshAccessibilityService service = instance;
        if (service == null) {
            return Outcome.failed("accessibility service is not enabled（" + enablePath() + "）");
        }
        if (keycode == null) {
            return Outcome.unsupported("no key code");
        }
        int action;
        String upper = keycode.trim().toUpperCase();
        if (upper.equals("KEYCODE_BACK") || upper.equals("4")) {
            action = AccessibilityService.GLOBAL_ACTION_BACK;
        } else if (upper.equals("KEYCODE_HOME") || upper.equals("3")) {
            action = AccessibilityService.GLOBAL_ACTION_HOME;
        } else if (upper.equals("KEYCODE_APP_SWITCH") || upper.equals("KEYCODE_RECENTS")
                || upper.equals("187")) {
            action = AccessibilityService.GLOBAL_ACTION_RECENTS;
        } else if (upper.equals("KEYCODE_NOTIFICATION") || upper.equals("83")) {
            action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
        } else if (upper.equals("KEYCODE_QUICK_SETTINGS")) {
            action = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
        } else if (upper.equals("KEYCODE_LOCK_SCREEN") && Build.VERSION.SDK_INT >= 28) {
            action = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
        } else if (upper.equals("KEYCODE_POWER") && Build.VERSION.SDK_INT >= 30) {
            // No INJECT_EVENTS; the power *dialog* is the closest real behaviour.
            action = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
        } else {
            return Outcome.unsupported("no accessibility global action for " + keycode);
        }
        boolean performed;
        try {
            performed = service.performGlobalAction(action);
        } catch (Throwable t) {
            return Outcome.failed("performGlobalAction threw: " + t);
        }
        if (!performed) {
            return Outcome.failed("performGlobalAction(" + action + ") was refused");
        }
        return Outcome.ok();
    }

    // ---------------------------------------------------------------- ui dump

    /**
     * A readable summary of the active window, not the raw tree.
     *
     * Screenshots are the wrong tool for "what is on screen" (they need consent
     * and OCR); the node tree has the labels already. The dump is capped in
     * nodes, depth and text length so a chat list cannot turn into 40 KB.
     */
    static Outcome uiSummary(int maxNodes) {
        DshAccessibilityService service = instance;
        if (service == null) {
            return Outcome.failed("accessibility service is not enabled（" + enablePath() + "）");
        }
        AccessibilityNodeInfo root;
        try {
            root = service.getRootInActiveWindow();
        } catch (Throwable t) {
            return Outcome.failed("cannot read the active window: " + t);
        }
        if (root == null) {
            return Outcome.failed("no active window");
        }
        StringBuilder sb = new StringBuilder(4096);
        sb.append("ui: ").append(nullToDash(root.getPackageName() == null ? null
                : root.getPackageName().toString())).append(" | ")
                .append(root.getWindowId() == -1 ? "window=?" : "window=" + root.getWindowId())
                .append(" | ").append(root.getChildCount()).append(" top-level children\n");
        int limit = maxNodes <= 0 ? 60 : Math.min(maxNodes, 400);
        ArrayDeque<Object[]> queue = new ArrayDeque<Object[]>();
        queue.add(new Object[]{root, 0});
        int visited = 0;
        int shown = 0;
        boolean truncated = false;
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            AccessibilityNodeInfo node = (AccessibilityNodeInfo) item[0];
            int depth = (Integer) item[1];
            if (node == null) {
                continue;
            }
            visited++;
            if (visited > limit * 6) {
                truncated = true;
                break;
            }
            if (node.isVisibleToUser() && shown < limit) {
                appendNode(sb, node, depth);
                shown++;
            } else if (node.isVisibleToUser()) {
                truncated = true;
            }
            if (depth >= 14) {
                continue;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(new Object[]{child, depth + 1});
                }
            }
        }
        sb.append("nodes: visited=").append(visited).append(" shown=").append(shown)
                .append(truncated ? " (truncated, raise ?max=)" : "").append('\n');
        return new Outcome(true, false, sb.toString());
    }

    private static void appendNode(StringBuilder sb, AccessibilityNodeInfo node, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        String className = node.getClassName() == null ? null : node.getClassName().toString();
        sb.append('[').append(strip(className)).append(']');
        String text = trim(node.getText());
        if (text.length() > 0) {
            sb.append(" text=\"").append(text).append('"');
        }
        String desc = trim(node.getContentDescription());
        if (desc.length() > 0) {
            sb.append(" desc=\"").append(desc).append('"');
        }
        String id = node.getViewIdResourceName();
        if (id != null && id.length() > 0) {
            sb.append(" id=").append(id);
        }
        if (node.isEditable()) {
            sb.append(" editable");
        }
        if (node.isFocused()) {
            sb.append(" focused");
        }
        if (node.isClickable()) {
            sb.append(" clickable");
        }
        if (node.isScrollable()) {
            sb.append(" scrollable");
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        sb.append(" bounds=").append(rect.left).append(',').append(rect.top).append('-')
                .append(rect.right).append(',').append(rect.bottom).append('\n');
    }

    private static String strip(String value) {
        if (value == null || value.length() == 0) {
            return "?";
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 && dot + 1 < value.length() ? value.substring(dot + 1) : value;
    }

    private static String trim(CharSequence value) {
        if (value == null) {
            return "";
        }
        String text = value.toString().replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }
}
