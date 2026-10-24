package com.jarvis.commandbridge;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class BridgeConfig {
    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir().resolve("termux-command-bridge.properties");

    private final String baseUrl;
    private final String token;
    private final long pollMillis;

    private BridgeConfig(String baseUrl, String token, long pollMillis) {
        String cleanedUrl = baseUrl == null ? "" : baseUrl.trim();
        while (cleanedUrl.endsWith("/")) {
            cleanedUrl = cleanedUrl.substring(0, cleanedUrl.length() - 1);
        }
        this.baseUrl = cleanedUrl;
        this.token = token == null ? "" : token.trim();
        this.pollMillis = Math.max(500L, Math.min(5000L, pollMillis));
    }

    static BridgeConfig loadOrCreate() {
        Properties properties = new Properties();
        try {
            Files.createDirectories(FILE.getParent());
            if (!Files.exists(FILE)) {
                properties.setProperty("baseUrl", "http://127.0.0.1:8765");
                properties.setProperty("token", "PASTE_TOKEN_HERE");
                properties.setProperty("pollMillis", "750");
                try (OutputStream output = Files.newOutputStream(FILE)) {
                    properties.store(output, "Termux Command Bridge settings. Restart Minecraft after editing.");
                }
            }
            try (InputStream input = Files.newInputStream(FILE)) {
                properties.load(input);
            }
        } catch (IOException exception) {
            TermuxCommandBridgeClient.LOGGER.error("Could not load bridge config at {}", FILE, exception);
        }

        String baseUrl = properties.getProperty("baseUrl", "http://127.0.0.1:8765");
        String token = properties.getProperty("token", "");
        long pollMillis = 750L;
        try {
            pollMillis = Long.parseLong(properties.getProperty("pollMillis", "750").trim());
        } catch (NumberFormatException ignored) {
            TermuxCommandBridgeClient.LOGGER.warn("Invalid pollMillis in config; using 750 ms.");
        }
        return new BridgeConfig(baseUrl, token, pollMillis);
    }

    boolean isConfigured() {
        return baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
                ? !token.isBlank() && !token.equalsIgnoreCase("PASTE_TOKEN_HERE")
                : false;
    }

    String endpoint(String path) {
        return baseUrl + path;
    }

    String token() {
        return token;
    }

    long pollMillis() {
        return pollMillis;
    }

    String displayUrl() {
        return baseUrl;
    }
}
