package io.github.rlawngjs0313.launcher;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class BuildInfo {
    static final String VERSION;
    static final String REPOSITORY;
    static final String MICROSOFT_CLIENT_ID;
    static final String APPLICATION_ID = "rlawngjs0313.game-launcher";
    static {
        Properties properties = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/launcher-build.properties")) {
            if (in == null) throw new IOException("Build metadata missing. Run Gradle processResources.");
            properties.load(in);
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
        VERSION = Objects.requireNonNull(properties.getProperty("version"));
        REPOSITORY = Objects.requireNonNull(properties.getProperty("repository"));
        MICROSOFT_CLIENT_ID = properties.getProperty("microsoftClientId", "");
    }
    static Path installation() throws IOException {
        String home = System.getProperty("game.launcher.home");
        if (!GameInstaller.os().equals("windows") || !GameInstaller.arch().equals("x64") || home == null) throw new IOException("자동 업데이트는 릴리즈에서 설치한 Windows x64 앱에서 사용할 수 있습니다.");
        Path root = Path.of(home).toRealPath();
        Properties marker = new Properties();
        try (InputStream input = Files.newInputStream(root.resolve("launcher-release.properties"))) { marker.load(input); }
        if (!APPLICATION_ID.equals(marker.getProperty("applicationId")) || !VERSION.equals(marker.getProperty("version")) || !Files.isRegularFile(root.resolve("GameLauncher.exe"))) throw new IOException("런처 설치 폴더를 확인할 수 없습니다.");
        return root;
    }
    static int compare(String a, String b) {
        String[] left = stableVersion(a).split("\\."), right = stableVersion(b).split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = new java.math.BigInteger(left[i]).compareTo(new java.math.BigInteger(right[i]));
            if (result != 0) return result;
        }
        return 0;
    }
    static String stableVersion(String value) {
        String version = value.startsWith("v") ? value.substring(1) : value;
        if (!version.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")) throw new IllegalArgumentException("지원하지 않는 릴리즈 버전: " + value);
        return version;
    }
    private BuildInfo() {}
}
