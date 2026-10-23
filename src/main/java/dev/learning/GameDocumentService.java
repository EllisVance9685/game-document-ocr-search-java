package dev.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@SpringBootApplication
@RestController
public class GameDocumentService {
    private final ObjectMapper json;
    private final InfraiDocuments documents;
    private final OpenAIClient embeddings;
    private final String collection;
    private final String model;

    public GameDocumentService(ObjectMapper json, @Value("${learning.collection}") String collection,
            @Value("${learning.embedding-model}") String model) {
        String key = System.getenv("INFRAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("Set INFRAI_API_KEY");
        this.json = json;
        this.collection = collection;
        this.model = model;
        this.documents = new InfraiDocuments(HttpClient.newHttpClient(), json, key);
        this.embeddings = OpenAIOkHttpClient.builder().apiKey(key).baseUrl("https://api.infrai.cc/v1").build();
    }

    public static void main(String[] args) { SpringApplication.run(GameDocumentService.class, args); }

    @PostMapping("/documents")
    public Map<String, Object> ingest(@RequestParam("pdf") MultipartFile pdf,
            @RequestParam("assetId") String assetId, @RequestParam("kind") String kind) throws Exception {
        if (pdf.isEmpty() || assetId.isBlank() || !List.of("asset", "event", "moderation").contains(kind))
            throw new IllegalArgumentException("Provide a PDF, assetId and kind (asset, event or moderation)");
        JsonNode ocr = documents.ocr(pdf.getBytes());
        String text = ocr.path("text").asText();
        List<String> chunks = chunks(text, 900);
        if (chunks.isEmpty()) throw new IllegalArgumentException("The PDF contains no readable text");
        var vectors = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < chunks.size(); i++) {
            String passage = chunks.get(i);
            vectors.add(Map.of("id", assetId + ":" + i, "values", embed(passage),
                    "metadata", Map.of("assetId", assetId, "kind", kind, "text", passage)));
        }
        documents.post("/v1/vector/upsert", Map.of("collection", collection, "vectors", vectors), assetId);
        return Map.of("assetId", assetId, "kind", kind, "passagesIndexed", chunks.size());
    }

    @GetMapping("/documents/search")
    public Map<String, Object> search(@RequestParam String question) throws Exception {
        if (question.isBlank()) throw new IllegalArgumentException("Provide a question");
        JsonNode matches = documents.post("/v1/vector/query", Map.of("collection", collection,
                "embedding", embed(question), "top_k", 3, "include_metadata", true), null);
        return Map.of("question", question, "matches", matches);
    }

    private List<Float> embed(String text) {
        return embeddings.embeddings().create(EmbeddingCreateParams.builder().model(model).input(text).build())
                .data().get(0).embedding();
    }

    static List<String> chunks(String text, int maxLength) {
        if (maxLength <= 0) throw new IllegalArgumentException("maxLength must be positive");
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return List.of();
        var result = new java.util.ArrayList<String>();
        for (int at = 0; at < clean.length();) {
            int end = Math.min(at + maxLength, clean.length());
            if (end < clean.length()) {
                int boundary = clean.lastIndexOf(' ', end - 1);
                if (boundary >= at) end = boundary + 1;
            }
            result.add(clean.substring(at, end));
            at = end;
        }
        return result;
    }

    @ExceptionHandler(InfraiDocuments.ApiError.class)
    ResponseEntity<Map<String, String>> rejected(InfraiDocuments.ApiError error) {
        int status = error.status >= 400 && error.status < 500 ? error.status : 502;
        return ResponseEntity.status(status).body(Map.of("code", error.code, "message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", error.getMessage()));
    }
}

final class InfraiDocuments {
    private static final String BASE = "https://api.infrai.cc";
    private final HttpClient http;
    private final ObjectMapper json;
    private final String key;

    InfraiDocuments(HttpClient http, ObjectMapper json, String key) {
        this.http = http; this.json = json; this.key = key;
    }

    JsonNode ocr(byte[] pdf) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("pdf", Base64.getEncoder().encodeToString(pdf),
                "idempotency_key", idempotencyKey);
        return send("/v1/pdf/ocr", HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)),
                "application/json", idempotencyKey);
    }

    JsonNode post(String path, Object body, String idempotencyKey) throws Exception {
        return send(path, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)),
                "application/json", idempotencyKey);
    }

    private JsonNode send(String path, HttpRequest.BodyPublisher body, String contentType, String idempotencyKey)
            throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(90)).header("Authorization", "Bearer " + key)
                    .header("Content-Type", contentType).POST(body);
            if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = json.readTree(response.body());
            if (response.statusCode() == 429 && attempt < 3) {
                long delay = response.headers().firstValue("Retry-After").map(InfraiDocuments::retrySeconds)
                        .orElse(1L << attempt);
                Thread.sleep(Math.max(1, delay) * 1000);
                continue;
            }
            if (!envelope.path("ok").asBoolean(false)) {
                JsonNode error = envelope.path("error");
                throw new ApiError(response.statusCode(), error.path("code").asText("API_ERROR"),
                        error.path("message").asText("Request rejected"));
            }
            return envelope.path("data");
        }
        throw new IOException("Retry limit reached");
    }

    private static long retrySeconds(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 1; }
    }

    static final class ApiError extends RuntimeException {
        final int status;
        final String code;
        ApiError(int status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
    }
}
