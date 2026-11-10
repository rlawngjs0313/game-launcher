package io.github.rlawngjs0313.launcher;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

final class ModSync {
    static final class Candidate {
        final Models.ModFile file;
        final JsonObject artifact;
        final boolean local;
        boolean selected;
        Candidate(Models.ModFile file, JsonObject artifact, boolean local) { this.file = file; this.artifact = artifact; this.local = local; this.selected = local; }
        @Override public String toString() { return (local ? "설치됨 · " : "서버 파일 · ") + file.fileName() + " — " + String.join(", ", file.mods().stream().map(m -> m.id() + " " + m.version()).toList()); }
    }
    record Plan(JsonObject info, JsonObject response, List<Models.ModFile> original, List<Candidate> candidates, boolean reviewRequired) {}

    static Plan prepare(Models.ServerEntry server, JsonObject info, Path instance) throws Exception {
        List<Models.ModFile> installed = ModInspector.scan(instance.resolve("mods"));
        JsonObject inventory = new JsonObject(); inventory.addProperty("manifestRevision", info.get("manifestRevision").getAsString()); inventory.add("files", Store.JSON.toJsonTree(installed));
        JsonObject response = Net.api(server, Net.serverPath(server) + "/sync-plan", inventory);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (var file : installed) candidates.put("local:" + file.fileName(), new Candidate(file, null, true));
        for (JsonElement value : response.getAsJsonArray("artifacts")) {
            JsonObject artifact = value.getAsJsonObject(); String hash = artifact.get("sha256").getAsString();
            if (!hash.matches("[a-f0-9]{64}")) throw new IOException("잘못된 서버 파일 해시");
            String name = artifact.get("fileName").getAsString(); safeName(name);
            if (candidates.values().stream().anyMatch(c -> c.file.sha256().equals(hash))) continue;
            List<Models.ModInfo> mods = new ArrayList<>();
            for (JsonElement mod : artifact.getAsJsonArray("mods")) mods.add(Store.JSON.fromJson(mod, Models.ModInfo.class));
            candidates.put(hash, new Candidate(new Models.ModFile(name, hash, artifact.get("size").getAsLong(), mods, List.of()), artifact, false));
        }
        boolean review = false;
        for (JsonElement value : response.getAsJsonArray("requirements")) {
            JsonObject requirement = value.getAsJsonObject(); String id = requirement.get("id").getAsString();
            List<Candidate> options = candidates.values().stream().filter(c -> contains(c, id)).toList();
            List<Candidate> validLocal = options.stream().filter(c -> c.local && eligible(c, requirement)).toList();
            if (options.size() > 1 && options.stream().anyMatch(c -> c.local)) review = true;
            Candidate chosen = validLocal.isEmpty() ? options.stream().filter(c -> eligible(c, requirement)).findFirst().orElseThrow(() -> new IOException("필수 모드를 만족하는 파일이 없습니다: " + id)) : validLocal.getFirst();
            for (Candidate option : options) option.selected = option == chosen;
        }
        List<Candidate> list = new ArrayList<>(candidates.values());
        if (list.stream().anyMatch(c -> c.local && !c.selected)) review = true;
        if (!validate(info, list).isEmpty()) review = true;
        return new Plan(info, response, installed, list, review);
    }
    private static boolean contains(Candidate c, String id) { return c.file.mods().stream().anyMatch(m -> m.id().equals(id)); }
    private static boolean eligible(Candidate c, JsonObject req) { return c.file.mods().stream().anyMatch(m -> m.id().equals(req.get("id").getAsString()) && ModInspector.satisfies(m.version(), req)); }

