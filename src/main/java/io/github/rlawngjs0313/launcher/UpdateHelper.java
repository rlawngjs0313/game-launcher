package io.github.rlawngjs0313.launcher;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs outside the installed runtime while the EXE installer replaces application files. */
public final class UpdateHelper {
    private static final String APPLICATION_ID = "rlawngjs0313.game-launcher";
    private UpdateHelper() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected update job path");
        Path job = Path.of(args[0]).toRealPath(), work = job.getParent();
        Properties config = load(job);
        try (var writer = Files.newBufferedWriter(work.resolve("update.log"))) {
            PrintWriter log = new PrintWriter(writer, true);
            Files.writeString(work.resolve("worker.pid"), Long.toString(ProcessHandle.current().pid()));
            Path root = Path.of(required(config, "installation")).toAbsolutePath().normalize();
            Path installer = Path.of(required(config, "installer")).toAbsolutePath().normalize();
            Path rollback = Path.of(required(config, "rollbackInstaller")).toAbsolutePath().normalize();
            Path receipt = work.resolve("ready.marker");
            String next = required(config, "version"), current = required(config, "currentVersion");
            long parentPid = Long.parseLong(required(config, "parentPid"));
            boolean installationStarted = false;
            Process child = null;
            try {
                validate(root, current);
                if (work.startsWith(root)) throw new IOException("Update worker must run outside installation");
                Path cache = work.getParent().getParent().resolve("installer-cache");
                verifyInstaller(installer, cache, required(config, "installerSha256"));
                verifyInstaller(rollback, cache, required(config, "rollbackSha256"));
                Files.writeString(work.resolve("worker.ready"), "ready");
                Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);
                if (parent.isPresent() && parent.get().isAlive() && parent.get().info().startInstant().map(t -> t.toString().equals(config.getProperty("parentStartedAt"))).orElse(true)) {
                    log.println("Waiting for launcher shutdown"); parent.get().onExit().get(120, TimeUnit.SECONDS);
                }
                Files.deleteIfExists(receipt);
                installationStarted = true;
                runInstaller(installer, root, work.resolve("install.log"));
                validate(root, next);
                child = start(root, "--update-receipt=" + receipt, work.resolve("launcher.log"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                boolean ready = false;
                while (System.nanoTime() < deadline) {
                    if (Files.isRegularFile(receipt) && Files.readString(receipt).strip().equals(next)) { ready = true; break; }
                    if (!child.isAlive()) break;
                    Thread.sleep(250);
                }
                if (!ready) throw new IOException("New launcher did not report a ready window");
                log.println("Update successful: " + current + " -> " + next);
                Files.writeString(work.resolve("completed"), "SUCCESS");
            } catch (Exception ex) {
                ex.printStackTrace(log);
                if (!installationStarted && ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) {
                    log.println("Launcher still running; installation unchanged");
                    Files.writeString(work.resolve("completed"), "CANCELLED"); return;
                }
                if (ex instanceof InstallerBusyException) {
                    log.println("Installer is still active; no overlapping rollback was started");
                    Files.writeString(work.resolve("completed"), "FAILED"); return;
                }
                if (child != null && child.isAlive()) {
                    child.destroy();
                    if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
                    if (child.isAlive()) { log.println("New launcher still running; rollback deferred"); Files.writeString(work.resolve("completed"), "FAILED"); return; }
                }
                try {
                    if (installationStarted) runInstaller(rollback, root, work.resolve("rollback.log"));
                    validate(root, current);
                    start(root, "--skip-update-check", work.resolve("launcher.log"));
                    log.println("Previous version restored with its installer");
                } catch (Exception recovery) { recovery.printStackTrace(log); log.println("Manual recovery installer: " + rollback); }
                Files.writeString(work.resolve("completed"), "FAILED");
            }
        }
    }
    private static void verifyInstaller(Path file, Path cache, String expected) throws Exception {
        if (!file.getParent().toRealPath().equals(cache.toRealPath()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !expected.matches("[a-fA-F0-9]{64}")) throw new IOException("Invalid installer path");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) { if (in.read() != 'M' || in.read() != 'Z') throw new IOException("Not a Windows executable"); }
        try (InputStream in = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n); }
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected)) throw new IOException("Installer checksum mismatch");
    }
    private static void runInstaller(Path installer, Path root, Path log) throws Exception {
        Process process = new ProcessBuilder(installer.toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/SP-", "/NORESTART", "/RESTARTEXITCODE=3010", "/NOCLOSEAPPLICATIONS", "/NORESTARTAPPLICATIONS", "/DIR=" + root, "/LOG=" + log)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            List<ProcessHandle> children = process.descendants().toList();
            children.forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS);
            if (process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) throw new InstallerBusyException();
            throw new IOException("Installer timed out");
        }
        if (process.exitValue() != 0) throw new IOException("Installer exit code " + process.exitValue() + "; see " + log);
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
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
    private static final class InstallerBusyException extends IOException { InstallerBusyException() { super("Installer did not terminate"); } }
}
