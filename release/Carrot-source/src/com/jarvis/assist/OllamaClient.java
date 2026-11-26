package com.jarvis.assist;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

final class OllamaClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String baseUrl = System.getenv().getOrDefault("CARROT_OLLAMA_URL", "http://127.0.0.1:11434");
    private final String model = System.getenv().getOrDefault("CARROT_OLLAMA_MODEL", "llama3.2:3b");
    private volatile boolean available;

    String model() { return model; }
    boolean available() { return available; }

    void checkAsync() {
        CompletableFuture.runAsync(this::health);
    }

    void warmAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                available = health();
                if (available) generate("Reply only with: ready", 2);
            } catch (Exception ignored) { available = false; }
        });
    }

    void sleepAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                String body = "{\"model\":\"" + escape(model) + "\",\"keep_alive\":0}";
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                http.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) { }
        });
    }

    boolean health() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            available = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
            return available;
        } catch (Exception e) {
            available = false;
            return false;
        }
    }

    String answer(String prompt) {
        if (!available && !health()) return "My local AI model is unavailable. Start Ollama and try again.";
        try {
            return generate(prompt, 240).trim();
        } catch (Exception e) {
            available = false;
            return "The local AI model did not respond in time. Your system commands and reminders still work.";
        }
    }

    private String generate(String prompt, int tokens) throws IOException, InterruptedException {
        String body = "{\"model\":\"" + escape(model) + "\",\"prompt\":\"" + escape(prompt)
                + "\",\"stream\":false,\"keep_alive\":\"30m\",\"options\":{\"temperature\":0.35,\"num_predict\":" + tokens + "}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Ollama returned " + response.statusCode());
        available = true;
        return jsonString(response.body(), "response");
    }

    static String jsonString(String json, String key) throws IOException {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new IOException("Missing JSON field: " + key);
        start += marker.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '"') throw new IOException("Invalid JSON string");
        StringBuilder result = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') return result.toString();
            if (c != '\\') { result.append(c); continue; }
            if (++i >= json.length()) break;
            char escaped = json.charAt(i);
            switch (escaped) {
                case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                case 'b' -> result.append('\b'); case 'f' -> result.append('\f'); case '"' -> result.append('"');
                case '\\' -> result.append('\\'); case '/' -> result.append('/');
                case 'u' -> { result.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); i += 4; }
                default -> result.append(escaped);
            }
        }
        throw new IOException("Unterminated JSON string");
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\"); case '"' -> out.append("\\\""); case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.toString();
    }
}
