package io.github.rlawngjs0313.launcher;

import com.google.gson.*;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class MicrosoftAuth {
    record Account(String name, String uuid, String accessToken, String xuid, String clientId, Instant expires) {}
    private final Path tokenFile;
    private Account current;
    MicrosoftAuth(Path root) { tokenFile = root.resolve("account.dpapi"); }

    synchronized Account authenticate(String clientId, Consumer<String> progress) throws Exception {
        if (clientId == null || !clientId.matches("[a-fA-F0-9-]{36}")) throw new IOException("설정에서 Microsoft 애플리케이션 Client ID를 입력하세요.");
        if (current != null && current.clientId().equals(clientId) && current.expires().isAfter(Instant.now().plusSeconds(90))) return current;
        JsonObject msa = null;
        JsonObject saved = load();
        if (saved != null && clientId.equals(Net.str(saved, "clientId", ""))) {
            progress.accept("Microsoft 로그인 갱신");
            try { msa = form(Map.of("client_id", clientId, "grant_type", "refresh_token", "refresh_token", saved.get("refreshToken").getAsString(), "scope", "XboxLive.signin offline_access")); }
            catch (AuthFailure failure) { progress.accept("로그인을 다시 진행하세요."); }
        }
        if (msa == null) msa = browserLogin(clientId, progress);
        if (msa.has("refresh_token")) save(clientId, msa.get("refresh_token").getAsString());
        progress.accept("Xbox 및 Minecraft 계정 확인");
        JsonObject properties = new JsonObject(); properties.addProperty("AuthMethod", "RPS"); properties.addProperty("SiteName", "user.auth.xboxlive.com"); properties.addProperty("RpsTicket", "d=" + msa.get("access_token").getAsString());
        JsonObject request = new JsonObject(); request.add("Properties", properties); request.addProperty("RelyingParty", "http://auth.xboxlive.com"); request.addProperty("TokenType", "JWT");
        JsonObject xbox = post("https://user.auth.xboxlive.com/user/authenticate", request);
        properties = new JsonObject(); properties.addProperty("SandboxId", "RETAIL"); JsonArray tokens = new JsonArray(); tokens.add(xbox.get("Token").getAsString()); properties.add("UserTokens", tokens);
        request = new JsonObject(); request.add("Properties", properties); request.addProperty("RelyingParty", "rp://api.minecraftservices.com/"); request.addProperty("TokenType", "JWT");
        JsonObject xsts = post("https://xsts.auth.xboxlive.com/xsts/authorize", request);
        JsonObject user = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject();
        request = new JsonObject(); request.addProperty("identityToken", "XBL3.0 x=" + user.get("uhs").getAsString() + ";" + xsts.get("Token").getAsString());
        JsonObject minecraft = post("https://api.minecraftservices.com/authentication/login_with_xbox", request);
        String access = minecraft.get("access_token").getAsString();
        JsonObject entitlement = authorizedGet("https://api.minecraftservices.com/entitlements/mcstore", access);
        if (!entitlement.has("items") || entitlement.getAsJsonArray("items").isEmpty()) throw new IOException("Minecraft Java Edition 이용 권한을 확인할 수 없습니다.");
        JsonObject profile = authorizedGet("https://api.minecraftservices.com/minecraft/profile", access);
        current = new Account(profile.get("name").getAsString(), profile.get("id").getAsString(), access, Net.str(user, "xid", ""), clientId, Instant.now().plusSeconds(minecraft.get("expires_in").getAsLong()));
        return current;
    }

    private JsonObject browserLogin(String clientId, Consumer<String> progress) throws Exception {
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        CompletableFuture<String> result = new CompletableFuture<>();
        HttpServer callback = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String redirect = "http://localhost:" + callback.getAddress().getPort() + "/";
        callback.createContext("/", exchange -> {
            try {
                Map<String, String> query = new HashMap<>();
                String raw = exchange.getRequestURI().getRawQuery();
                if (raw != null) for (String part : raw.split("&")) { String[] pair = part.split("=", 2); query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""); }
                boolean valid = exchange.getRequestMethod().equals("GET") && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), query.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8));
                String message;
                if (!valid) message = "잘못된 로그인 요청입니다.";
                else if (query.containsKey("code")) { result.complete(query.get("code")); message = "로그인이 완료됐습니다. 런처로 돌아가세요."; }
                else { result.completeExceptionally(new IOException("로그인이 취소되었거나 승인되지 않았습니다.")); message = "로그인을 완료하지 못했습니다. 런처에서 다시 시도하세요."; }
                byte[] body = message.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(valid ? 200 : 400, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        callback.start();
        try {
            String query = encode(Map.of("client_id", clientId, "response_type", "code", "redirect_uri", redirect,
                "scope", "XboxLive.signin offline_access", "state", state, "code_challenge", challenge, "code_challenge_method", "S256", "prompt", "select_account"));
            URI uri = URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?" + query);
            progress.accept("기본 브라우저에서 Microsoft 로그인을 완료하세요.");
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(uri);
            else throw new IOException("기본 웹 브라우저를 열 수 없습니다.");
            String code = result.get(5, TimeUnit.MINUTES);
            return form(Map.of("client_id", clientId, "grant_type", "authorization_code", "code", code, "redirect_uri", redirect, "code_verifier", verifier, "scope", "XboxLive.signin offline_access"));
        } finally { callback.stop(0); }
    }
    private static String encode(Map<String, String> values) { return String.join("&", values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).toList()); }
    private static JsonObject form(Map<String, String> values) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(encode(values))));
    }
    private static JsonObject post(String endpoint, JsonObject body) throws Exception { return send(HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(Store.JSON.toJson(body)))); }
    private static JsonObject authorizedGet(String endpoint, String token) throws Exception { return send(HttpRequest.newBuilder(URI.create(endpoint)).header("Authorization", "Bearer " + token).GET()); }
    private static JsonObject send(HttpRequest.Builder request) throws Exception {
        var response = Net.HTTP.send(request.timeout(Duration.ofSeconds(40)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new AuthFailure("계정 인증 HTTP " + response.statusCode() + ". 계정 이용 권한과 애플리케이션 등록 상태를 확인하세요.");
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private JsonObject load() throws IOException {
        if (!GameInstaller.os().equals("windows") || !Files.exists(tokenFile)) return null;
        try { return JsonParser.parseString(new String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(tokenFile)), StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException ex) { throw new IOException("저장된 로그인 정보를 읽을 수 없습니다. 설정에서 로그아웃 후 다시 로그인하세요.", ex); }
    }
    private void save(String id, String refresh) throws IOException {
        if (!GameInstaller.os().equals("windows")) return;
        JsonObject data = new JsonObject(); data.addProperty("clientId", id); data.addProperty("refreshToken", refresh);
        byte[] encrypted = Crypt32Util.cryptProtectData(Store.JSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        Path temporary = Files.createTempFile(tokenFile.getParent(), ".account-", ".tmp");
        try { Files.write(temporary, encrypted); Store.move(temporary, tokenFile); } finally { Files.deleteIfExists(temporary); }
    }
    synchronized void logout() throws IOException { current = null; Files.deleteIfExists(tokenFile); }
    private static final class AuthFailure extends IOException { AuthFailure(String message) { super(message); } }
}
