package com.dsh.android;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stepwise probe of the bundled runtime.
 *
 * The first on-device run showed the harness dying with exit 159 (= 128 + 31,
 * SIGSYS) roughly zero seconds after launch, with no output at all. That could
 * mean anything from "node never actually exec'd" to "glibc made a syscall the
 * app sandbox refuses", so this runs the pieces separately and records exactly
 * where it stops:
 *
 *   1. bundled bash, trivial command      -> does exec work at all for us
 *   2. bundled bash, print LD_LIBRARY_PATH -> did the environment arrive
 *   3. node -v                            -> can node start and print
 *   4. node -e console.log                -> can node run JS
 *   5. the harness entry, real args       -> the actual launch
 *
 * Every step's stdout/stderr/exit code lands in the step list and in dsh.log.
 */
final class RuntimeProbe {

    private static final String TAG = "DSH";
    private static final int STEP_TIMEOUT_MS = 90000;

    static final class Step {
        final String name;
        final String command;
        String stdout = "";
        String stderr = "";
        String exit = "?";
        long elapsedMs;

        Step(String name, String command) {
            this.name = name;
            this.command = command;
        }

        boolean ok() {
            return "0".equals(exit) && stdout.length() > 0;
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(ok() ? "[ok]   " : "[FAIL] ").append(name).append('\n');
            sb.append("       cmd: ").append(command).append('\n');
            sb.append("       exit=").append(exit).append(" (").append(elapsedMs).append("ms)\n");
            if (stdout.length() > 0) {
                sb.append("       out: ")
                        .append(stdout.replace("\n", "\n            ").trim()).append('\n');
            }
            if (stderr.length() > 0) {
                sb.append("       err: ")
                        .append(stderr.replace("\n", "\n            ").trim()).append('\n');
            }
            return sb.toString();
        }
    }

    private final File runtimeDir;
    private final File binDir;
    private final File libDir;
    private final File prefixDir;
    private final File homeDir;
    private final File tmpDir;
    private final File dshEntry;

    RuntimeProbe(File runtimeDir) {
        this.runtimeDir = runtimeDir;
        this.binDir = new File(runtimeDir, "usr/bin");
        this.libDir = new File(runtimeDir, "usr/lib");
        this.prefixDir = new File(runtimeDir, "usr");
        this.homeDir = new File(runtimeDir, "home");
        this.tmpDir = new File(runtimeDir, "tmp");
        this.dshEntry = new File(runtimeDir,
                "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js");
    }

    /**
     * Exercise each piece of the bundled runtime separately, so a failure points
     * at one of them rather than "the harness did not start".
     *
     * bionic is the libc the sandbox actually accepts: glibc dies with SIGSYS
     * while it registers rseq, and a musl Node starts but cannot execute
     * JavaScript. The syscall matrix is kept as the diagnostic of last resort.
     */
    List<Step> runAll() {
        List<Step> steps = new ArrayList<Step>();
        File probeDir = new File(runtimeDir, "probe");
        String[] listed = probeDir.list();
        steps.add(note("probe dir " + probeDir + " -> "
                + (listed == null ? "missing" : java.util.Arrays.toString(listed))));

        File nodeExe = new File(binDir, "node");
        File bashExe = new File(binDir, "bash");
        File rgExe = new File(binDir, "rg");

        if (nodeExe.isFile()) {
            steps.add(run("node: version", new String[]{nodeExe.getAbsolutePath(), "--version"}));
            steps.add(run("node: run javascript", new String[]{nodeExe.getAbsolutePath(), "-e",
                    "console.log('NODE_JS_OK ' + process.version + ' ' + process.platform)"}));
            steps.add(run("node: load a module", new String[]{nodeExe.getAbsolutePath(), "-e",
                    "require('node:fs'); console.log('REQUIRE_OK')"}));
        }
        if (bashExe.isFile()) {
            steps.add(run("bash: trivial command",
                    new String[]{bashExe.getAbsolutePath(), "-c", "echo BASH_OK"}));
        }
        if (rgExe.isFile()) {
            steps.add(run("rg: version", new String[]{rgExe.getAbsolutePath(), "--version"}));
        }

        steps.add(run("harness: dsh --version",
                new String[]{nodeExe.getAbsolutePath(), dshEntry.getAbsolutePath(), "--version"}));

        File sys = new File(probeDir, "tsys.bin");
        if (sys.isFile()) {
            steps.add(run("syscall matrix", new String[]{sys.getAbsolutePath(), "all"}));
        }

        // Is anything from the environment reaching the child at all?
        steps.add(run("environment sanity", new String[]{"/system/bin/sh", "-c",
                "echo SH_OK; echo PATH=$PATH; id"}));
        return steps;
    }

    /** A step that only records an observation, no subprocess. */
    private Step note(String text) {
        Step step = new Step("note", text);
        step.stdout = text;
        step.exit = "0";
        return step;
    }

    private Step run(String name, String[] cmd) {
        Step step = new Step(name, join(cmd));
        Process p = null;
        long t0 = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(homeDir);
            Map<String, String> env = pb.environment();
            NodeServer.applyRuntimeEnv(env, prefixDir, binDir, homeDir, tmpDir);

            p = pb.start();
            final Process proc = p;
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            Thread to = pump(proc.getInputStream(), out);
            Thread te = pump(proc.getErrorStream(), err);
            boolean finished = waitFor(proc, STEP_TIMEOUT_MS);
            if (!finished) {
                proc.destroy();
                step.exit = "timeout";
            } else {
                step.exit = String.valueOf(proc.exitValue());
            }
            to.join(2000);
            te.join(2000);
            step.stdout = out.toString();
            step.stderr = err.toString();
        } catch (Throwable t) {
            step.exit = "exception";
            step.stderr = String.valueOf(t);
            Log.w(TAG, "probe step failed: " + name, t);
        } finally {
            if (p != null) {
                p.destroy();
            }
            step.elapsedMs = System.currentTimeMillis() - t0;
        }
        Log.i(TAG, step.render());
        return step;
    }

    private static Thread pump(final InputStream in, final StringBuilder sink) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String line;
                    int lines = 0;
                    while ((line = r.readLine()) != null && lines < 200) {
                        sink.append(line).append('\n');
                        lines++;
                    }
                } catch (Exception ignored) {
                    // process went away; whatever was captured stands
                } finally {
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "probe-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static boolean waitFor(Process p, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException running) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static String join(String[] cmd) {
        StringBuilder sb = new StringBuilder();
        for (String c : cmd) {
            sb.append(c).append(' ');
        }
        return sb.toString().trim();
    }

}
