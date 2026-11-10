package io.github.rlawngjs0313.launcher;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.*;
import org.apache.maven.artifact.versioning.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.*;

final class ModInspector {
    static List<Models.ModFile> scan(Path directory) throws Exception {
        List<Models.ModFile> files = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList()) files.add(inspect(path));
        }
        return files;
    }
    static Models.ModFile inspect(Path file) throws Exception {
        List<Models.ModInfo> mods = new ArrayList<>(); List<Models.Dependency> dependencies = new ArrayList<>();
        try (ZipFile jar = new ZipFile(file.toFile())) {
            String implementationVersion = "";
            ZipEntry manifest = jar.getEntry("META-INF/MANIFEST.MF");
            if (manifest != null) try (InputStream in = jar.getInputStream(manifest)) { implementationVersion = Objects.toString(new Manifest(in).getMainAttributes().getValue("Implementation-Version"), ""); }
            ZipEntry entry = jar.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) entry = jar.getEntry("META-INF/mods.toml");
            if (entry != null) {
                if (entry.getSize() > 2 * 1024 * 1024) throw new IOException("모드 메타데이터 크기 제한 초과");
                Config config;
                try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) { config = new TomlParser().parse(reader); }
                List<Config> declarations = config.getOrElse("mods", List.of());
                for (Config mod : declarations) {
                    String id = mod.get("modId"), version = mod.getOrElse("version", "");
                    version = version.replace("${file.jarVersion}", implementationVersion);
                    if (version.contains("${") || version.isBlank()) throw new IOException("모드 버전을 확인할 수 없습니다: " + file.getFileName());
                    mods.add(new Models.ModInfo(id, version));
                    List<Config> requirements = config.getOrElse(List.of("dependencies", id), List.of());
                    for (Config dependency : requirements) {
                        String side = dependency.getOrElse("side", "BOTH");
                        if (side.equals("SERVER")) continue;
                        String type = dependency.getOrElse("type", "required");
                        dependencies.add(new Models.Dependency(dependency.get("modId"), dependency.getOrElse("versionRange", "*"), type));
                    }
                }
            } else if (jar.getEntry("fabric.mod.json") != null) {
                try (Reader reader = new InputStreamReader(jar.getInputStream(jar.getEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
                    JsonObject mod = JsonParser.parseReader(reader).getAsJsonObject(); mods.add(new Models.ModInfo(mod.get("id").getAsString(), mod.get("version").getAsString()));
                }
            }
        }
        return new Models.ModFile(file.getFileName().toString(), Net.hash(file, "SHA-256"), Files.size(file), List.copyOf(mods), List.copyOf(dependencies));
    }
    static boolean satisfies(String version, JsonObject requirement) {
        if (requirement.has("minimumVersion") && new DefaultArtifactVersion(version).compareTo(new DefaultArtifactVersion(requirement.get("minimumVersion").getAsString())) < 0) return false;
        for (JsonElement range : Net.array(requirement, "ranges")) if (!inRange(version, range.getAsString())) return false;
        return true;
    }
    static boolean inRange(String version, String specification) {
        if (specification == null || specification.isBlank() || specification.equals("*")) return true;
        try {
            VersionRange range = VersionRange.createFromVersionSpec(specification);
            if (range.getRestrictions().isEmpty()) return new DefaultArtifactVersion(version).compareTo(new DefaultArtifactVersion(specification)) >= 0;
            return range.containsVersion(new DefaultArtifactVersion(version));
        } catch (InvalidVersionSpecificationException ex) { return false; }
    }
}
