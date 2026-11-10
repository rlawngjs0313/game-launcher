package io.github.rlawngjs0313.launcher;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.zip.*;
import java.time.Duration;

final class GameInstaller {
    private final Path root;
    private final Consumer<String> progress;
    record Installed(Path java, String profile, JsonObject version) {}
    GameInstaller(Path root, Consumer<String> progress) { this.root = root; this.progress = progress; }

    Installed ensure(JsonObject requirement, Models.Settings settings) throws Exception {
        String minecraft = requirement.getAsJsonObject("minecraft").get("version").getAsString();
        if (!minecraft.matches("[A-Za-z0-9._-]+")) throw new IOException("잘못된 Minecraft 버전");
        String type = requirement.getAsJsonObject("loader").get("type").getAsString();
        if (!Set.of("vanilla", "neoforge").contains(type)) throw new IOException("아직 지원하지 않는 로더: " + type);
        Files.createDirectories(root.resolve("versions"));
        progress.accept("Minecraft " + minecraft + " 설치 파일 확인");
        JsonObject manifest = Net.json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        JsonObject entry = null;
        for (JsonElement value : manifest.getAsJsonArray("versions")) if (value.getAsJsonObject().get("id").getAsString().equals(minecraft)) { entry = value.getAsJsonObject(); break; }
        if (entry == null) throw new IOException("공식 배포 목록에 없는 Minecraft 버전: " + minecraft);
        Path versionFile = root.resolve("versions").resolve(minecraft).resolve(minecraft + ".json");
        Net.download(entry.get("url").getAsString(), versionFile, "SHA-1", Net.str(entry, "sha1", ""), "");
        JsonObject base = read(versionFile);
        int javaMajor = base.has("javaVersion") ? base.getAsJsonObject("javaVersion").get("majorVersion").getAsInt() : 8;
        Path java = ensureJava(javaMajor, settings.javaPath);
        JsonObject client = base.getAsJsonObject("downloads").getAsJsonObject("client");
        Net.download(client.get("url").getAsString(), root.resolve("versions").resolve(minecraft).resolve(minecraft + ".jar"), "SHA-1", client.get("sha1").getAsString(), "");
        installLibraries(base);
        installAssets(base);
        String profile = minecraft;
        if (type.equals("neoforge")) {
            String neo = requirement.getAsJsonObject("loader").get("version").getAsString();
            if (!neo.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.-]+)?")) throw new IOException("잘못된 NeoForge 버전");
            Path installer = root.resolve("installers").resolve("neoforge-" + neo + "-installer.jar");
            String url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + neo + "/neoforge-" + neo + "-installer.jar";
            var checksumResponse = Net.DOWNLOADS.send(HttpRequest.newBuilder(URI.create(url + ".sha1")).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            if (checksumResponse.statusCode() != 200) throw new IOException("NeoForge 설치 파일 체크섬 HTTP " + checksumResponse.statusCode());
            String checksum = checksumResponse.body().strip().split("\\s+")[0];
            if (!checksum.matches("[a-fA-F0-9]{40}")) throw new IOException("NeoForge 설치 파일 체크섬을 확인할 수 없습니다.");
            Net.download(url, installer, "SHA-1", checksum, "");
            try (ZipFile zip = new ZipFile(installer.toFile())) {
                var version = zip.getEntry("version.json");
                if (version == null) throw new IOException("NeoForge 설치 파일의 프로필이 없습니다.");
                try (Reader reader = new InputStreamReader(zip.getInputStream(version), StandardCharsets.UTF_8)) { profile = JsonParser.parseReader(reader).getAsJsonObject().get("id").getAsString(); }
            }
            if (!profile.matches("[A-Za-z0-9._-]+")) throw new IOException("잘못된 로더 프로필 ID");
            Path marker = root.resolve("versions").resolve(profile).resolve("launcher-install-complete.json");
            if (!Files.exists(marker)) {
                progress.accept("NeoForge " + neo + " 설치 중");
                Path profiles = root.resolve("launcher_profiles.json");
                if (!Files.exists(profiles)) Store.write(profiles, "{\"profiles\":{}}");
                run(java, List.of("-jar", installer.toString(), "--installClient", root.toString()), root.resolve("neoforge-install.log"));
                if (!Files.isRegularFile(root.resolve("versions").resolve(profile).resolve(profile + ".json"))) throw new IOException("NeoForge 프로필 생성 실패. neoforge-install.log를 확인하세요.");
                Store.write(marker, "{\"neoforge\":\"" + neo + "\"}");
            }
        }
        JsonObject merged = loadProfile(profile, new HashSet<>());
        installLibraries(merged);
        return new Installed(java, profile, merged);
    }

