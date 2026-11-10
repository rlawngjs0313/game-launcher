package io.github.rlawngjs0313.launcher;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs from a copied runtime outside the installation being replaced. No JavaFX dependencies. */
public final class UpdateHelper {
    private static final String APPLICATION_ID = "rlawngjs0313.game-launcher";
    private UpdateHelper() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected update job path");
        Path job = Path.of(args[0]).toRealPath();
        Properties config = load(job);
        Path work = job.getParent();
        try (var writer = Files.newBufferedWriter(work.resolve("update.log"))) {
            PrintWriter log = new PrintWriter(writer, true);
            long pid = ProcessHandle.current().pid();
            Files.writeString(work.resolve("worker.pid"), Long.toString(pid));
            Path root = Path.of(required(config, "installation")).toAbsolutePath().normalize();
            Path staged = Path.of(required(config, "staged")).toAbsolutePath().normalize();
            Path receipt = work.resolve("ready.marker");
            String next = required(config, "version"), current = required(config, "currentVersion");
            Path backup = root.resolveSibling(".game-launcher-previous-" + UUID.randomUUID());
            Path failed = root.resolveSibling(".game-launcher-failed-" + UUID.randomUUID());
            boolean oldMoved = false, newMoved = false;
            Process child = null;
            try {
                validate(root, current);
                validate(staged, next);
                if (root.getParent() == null || staged.getParent() == null || !root.getParent().equals(staged.getParent().getParent()) ||
                    !staged.getParent().getFileName().toString().startsWith(".game-launcher-stage-")) throw new IOException("Invalid staging layout");
                if (work.startsWith(root)) throw new IOException("Update worker must run outside the installation");
                Optional<ProcessHandle> parent = ProcessHandle.of(Long.parseLong(required(config, "parentPid")));
                Files.writeString(work.resolve("worker.ready"), "ready");
                if (parent.isPresent() && parent.get().isAlive() && parent.get().info().startInstant().map(t -> t.toString().equals(config.getProperty("parentStartedAt"))).orElse(true)) {
                    log.println("Waiting for launcher shutdown"); parent.get().onExit().get(120, TimeUnit.SECONDS);
                }
                Files.deleteIfExists(receipt);
                moveWithRetry(root, backup); oldMoved = true;
                moveWithRetry(staged, root); newMoved = true;
                validate(root, next);
                child = start(root, "--update-receipt=" + receipt, work.resolve("launcher.log"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                boolean ready = false;
                while (System.nanoTime() < deadline && child.isAlive()) {
                    if (Files.isRegularFile(receipt) && Files.readString(receipt).strip().equals(next)) { ready = true; break; }
                    Thread.sleep(250);
                }
                if (!ready) throw new IOException("New launcher did not report a ready window");
                log.println("Update successful: " + current + " -> " + next);
                Files.writeString(work.resolve("completed"), "SUCCESS");
                try { deleteTree(backup); deleteTree(staged.getParent()); Files.deleteIfExists(work.resolve("release.zip")); }
                catch (IOException ex) { log.println("Cleanup deferred: " + ex.getMessage()); }
            } catch (Exception ex) {
                ex.printStackTrace(log);
                if (!oldMoved && ProcessHandle.of(Long.parseLong(required(config, "parentPid"))).map(ProcessHandle::isAlive).orElse(false)) {
                    log.println("Launcher has not exited; installation left unchanged");
                    Files.writeString(work.resolve("completed"), "CANCELLED");
                    return;
                }
                if (child != null && child.isAlive()) {
                    child.destroy();
                    if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
                }
                try {
                    if (newMoved && Files.exists(root)) moveWithRetry(root, failed);
                    if (oldMoved) moveWithRetry(backup, root);
                    validate(root, current);
                    start(root, "--skip-update-check", work.resolve("launcher.log"));
                    log.println("Previous launcher restored");
                    if (Files.exists(failed)) deleteTree(failed);
                } catch (Exception recovery) { recovery.printStackTrace(log); log.println("Restore the previous application folder from: " + backup); }
                Files.writeString(work.resolve("completed"), "FAILED");
            }
        }
    }
    private static Process start(Path root, String argument, Path log) throws IOException {
        return new ProcessBuilder(root.resolve("GameLauncher.exe").toString(), argument).directory(root.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    }
    private static void validate(Path root, String version) throws IOException {
        Properties marker = load(root.resolve("launcher-release.properties"));
        if (!APPLICATION_ID.equals(marker.getProperty("applicationId")) || !version.equals(marker.getProperty("version")) || !Files.isRegularFile(root.resolve("GameLauncher.exe"))) throw new IOException("Application marker mismatch: " + root);
    }
    private static Properties load(Path path) throws IOException { Properties p = new Properties(); try (InputStream in = Files.newInputStream(path)) { p.load(in); } return p; }
    private static String required(Properties p, String key) throws IOException { String value = p.getProperty(key); if (value == null || value.isBlank()) throw new IOException("Missing update field: " + key); return value; }
    private static void moveWithRetry(Path from, Path to) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (true) {
            try { Files.move(from, to); return; }
            catch (IOException ex) { if (System.nanoTime() >= deadline) throw ex; Thread.sleep(500); }
        }
    }
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
}
