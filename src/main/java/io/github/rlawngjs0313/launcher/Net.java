package io.github.rlawngjs0313.launcher;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

final class Net {
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    static final HttpClient DOWNLOADS = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    static void checkCancelled() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException("작업을 취소했습니다."); }
    static JsonObject json(String url) throws Exception { return request(URI.create(url), "GET", null, ""); }
    static JsonElement element(String url) throws Exception {
        var response = DOWNLOADS.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).header("User-Agent", "GameLauncher/0.1.0").build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        return JsonParser.parseString(response.body());
    }
    static JsonObject api(Models.ServerEntry server, String suffix, JsonObject body) throws Exception {
        URI base = apiBase(server.apiBase());
        URI uri = base.resolve(base.getPath().replaceAll("/$", "") + suffix);
        return request(uri, body == null ? "GET" : "POST", body, server.token());
    }
    static URI apiBase(String value) {
        URI uri = URI.create(value.replaceAll("/$", ""));
        String host = uri.getHost();
        boolean local = Set.of("localhost", "127.0.0.1", "::1").contains(host == null ? "" : host);
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
            !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("API 주소는 HTTPS를 사용하세요. localhost 개발 주소만 HTTP가 가능합니다.");
        }
        return uri;
    }
    static String serverPath(Models.ServerEntry server) { return "/api/v1/servers/" + URLEncoder.encode(server.serverId(), java.nio.charset.StandardCharsets.UTF_8); }
    static JsonObject request(URI uri, String method, JsonObject body, String token) throws Exception {
        checkCancelled();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).header("Accept", "application/json").header("User-Agent", "GameLauncher/0.1.0");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Store.JSON.toJson(body)));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String message = "HTTP " + response.statusCode();
            try { JsonObject error = JsonParser.parseString(response.body()).getAsJsonObject(); if (error.has("error")) message += ": " + error.get("error").getAsString(); } catch (Exception ignored) {}
            throw new IOException(message);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    static void download(String url, Path target, String algorithm, String expected, String token) throws Exception {
        download(url, target, algorithm, expected, token, Long.MAX_VALUE);
    }
    static void download(String url, Path target, String algorithm, String expected, String token, long maximumBytes) throws Exception {
        checkCancelled();
        if (Files.isRegularFile(target) && (expected == null || expected.isBlank() || hash(target, algorithm).equalsIgnoreCase(expected))) return;
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) && !Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost())) throw new IOException("암호화되지 않은 다운로드 주소");
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".download-", ".part");
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).header("User-Agent", "GameLauncher/0.1.0");
            if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
            HttpClient client = token == null || token.isBlank() ? DOWNLOADS : HTTP;
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) throw new IOException("파일 다운로드 HTTP " + response.statusCode());
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    byte[] block = new byte[65536]; int n; long total = 0;
                    while ((n = in.read(block)) != -1) { checkCancelled(); total += n; if (total > maximumBytes) throw new IOException("다운로드 파일 크기 제한 초과"); out.write(block, 0, n); }
                }
            }
            if (expected != null && !expected.isBlank() && !hash(temporary, algorithm).equalsIgnoreCase(expected)) throw new IOException("다운로드 해시가 일치하지 않습니다: " + target.getFileName());
            Store.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }
    static String hash(Path file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) { checkCancelled(); digest.update(buffer, 0, n); } }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String digest(byte[] bytes, String algorithm) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
    static String str(JsonObject object, String key, String fallback) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback; }
    static JsonArray array(JsonObject object, String key) { return object.has(key) ? object.getAsJsonArray(key) : new JsonArray(); }
}
