package com.basir.inventory.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Base64;

@ConfigurationProperties("ajwa")
public record AppProperties(
        String shopName,
        String providerPhone,
        String providerWhatsapp,
        String signPublic,
        String boxPublic,
        String dataDir,
        String version,
        Relay relay
) {
    /** Shop time; the MySQL export writes DATETIME columns in this zone too. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");

    public record Relay(String endpoint, String token) {
        public boolean configured() {
            return endpoint != null && endpoint.startsWith("https://");
        }
    }

    public byte[] signPublicKey() {
        return Base64.getDecoder().decode(signPublic);
    }

    public byte[] boxPublicKey() {
        return Base64.getDecoder().decode(boxPublic);
    }

    public Path dataPath() {
        return Path.of(dataDir).toAbsolutePath();
    }
}
