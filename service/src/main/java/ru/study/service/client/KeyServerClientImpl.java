package ru.study.service.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.study.core.dto.KeyDTO;
import ru.study.core.exception.CoreException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class KeyServerClientImpl implements KeyServerClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI base;

    public KeyServerClientImpl(String baseUrl) {
        this.http = HttpClient.newBuilder().build();
        this.mapper = new ObjectMapper();
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl);
    }

    @Override
    public Optional<KeyDTO> findKeyByEmail(String email) throws CoreException {
        try {
            String q = URLEncoder.encode(email, StandardCharsets.UTF_8);
            URI u = base.resolve("/api/keys?email=" + q);
            HttpRequest r = HttpRequest.newBuilder(u).GET().build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return Optional.empty();
            if (resp.statusCode() >= 400) throw new CoreException("Key server returned " + resp.statusCode() + ": " + resp.body());
            List<KeyDTO> list = mapper.readValue(resp.body(), new TypeReference<>(){});
            return list.stream().findFirst();
        } catch (IOException | InterruptedException e) {
            throw new CoreException("findKeyByEmail failed", e);
        }
    }

    @Override
    public KeyDTO uploadPublicKey(String email, String publicKeyPem) throws CoreException {
        try {
            URI u = base.resolve("/api/keys");
            var node = mapper.createObjectNode();
            node.put("email", email);
            node.put("publicKeyPem", publicKeyPem);
            String body = mapper.writeValueAsString(node);
            HttpRequest req = HttpRequest.newBuilder(u)
                    .header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) throw new CoreException("upload failed: " + resp.statusCode() + " " + resp.body());
            return mapper.readValue(resp.body(), KeyDTO.class);
        } catch (IOException | InterruptedException e) {
            throw new CoreException("uploadPublicKey failed", e);
        }
    }

    @Override
    public void verifyKeyByToken(String token) throws CoreException {
        try {
            // server exposes POST /api/keys/verify?token=...
            URI u = base.resolve("/api/keys/verify?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(u)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) throw new CoreException("verify failed: " + resp.statusCode() + " " + resp.body());
        } catch (IOException | InterruptedException e) {
            throw new CoreException("verifyKeyByToken failed", e);
        }
    }
}
