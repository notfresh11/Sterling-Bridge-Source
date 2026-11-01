package net.sterling.bridge.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public class ModrinthService {

    private final HttpClient httpClient;

    public ModrinthService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Optional<String> getDirectDownloadUrlByHash(String sha256Hash) {
        try {
            String url = "https://api.modrinth.com/v2/version_file/" + sha256Hash;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "SterlingBridge/2.0")
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("files") && json.getAsJsonArray("files").size() > 0) {
                    JsonObject fileObj = json.getAsJsonArray("files").get(0).getAsJsonObject();
                    if (fileObj.has("url")) {
                        return Optional.of(fileObj.get("url").getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
            // Use the local download URL if Modrinth lookup fails.
        }
        return Optional.empty();
    }
}
