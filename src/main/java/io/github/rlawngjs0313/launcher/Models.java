package io.github.rlawngjs0313.launcher;

import java.util.*;

final class Models {
    record ServerEntry(String name, String address, String apiBase, String serverId, String token) {}
    record ModInfo(String id, String version) {}
    record Dependency(String id, String range, String type) {}
    record ModFile(String fileName, String sha256, long size, List<ModInfo> mods, List<Dependency> dependencies) {}
    static final class Settings {
        String clientId = "";
        String javaPath = "";
        int memoryMb = 4096;
        List<ServerEntry> servers = new ArrayList<>();
    }
    private Models() {}
}