    static List<String> validate(JsonObject info, List<Candidate> choices) {
        List<String> errors = new ArrayList<>(); Map<String, Models.ModInfo> selected = new HashMap<>(); Set<String> names = new HashSet<>();
        selected.put("minecraft", new Models.ModInfo("minecraft", info.getAsJsonObject("minecraft").get("version").getAsString()));
        String loader = info.getAsJsonObject("loader").get("type").getAsString();
        if (!loader.equals("vanilla")) selected.put(loader, new Models.ModInfo(loader, info.getAsJsonObject("loader").get("version").getAsString()));
        for (Candidate choice : choices) if (choice.selected) {
            if (!names.add(choice.file.fileName().toLowerCase(Locale.ROOT))) errors.add("파일명 중복: " + choice.file.fileName());
            for (Models.ModInfo mod : choice.file.mods()) if (selected.putIfAbsent(mod.id(), mod) != null) errors.add("동일 모드 ID가 중복 선택됨: " + mod.id());
        }
        for (JsonElement value : info.getAsJsonArray("requirements")) {
            JsonObject req = value.getAsJsonObject(); String id = req.get("id").getAsString(); Models.ModInfo mod = selected.get(id);
            if (mod == null || !ModInspector.satisfies(mod.version(), req)) errors.add(id + ": 최소 " + Net.str(req, "minimumVersion", "") + ", 범위 " + Net.array(req, "ranges"));
        }
        for (Candidate choice : choices) if (choice.selected) for (var dependency : choice.file.dependencies()) {
            Models.ModInfo actual = selected.get(dependency.id());
            boolean fits = actual != null && ModInspector.inRange(actual.version(), dependency.range());
            if (dependency.type().equalsIgnoreCase("required") && !fits) errors.add(choice.file.fileName() + " → " + dependency.id() + " " + dependency.range() + " 필요");
            if (dependency.type().equalsIgnoreCase("incompatible") && fits) errors.add(choice.file.fileName() + "과 " + dependency.id() + " 충돌");
            if (dependency.type().equalsIgnoreCase("optional") && actual != null && !fits) errors.add(choice.file.fileName() + "의 선택 의존성 버전 충돌: " + dependency.id());
        }
        return errors.stream().distinct().toList();
    }
    static void apply(Models.ServerEntry server, Plan plan, Path instance, Consumer<String> progress) throws Exception {
        List<String> errors = validate(plan.info(), plan.candidates());
        if (!errors.isEmpty()) throw new IOException(String.join("\n", errors));
        Path staging = instance.resolve(".mod-downloads"); Files.createDirectories(staging);
        List<Candidate> inspected = new ArrayList<>();
        for (Candidate candidate : plan.candidates()) {
            if (!candidate.selected) continue;
            if (candidate.local) { inspected.add(candidate); continue; }
            progress.accept("모드 다운로드: " + candidate.file.fileName());
            String relative = candidate.artifact.get("downloadPath").getAsString();
            String prefix = Net.serverPath(server) + "/artifacts/" + candidate.file.sha256() + "?revision=";
            if (!relative.startsWith(prefix)) throw new IOException("잘못된 모드 다운로드 경로");
            Path temporary = staging.resolve(candidate.file.sha256() + ".jar");
            Net.download(Net.apiBase(server.apiBase()).toString() + relative, temporary, "SHA-256", candidate.file.sha256());
            Models.ModFile scanned = ModInspector.inspect(temporary);
            if (scanned.size() != candidate.file.size() || !new HashSet<>(scanned.mods()).equals(new HashSet<>(candidate.file.mods()))) throw new IOException("다운로드한 모드 정보가 서버 목록과 다릅니다: " + candidate.file.fileName());
            var copy = new Candidate(new Models.ModFile(candidate.file.fileName(), scanned.sha256(), scanned.size(), scanned.mods(), scanned.dependencies()), candidate.artifact, false); copy.selected = true; inspected.add(copy);
        }
        errors = validate(plan.info(), inspected);
        if (!errors.isEmpty()) throw new IOException(String.join("\n", errors));
        JsonObject latest = Net.api(server, Net.serverPath(server), null);
        if (!latest.get("manifestRevision").equals(plan.info().get("manifestRevision"))) throw new IOException("서버 요구사항이 변경되어 적용을 중단했습니다. 다시 접속하세요.");
        Path mods = instance.resolve("mods");
        List<Models.ModFile> actual = ModInspector.scan(mods);
        if (!actual.equals(plan.original())) throw new IOException("검사 중 모드 폴더가 변경되었습니다. 다시 접속하세요.");
        Net.checkCancelled();
        // All downloads and validation finish before the destructive phase. Only explicitly
        // unchecked local files are deleted. No backup copies are created.
        for (Candidate candidate : plan.candidates()) if (candidate.local && !candidate.selected) {
            Files.delete(mods.resolve(candidate.file.fileName())); progress.accept("선택 해제된 파일 삭제: " + candidate.file.fileName());
        }
        for (Candidate candidate : plan.candidates()) if (!candidate.local && candidate.selected) {
            Path destination = mods.resolve(candidate.file.fileName());
            if (Files.exists(destination)) throw new IOException("선택한 모드의 파일명 충돌: " + destination.getFileName());
            Store.move(staging.resolve(candidate.file.sha256() + ".jar"), destination);
        }
        Store.write(instance.resolve("instance.json"), Store.JSON.toJson(plan.info()));
        progress.accept("모드 동기화 완료");
    }
    private static void safeName(String name) throws IOException { if (!name.endsWith(".jar") || name.startsWith(".") || name.matches(".*[\\\\/\\x00-\\x1f:].*")) throw new IOException("잘못된 모드 파일명"); }
}
