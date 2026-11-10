package io.github.rlawngjs0313.launcher;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class LauncherUpdater {
    record Release(String version, String tag, String notes, long size, String installerUrl, String checksumUrl) {}
    private static final long MAX_ARCHIVE = 1024L * 1024 * 1024;
    private final Path data;
    LauncherUpdater(Path data) { this.data = data; }

    Release latest() throws Exception {
        URI uri = URI.create("https://api.github.com/repos/" + BuildInfo.REPOSITORY + "/releases/latest");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "GameLauncher/" + BuildInfo.VERSION).header("Accept", "application/vnd.github+json").build();
        var response = Net.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw new IOException("릴리즈 확인 HTTP " + response.statusCode());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.get("draft").getAsBoolean() || json.get("prerelease").getAsBoolean()) return null;
        String tag = json.get("tag_name").getAsString(), version = BuildInfo.stableVersion(tag);
        if (BuildInfo.compare(version, BuildInfo.VERSION) <= 0) return null;
        return parseRelease(json);
    }
    private static Release parseRelease(JsonObject json) throws IOException {
        String tag = json.get("tag_name").getAsString(), version = BuildInfo.stableVersion(tag);
        String archiveName = "GameLauncher-" + version + "-windows-x64-setup.exe";
        JsonObject archive = null, checksum = null;
        for (JsonElement value : json.getAsJsonArray("assets")) {
            JsonObject asset = value.getAsJsonObject();
            if (archiveName.equals(Net.str(asset, "name", ""))) archive = asset;
            if ((archiveName + ".sha256").equals(Net.str(asset, "name", ""))) checksum = asset;
        }
        if (archive == null || checksum == null) throw new IOException("릴리즈에 Windows 설치 EXE 또는 체크섬이 없습니다.");
        long size = archive.get("size").getAsLong();
        if (size < 1 || size > MAX_ARCHIVE) throw new IOException("업데이트 파일 크기가 올바르지 않습니다.");
        String archiveUrl = assetUrl(archive, tag, archiveName), checksumUrl = assetUrl(checksum, tag, archiveName + ".sha256");
        String notes = Net.str(json, "body", "");
        return new Release(version, tag, notes.length() > 6000 ? notes.substring(0, 6000) : notes, size, archiveUrl, checksumUrl);
    }
    private static String assetUrl(JsonObject asset, String tag, String name) throws IOException {
        URI uri = URI.create(asset.get("browser_download_url").getAsString());
        String expected = "/" + BuildInfo.REPOSITORY + "/releases/download/" + tag + "/" + name;
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || !expected.equals(uri.getPath()) || uri.getQuery() != null) throw new IOException("예상하지 못한 릴리즈 다운로드 주소");
        return uri.toString();
    }

    void install(Release release, Consumer<String> progress) throws Exception {
        Path installation = BuildInfo.installation();
        if (data.toAbsolutePath().normalize().startsWith(installation)) throw new IOException("게임 데이터는 런처 실행 파일 폴더 밖에 있어야 합니다.");
        cleanupOldJobs();
        Path jobs = data.resolve("updates"); Files.createDirectories(jobs);
        Path job = Files.createTempDirectory(jobs, "update-");
        Process worker = null;
        boolean handedOff = false;
        try {
            Path probe;
            try { probe = Files.createTempFile(installation, ".update-write-", ".tmp"); }
            catch (AccessDeniedException ex) { throw new IOException("런처 설치 폴더를 수정할 권한이 없습니다. 사용자별 설치 EXE로 다시 설치하세요.", ex); }
            Files.delete(probe);
            // Use the current release's installer for rollback, including uninstall registry state.
            progress.accept("현재 버전 복구 설치 파일 확인");
            Release previous = parseRelease(Net.json("https://api.github.com/repos/" + BuildInfo.REPOSITORY + "/releases/tags/v" + BuildInfo.VERSION));
            if (!previous.version().equals(BuildInfo.VERSION)) throw new IOException("복구 설치 파일 버전 불일치");
            String previousHash = checksum(previous);
            Path rollbackInstaller = downloadInstaller(previous, previousHash, progress);
            String nextHash = checksum(release);
            Path nextInstaller = downloadInstaller(release, nextHash, progress);
            Path runtime = installation.resolve("runtime"), workerRuntime = job.resolve("runtime");
            if (!Files.isRegularFile(runtime.resolve("bin/java.exe"))) throw new IOException("자동 업데이트 도우미용 Java가 없습니다. 최신 설치 EXE를 직접 실행하세요.");
            progress.accept("업데이트 도우미 준비");
            copyTree(runtime, workerRuntime);
            Path launcherJar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(launcherJar)) throw new IOException("릴리즈 앱에서만 자동 업데이트할 수 있습니다.");
            Path helperJar = job.resolve("updater.jar"); Files.copy(launcherJar, helperJar);
            Properties config = new Properties();
            config.setProperty("installation", installation.toString());
            config.setProperty("installer", nextInstaller.toString()); config.setProperty("installerSha256", nextHash);
            config.setProperty("rollbackInstaller", rollbackInstaller.toString()); config.setProperty("rollbackSha256", previousHash);
            config.setProperty("version", release.version()); config.setProperty("currentVersion", BuildInfo.VERSION);
            config.setProperty("parentPid", Long.toString(ProcessHandle.current().pid()));
            config.setProperty("parentStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString());
            Path configPath = job.resolve("job.properties"); try (OutputStream out = Files.newOutputStream(configPath)) { config.store(out, "Game Launcher update"); }
            Net.checkCancelled();
            worker = new ProcessBuilder(workerRuntime.resolve("bin/java.exe").toString(), "-cp", helperJar.toString(), UpdateHelper.class.getName(), configPath.toString())
                .directory(job.toFile()).redirectErrorStream(true).redirectOutput(job.resolve("worker.log").toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.isRegularFile(job.resolve("worker.ready"))) {
                if (!worker.isAlive() || System.nanoTime() >= deadline) throw new IOException("업데이트 도우미 시작 실패. " + job.resolve("worker.log"));
                Thread.sleep(100);
            }
            Net.checkCancelled();
            handedOff = true;
            progress.accept("업데이트를 적용하기 위해 런처를 재시작합니다.");
        } finally {
            if (!handedOff) {
                if (worker != null && worker.isAlive()) {
                    worker.destroyForcibly();
                    boolean interrupted = Thread.interrupted();
                    try { worker.waitFor(10, TimeUnit.SECONDS); }
                    finally { if (interrupted) Thread.currentThread().interrupt(); }
                }
                if (Files.exists(job.resolve("worker.log"))) {
                    UpdateHelper.deleteTree(job.resolve("runtime"));
                    Files.deleteIfExists(job.resolve("updater.jar"));
                } else UpdateHelper.deleteTree(job);
            }
        }
    }
    private static String checksum(Release release) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(release.checksumUrl())).timeout(Duration.ofSeconds(30)).build();
        var response = Net.DOWNLOADS.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String result;
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) throw new IOException("체크섬 다운로드 HTTP " + response.statusCode());
            byte[] bytes = in.readNBytes(4097); if (bytes.length > 4096) throw new IOException("체크섬 크기 제한 초과");
            result = new String(bytes, StandardCharsets.UTF_8).strip().split("\\s+")[0];
        }
        if (!result.matches("[a-fA-F0-9]{64}")) throw new IOException("잘못된 SHA-256 체크섬");
        return result;
    }
    private Path downloadInstaller(Release release, String checksum, Consumer<String> progress) throws Exception {
        progress.accept("설치 파일 확인 및 다운로드: " + release.version());
        Path file = data.resolve("installer-cache").resolve("GameLauncher-" + release.version() + "-windows-x64-setup.exe");
        Net.download(release.installerUrl(), file, "SHA-256", checksum, release.size());
        if (Files.size(file) != release.size()) throw new IOException("설치 파일 크기 불일치");
        return file;
    }
    private static void copyTree(Path source, Path destination) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Net.checkCancelled();
                if (Files.isSymbolicLink(file)) throw new IOException("런타임에 예상하지 못한 링크가 있습니다.");
                Path target = destination.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target);
            }
        }
    }
    void cleanupOldJobs() {
        Path directory = data.resolve("updates"); if (!Files.isDirectory(directory)) return;
        boolean workerActive = false;
        try (var jobs = Files.list(directory)) {
            for (Path job : jobs.filter(p -> p.getFileName().toString().startsWith("update-") && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (Files.isRegularFile(job.resolve("worker.pid"))) {
                    long pid = Long.parseLong(Files.readString(job.resolve("worker.pid")).strip());
                    if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) { workerActive = true; continue; }
                }
                if (!Files.isRegularFile(job.resolve("completed"))) continue;
                String result = Files.readString(job.resolve("completed")).strip();
                if (!result.equals("SUCCESS")) continue; // Keep failure logs for diagnosis.
                UpdateHelper.deleteTree(job);
            }
            Path cache = data.resolve("installer-cache");
            if (!workerActive && Files.isDirectory(cache)) try (var files = Files.list(cache)) {
                String current = "GameLauncher-" + BuildInfo.VERSION + "-windows-x64-setup.exe";
                for (Path file : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    String name = file.getFileName().toString();
                    if (name.matches("GameLauncher-[0-9]+\\.[0-9]+\\.[0-9]+-windows-x64-setup\\.exe") && !name.equals(current)) Files.delete(file);
                }
            }
        } catch (Exception ignored) { /* Cleanup is best effort; it must not block launching. */ }
    }
}
