package com.basir.inventory.web.backup;

import com.basir.inventory.web.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Posts JSON to the provider's Google Apps Script relay (backup-relay/Code.gs), which e-mails
 * backups and records heartbeats in the monitoring sheet. Same protocol as the phone.
 */
@Component
public class RelayClient {
    private static final Logger log = LoggerFactory.getLogger(RelayClient.class);

    private final AppProperties.Relay relay;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            // Apps Script answers a POST with a redirect to the result; follow it manually as GET.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public RelayClient(AppProperties props, ObjectMapper json) {
        this.relay = props.relay();
        this.json = json;
    }

    public boolean configured() {
        return relay != null && relay.configured();
    }

    public String token() {
        return relay == null ? "" : relay.token();
    }

    /** True when the relay answered {"ok":true}. */
    public boolean post(Map<String, Object> body) {
        if (!configured()) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(relay.endpoint()))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = response.headers().firstValue("Location").orElseThrow();
                response = http.send(HttpRequest.newBuilder(URI.create(location)).timeout(Duration.ofSeconds(90)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                code = response.statusCode();
            }
            boolean ok = code >= 200 && code < 300 && response.body().contains("\"ok\":true");
            if (!ok) log.warn("Relay answered {}: {}", code, abbreviate(response.body()));
            return ok;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Relay post failed: {}", e.toString());
            return false;
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
