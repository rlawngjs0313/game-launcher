package io.github.rlawngjs0313.launcher;

import java.util.*;

final class Models {
    record ServerEntry(String name, String address, String apiBase, String serverId) {}
    record ModInfo(String id, String version) {}
    record Dependency(String id, String range, String type) {}
    record ModFile(String fileName, String sha256, long size, List<ModInfo> mods, List<Dependency> dependencies) {}
    static final class Settings {
        String javaPath = "";
        int memoryMb = 4096;
        boolean checkForUpdates = true;
        List<ServerEntry> servers = new ArrayList<>();
    }
    private Models() {}
}