    private Path ensureJava(int major, String configured) throws Exception {
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured).toAbsolutePath();
            checkJava(candidate, major); return candidate;
        }
        String os = os(), arch = arch();
        Path target = root.resolve("runtimes").resolve(os + "-" + arch + "-" + major);
        Path found = findJava(target);
        if (found != null) { checkJava(found, major); return found; }
        // Native Windows ARM64 UI packaging is deferred by the project requirements.
        if (!os.equals("windows")) throw new IOException("자동 Java 설치는 Windows x64 대상입니다. 설정에서 Java " + major + " 실행 파일을 지정하세요.");
        if (!arch.equals("x64")) throw new IOException("현재 배포 대상은 Windows x64입니다. ARM64 네이티브 패키지는 추후 지원합니다.");
        progress.accept("게임용 Java " + major + " 다운로드");
        String endpoint = "https://api.adoptium.net/v3/assets/latest/" + major + "/hotspot?architecture=" + arch + "&image_type=jre&os=" + os;
        JsonArray assets = Net.element(endpoint).getAsJsonArray();
        if (assets.isEmpty()) throw new IOException("해당 플랫폼의 Java 배포 파일이 없습니다.");
        JsonObject pkg = assets.get(0).getAsJsonObject().getAsJsonObject("binary").getAsJsonObject("package");
        Path archive = root.resolve("cache").resolve("jre-" + major + "-" + arch + ".zip");
        Net.download(pkg.get("link").getAsString(), archive, "SHA-256", pkg.get("checksum").getAsString(), "");
        Path staging = root.resolve("runtimes").resolve(".install-" + UUID.randomUUID());
        try {
            unzip(archive, staging, false);
            Path stagedJava = findJava(staging);
            if (stagedJava == null) throw new IOException("Java 실행 파일이 없습니다.");
            checkJava(stagedJava, major);
            if (Files.exists(target)) deleteTree(target);
            Files.move(staging, target);
        } finally { if (Files.exists(staging)) deleteTree(staging); }
        return Objects.requireNonNull(findJava(target));
    }
    private static Path findJava(Path target) throws IOException {
        if (!Files.isDirectory(target)) return null;
        try (var files = Files.walk(target, 4)) { return files.filter(p -> p.getFileName().toString().equals(os().equals("windows") ? "java.exe" : "java") && p.getParent().getFileName().toString().equals("bin")).findFirst().orElse(null); }
    }
    private static void checkJava(Path java, int expected) throws Exception {
        Process process = new ProcessBuilder(java.toString(), "-XshowSettings:properties", "-version").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Java 확인 시간 초과"); }
        Matcher matcher = Pattern.compile("java\\.specification\\.version\\s*=\\s*(?:1\\.)?(\\d+)").matcher(output);
        if (process.exitValue() != 0 || !matcher.find() || Integer.parseInt(matcher.group(1)) != expected) throw new IOException("Java " + expected + " 실행 파일을 지정하세요.");
    }
    private void installLibraries(JsonObject version) throws Exception {
        for (JsonElement value : Net.array(version, "libraries")) {
            Net.checkCancelled(); JsonObject library = value.getAsJsonObject();
            if (!allowed(library, Map.of()) || !nativeMatches(library.get("name").getAsString())) continue;
            if (library.has("downloads")) {
                JsonObject downloads = library.getAsJsonObject("downloads");
                if (downloads.has("artifact")) downloadLibrary(downloads.getAsJsonObject("artifact"));
                if (library.has("natives") && library.getAsJsonObject("natives").has(ruleOs())) {
                    String classifier = library.getAsJsonObject("natives").get(ruleOs()).getAsString().replace("${arch}", "64");
                    if (downloads.has("classifiers") && downloads.getAsJsonObject("classifiers").has(classifier)) downloadLibrary(downloads.getAsJsonObject("classifiers").getAsJsonObject(classifier));
                }
            } else {
                String relative = coordinatePath(library.get("name").getAsString());
                String base = Net.str(library, "url", "https://libraries.minecraft.net/");
                Net.download(base.replaceAll("/$", "") + "/" + relative, root.resolve("libraries").resolve(relative), "SHA-1", "", "");
            }
        }
    }
    private void downloadLibrary(JsonObject artifact) throws Exception {
        Path target = safeResolve(root.resolve("libraries"), artifact.get("path").getAsString());
        Net.download(artifact.get("url").getAsString(), target, "SHA-1", Net.str(artifact, "sha1", ""), "");
    }
    private void installAssets(JsonObject version) throws Exception {
        if (!version.has("assetIndex")) return;
        JsonObject index = version.getAsJsonObject("assetIndex");
        Path target = safeResolve(root.resolve("assets/indexes"), index.get("id").getAsString() + ".json");
        Net.download(index.get("url").getAsString(), target, "SHA-1", index.get("sha1").getAsString(), "");
        JsonObject objects = read(target).getAsJsonObject("objects");
        progress.accept("게임 에셋 확인: " + objects.size() + "개");
        try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (var entry : objects.entrySet()) {
                String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
                if (!hash.matches("[a-f0-9]{40}")) throw new IOException("잘못된 에셋 해시");
                String relative = hash.substring(0, 2) + "/" + hash;
                futures.add(workers.submit(() -> { try { Net.download("https://resources.download.minecraft.net/" + relative, root.resolve("assets/objects").resolve(relative), "SHA-1", hash, ""); } catch (Exception ex) { throw new CompletionException(ex); } }));
            }
            try { for (Future<?> future : futures) { Net.checkCancelled(); future.get(); } }
            catch (Exception ex) { workers.shutdownNow(); throw ex; }
        }
    }
    JsonObject loadProfile(String id, Set<String> chain) throws Exception {
        if (!id.matches("[A-Za-z0-9._-]+") || !chain.add(id)) throw new IOException("잘못된 프로필 상속");
        JsonObject child = read(root.resolve("versions").resolve(id).resolve(id + ".json"));
        if (!child.has("inheritsFrom")) return child;
        JsonObject parent = loadProfile(child.get("inheritsFrom").getAsString(), chain);
        JsonObject result = parent.deepCopy();
        for (var entry : child.entrySet()) if (!Set.of("libraries", "arguments").contains(entry.getKey())) result.add(entry.getKey(), entry.getValue());
        Map<String, JsonElement> libraries = new LinkedHashMap<>();
        for (JsonElement lib : Net.array(parent, "libraries")) libraries.put(libraryKey(lib.getAsJsonObject().get("name").getAsString()), lib);
        for (JsonElement lib : Net.array(child, "libraries")) libraries.put(libraryKey(lib.getAsJsonObject().get("name").getAsString()), lib);
        JsonArray merged = new JsonArray(); libraries.values().forEach(merged::add); result.add("libraries", merged);
        JsonObject arguments = parent.has("arguments") ? parent.getAsJsonObject("arguments").deepCopy() : new JsonObject();
        if (child.has("arguments")) for (var entry : child.getAsJsonObject("arguments").entrySet()) {
            JsonArray values = arguments.has(entry.getKey()) ? arguments.getAsJsonArray(entry.getKey()) : new JsonArray();
            values.addAll(entry.getValue().getAsJsonArray()); arguments.add(entry.getKey(), values);
        }
        result.add("arguments", arguments); return result;
    }
    Process launch(Installed installed, Path instance, String address, MicrosoftAuth.Account account, int memory) throws Exception {
        JsonObject version = installed.version();
        Path natives = instance.resolve("natives");
        if (Files.exists(natives)) deleteTree(natives);
        Files.createDirectories(natives);
        List<String> classpath = new ArrayList<>();
        for (JsonElement value : Net.array(version, "libraries")) {
            JsonObject lib = value.getAsJsonObject(); if (!allowed(lib, Map.of()) || !nativeMatches(lib.get("name").getAsString())) continue;
            String coordinate = lib.get("name").getAsString();
            JsonObject downloads = lib.has("downloads") ? lib.getAsJsonObject("downloads") : new JsonObject();
            if (downloads.has("artifact")) {
                Path file = safeResolve(root.resolve("libraries"), downloads.getAsJsonObject("artifact").get("path").getAsString());
                classpath.add(file.toString());
                if (coordinate.contains(":natives-")) unzip(file, natives, true);
            } else if (!lib.has("natives")) classpath.add(root.resolve("libraries").resolve(coordinatePath(coordinate)).toString());
            if (lib.has("natives") && lib.getAsJsonObject("natives").has(ruleOs())) {
                String classifier = lib.getAsJsonObject("natives").get(ruleOs()).getAsString().replace("${arch}", "64");
                if (downloads.has("classifiers") && downloads.getAsJsonObject("classifiers").has(classifier)) {
                    Path file = safeResolve(root.resolve("libraries"), downloads.getAsJsonObject("classifiers").getAsJsonObject(classifier).get("path").getAsString());
                    unzip(file, natives, true);
                }
            }
        }
        String minecraft = Net.str(version, "jar", Net.str(version, "inheritsFrom", installed.profile()));
        classpath.add(root.resolve("versions").resolve(minecraft).resolve(minecraft + ".jar").toString());
        Map<String, String> variables = new HashMap<>();
        variables.put("auth_player_name", account.name()); variables.put("auth_uuid", account.uuid()); variables.put("auth_access_token", account.accessToken());
        variables.put("auth_xuid", account.xuid()); variables.put("clientid", account.clientId()); variables.put("user_type", "msa"); variables.put("user_properties", "{}");
        variables.put("version_name", installed.profile()); variables.put("version_type", "release"); variables.put("game_directory", instance.toString());
        variables.put("assets_root", root.resolve("assets").toString()); variables.put("assets_index_name", version.getAsJsonObject("assetIndex").get("id").getAsString());
        variables.put("natives_directory", natives.toString()); variables.put("launcher_name", "GameLauncher"); variables.put("launcher_version", "0.1.0");
        variables.put("classpath", String.join(File.pathSeparator, classpath)); variables.put("classpath_separator", File.pathSeparator); variables.put("library_directory", root.resolve("libraries").toString());
        variables.put("quickPlayPath", instance.resolve("quickplay.json").toString()); variables.put("quickPlayMultiplayer", address);
        Map<String, Boolean> features = Map.of("has_quick_plays_support", true, "is_quick_play_multiplayer", true);
        List<String> args = new ArrayList<>(); args.add("-Xmx" + Math.max(1024, memory) + "M"); args.add("-Dfile.encoding=UTF-8");
        JsonObject arguments = version.has("arguments") ? version.getAsJsonObject("arguments") : new JsonObject();
        List<String> jvm = expandArguments(Net.array(arguments, "jvm"), variables, features);
        if (jvm.isEmpty()) { args.add("-Djava.library.path=" + natives); args.add("-cp"); args.add(variables.get("classpath")); } else args.addAll(jvm);
        args.add(version.get("mainClass").getAsString());
        List<String> game = expandArguments(Net.array(arguments, "game"), variables, features);
        if (game.isEmpty() && version.has("minecraftArguments")) {
            Matcher tokens = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(version.get("minecraftArguments").getAsString());
            while (tokens.find()) game.add(substitute(tokens.group(1) == null ? tokens.group(2) : tokens.group(1), variables));
        }
        args.addAll(game);
        if (!game.contains("--quickPlayMultiplayer")) {
            String host = address; int port = 25565;
            if (address.startsWith("[")) { int end = address.indexOf(']'); host = address.substring(1, end); if (end + 1 < address.length()) port = Integer.parseInt(address.substring(end + 2)); }
            else if (address.indexOf(':') == address.lastIndexOf(':') && address.contains(":")) { int index = address.lastIndexOf(':'); host = address.substring(0, index); port = Integer.parseInt(address.substring(index + 1)); }
            args.addAll(List.of("--server", host, "--port", Integer.toString(port)));
        }
        Files.createDirectories(instance.resolve("logs"));
        // An argument file avoids Windows' command line length limit. It contains a token, so
        // remove it immediately after the child has consumed it, and never log its contents.
        Path argumentFile = Files.createTempFile(instance, ".launch-", ".args");
        Store.write(argumentFile, String.join("\n", args.stream().map(GameInstaller::quoteArgument).toList()));
        Process process;
        try { process = new ProcessBuilder(installed.java().toString(), "@" + argumentFile).directory(instance.toFile()).redirectErrorStream(true).redirectOutput(instance.resolve("logs/launcher-game.log").toFile()).start(); }
        catch (Exception ex) { Files.deleteIfExists(argumentFile); throw ex; }
        Thread.ofVirtual().start(() -> { try { Thread.sleep(15000); Files.deleteIfExists(argumentFile); process.waitFor(); } catch (Exception ignored) {} finally { try { Files.deleteIfExists(argumentFile); } catch (IOException ignored) {} } });
        return process;
    }
    static String quoteArgument(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static List<String> expandArguments(JsonArray arguments, Map<String, String> variables, Map<String, Boolean> features) throws IOException {
        List<String> result = new ArrayList<>();
        for (JsonElement entry : arguments) {
            if (entry.isJsonPrimitive()) result.add(substitute(entry.getAsString(), variables));
            else { JsonObject arg = entry.getAsJsonObject(); if (!allowed(arg, features)) continue;
                if (arg.get("value").isJsonArray()) for (JsonElement value : arg.getAsJsonArray("value")) result.add(substitute(value.getAsString(), variables));
                else result.add(substitute(arg.get("value").getAsString(), variables)); }
        }
        return result;
    }
    private static String substitute(String input, Map<String, String> variables) throws IOException {
        for (var entry : variables.entrySet()) input = input.replace("${" + entry.getKey() + "}", entry.getValue());
        if (input.contains("${")) throw new IOException("지원하지 않는 게임 실행 변수가 있습니다.");
        return input;
    }
    static boolean allowed(JsonObject item, Map<String, Boolean> features) {
        if (!item.has("rules")) return true;
        boolean result = false;
        for (JsonElement value : item.getAsJsonArray("rules")) {
            JsonObject rule = value.getAsJsonObject(); boolean match = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) match &= os.get("name").getAsString().equals(ruleOs());
                if (os.has("arch")) match &= System.getProperty("os.arch").matches(os.get("arch").getAsString());
                if (os.has("version")) match &= System.getProperty("os.version").matches(os.get("version").getAsString());
            }
            if (rule.has("features")) for (var feature : rule.getAsJsonObject("features").entrySet()) match &= features.getOrDefault(feature.getKey(), false).equals(feature.getValue().getAsBoolean());
            if (match) result = rule.get("action").getAsString().equals("allow");
        }
        return result;
    }
    private void run(Path java, List<String> arguments, Path log) throws Exception {
        List<String> command = new ArrayList<>(); command.add(java.toString()); command.addAll(arguments);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            while (!process.waitFor(1, TimeUnit.SECONDS)) Net.checkCancelled();
            if (process.exitValue() != 0) throw new IOException("설치 프로그램 종료 코드 " + process.exitValue() + ": " + log);
        } catch (InterruptedException ex) { process.destroyForcibly(); throw ex; }
    }
    static JsonObject read(Path file) throws IOException { try (Reader reader = Files.newBufferedReader(file)) { return JsonParser.parseReader(reader).getAsJsonObject(); } }
    static String os() { String os = System.getProperty("os.name").toLowerCase(Locale.ROOT); return os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux"; }
    static String ruleOs() { return os().equals("mac") ? "osx" : os(); }
    static String arch() { String arch = System.getProperty("os.arch"); return arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "x64"; }
    private static boolean nativeMatches(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 4 || !parts[3].startsWith("natives-")) return true;
        String platform = os().equals("mac") ? "macos" : os();
        String expected = "natives-" + platform + (arch().equals("aarch64") ? "-arm64" : "");
        return parts[3].split("@", 2)[0].equals(expected);
    }
    static Path safeResolve(Path root, String relative) throws IOException { Path path = root.resolve(relative).normalize(); if (!path.startsWith(root.normalize())) throw new IOException("잘못된 파일 경로"); return path; }
    static String coordinatePath(String coordinate) {
        String[] extension = coordinate.split("@", 2), parts = extension[0].split(":");
        if (parts.length < 3) throw new IllegalArgumentException("잘못된 Maven 좌표");
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + (parts.length > 3 ? "-" + parts[3] : "") + "." + (extension.length > 1 ? extension[1] : "jar");
    }
    private static String libraryKey(String coordinate) { String[] p = coordinate.split(":"); return p[0] + ":" + p[1] + (p.length > 3 ? ":" + p[3] : ""); }
    static void unzip(Path archive, Path destination, boolean nativesOnly) throws IOException {
        Files.createDirectories(destination);
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || (nativesOnly && !(entry.getName().endsWith(".dll") || entry.getName().endsWith(".so") || entry.getName().endsWith(".dylib")))) continue;
                Path output = safeResolve(destination, nativesOnly ? Path.of(entry.getName()).getFileName().toString() : entry.getName());
                Files.createDirectories(output.getParent());
                try (OutputStream out = Files.newOutputStream(output)) { byte[] b = new byte[65536]; int n; while ((n = zip.read(b)) != -1) { total += n; if (total > 2L * 1024 * 1024 * 1024) throw new IOException("압축 해제 크기 제한"); out.write(b, 0, n); } }
            }
        }
    }
    private static void deleteTree(Path root) throws IOException { try (var files = Files.walk(root)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
}
