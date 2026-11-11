package io.github.rlawngjs0313.launcher;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class LoginCallback {
    private LoginCallback() {}

    static void handle(HttpExchange exchange, String expectedState, CompletableFuture<String> result, Runnable onBrowserComplete) throws IOException {
        try {
            if (!exchange.getRequestURI().getPath().equals("/")) {
                exchange.sendResponseHeaders(404, -1); return;
            }
            Map<String, String> query = new HashMap<>();
            boolean malformed = false;
            try {
                String raw = exchange.getRequestURI().getRawQuery();
                if (raw != null) for (String part : raw.split("&")) {
                    String[] pair = part.split("=", 2);
                    query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                }
            } catch (IllegalArgumentException ex) { malformed = true; }
            boolean valid = !malformed && exchange.getRequestMethod().equals("GET")
                && MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.UTF_8), query.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8));
            String code = query.getOrDefault("code", "");
            boolean received = valid && !code.isBlank() && !query.containsKey("error");
            byte[] body = page(received, valid).getBytes(StandardCharsets.UTF_8);
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/html; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
            exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
            // Deliver the page before the authentication thread can stop the callback server.
            if (valid) onBrowserComplete.run();
            if (received) result.complete(code);
            else if (valid) result.completeExceptionally(new IOException("로그인이 취소되었거나 승인되지 않았습니다."));
        } finally { exchange.close(); }
    }

    private static String page(boolean received, boolean valid) throws IOException {
        String template;
        try (var stream = Objects.requireNonNull(LoginCallback.class.getResourceAsStream("/login-callback.html"))) {
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String title = received ? "런처로 돌아가세요" : valid ? "로그인을 완료하지 못했습니다" : "로그인 요청을 확인할 수 없습니다";
        String description = received ? "Microsoft 계정 확인을 위한 브라우저 단계가 끝났습니다."
            : valid ? "Microsoft 로그인이 취소되었거나 승인되지 않았습니다." : "유효하지 않거나 만료된 로그인 요청입니다.";
        String next = received ? "계정 연결은 런처에서 계속됩니다." : "런처에서 Microsoft 로그인을 다시 시작하세요.";
        String hint = received ? "최종 로그인 결과를 런처에서 확인하세요." : "이 페이지에서는 다시 로그인할 수 없습니다.";
        return template.replace("{{title}}", title).replace("{{description}}", description)
            .replace("{{next}}", next).replace("{{hint}}", hint).replace("{{status}}", received ? "received" : "failed")
            .replace("{{symbol}}", received ? "✓" : "!").replace("{{badge}}", received ? "브라우저 인증 완료" : "다시 확인해 주세요");
    }
}
