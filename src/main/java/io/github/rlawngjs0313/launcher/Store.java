package io.github.rlawngjs0313.launcher;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;

final class Store {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    final Path root;
    Store() throws IOException {
        String local = System.getenv("LOCALAPPDATA");
        root = local == null ? Path.of(System.getProperty("user.home"), ".game-launcher") : Path.of(local, "GameLauncher");
        Files.createDirectories(root);
    }
    Models.Settings load() throws IOException {
        Path file = root.resolve("settings.json");
        if (!Files.exists(file)) return new Models.Settings();
        try (Reader reader = Files.newBufferedReader(file)) {
            Models.Settings settings = JSON.fromJson(reader, Models.Settings.class);
            if (settings == null || settings.servers == null) throw new IOException("설정 파일이 손상되었습니다: " + file);
            return settings;
        } catch (JsonParseException ex) { throw new IOException("설정 JSON 오류: " + file, ex); }
    }
    void save(Models.Settings settings) throws IOException { write(root.resolve("settings.json"), JSON.toJson(settings)); }
    Path instance(Models.ServerEntry server) throws Exception {
        String identity = server.apiBase() + "\n" + server.serverId();
        String id = Net.digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8), "SHA-256").substring(0, 24);
        Path instance = root.resolve("instances").resolve(id);
        Files.createDirectories(instance.resolve("mods"));
        return instance;
    }
    static void write(Path target, String text) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".write-", ".tmp");
        try { Files.writeString(temporary, text); move(temporary, target); }
        finally { Files.deleteIfExists(temporary); }
    }
    static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
}
