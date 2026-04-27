package com.vistora.discovery.monitor.agent.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistora.discovery.monitor.model.dto.BrowserExtension;
import com.vistora.discovery.monitor.model.dto.ShadowAiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.stream.Stream;

public class BrowserCollector {

    private static final Logger log = LoggerFactory.getLogger(BrowserCollector.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CopyOnWriteArrayList<ShadowAiEvent> collectedShadowAiEvents = new CopyOnWriteArrayList<>();
    private final NetworkCollector networkCollector = new NetworkCollector();
    private HttpServer server;

    public BrowserCollector() {
        startHttpServer();
    }

    private void startHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8078), 0);
            server.createContext("/events", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

                    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(204, -1);
                    } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        System.out.println("[Agent Debug] Incoming POST to /events from browser extension...");
                        try (java.io.InputStream is = exchange.getRequestBody()) {
                            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            ShadowAiEvent event = objectMapper.readValue(body, ShadowAiEvent.class);
                            
                            // Fusion Step
                            Integer browserPid = null;
                            String destinationIp = null;
                            try {
                                java.net.URL urlObj = new java.net.URL(event.url());
                                String host = urlObj.getHost();
                                java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
                                List<String> ips = new java.util.ArrayList<>();
                                for (java.net.InetAddress addr : addrs) {
                                    ips.add(addr.getHostAddress());
                                }
                                
                                NetworkCollector.CorrelationResult corr = networkCollector.findBrowserPidForIps(ips);
                                if (corr != null) {
                                    browserPid = corr.pid();
                                    destinationIp = corr.matchedIp();
                                    log.info("Correlated Shadow AI Event to Chrome PID: {} and IP: {}", browserPid, destinationIp);
                                }
                            } catch (Exception ex) {
                                log.warn("Failed to correlate network socket: {}", ex.getMessage());
                            }

                            ShadowAiEvent enriched = new ShadowAiEvent(
                                    event.method(), event.url(), event.prompt(), event.model(),
                                    event.timestamp(), event.promptTokenEstimate(), event.userId(),
                                    event.deviceId(), event.browser(), event.sensitivityScore(),
                                    event.actionType(), event.fileName(), event.fileSize(), event.fileType(),
                                    browserPid, destinationIp
                            );
                            
                            collectedShadowAiEvents.add(enriched);
                            if ("prompt".equalsIgnoreCase(enriched.actionType())) {
                                log.info("Received Shadow AI Prompt | Model: {} | Text: {}", enriched.model(), enriched.prompt());
                            } else {
                                log.info("Received Shadow AI {} | File: {} | Size: {} bytes | Type: {}", 
                                        enriched.actionType().toUpperCase(), enriched.fileName(), enriched.fileSize(), enriched.fileType());
                            }
                            exchange.sendResponseHeaders(202, -1);
                        } catch (Exception e) {
                            log.error("Failed to parse Shadow AI event: {}", e.getMessage());
                            exchange.sendResponseHeaders(400, -1);
                        }
                    } else {
                        exchange.sendResponseHeaders(405, -1);
                    }
                    exchange.close();
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
            server.start();
            log.info("BrowserCollector HTTP Server started on 127.0.0.1:8078");
        } catch (IOException e) {
            log.error("Failed to start BrowserCollector HTTP Server: {}", e.getMessage());
        }
    }

    public List<ShadowAiEvent> getAndClearShadowAiEvents() {
        List<ShadowAiEvent> flush = new java.util.ArrayList<>(collectedShadowAiEvents);
        if (!flush.isEmpty()) {
            log.info("Flushing {} Shadow AI events to Agent.", flush.size());
        }
        collectedShadowAiEvents.removeAll(flush);
        return flush;
    }

    public List<BrowserExtension> collect() {
        List<BrowserExtension> extensions = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();

        // Define paths for Chrome, Edge, and Brave
        List<BrowserProfile> profiles = new ArrayList<>();

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            findProfiles(profiles, "CHROME", Paths.get(localAppData, "Google", "Chrome", "User Data"));
            findProfiles(profiles, "EDGE", Paths.get(localAppData, "Microsoft", "Edge", "User Data"));
            findProfiles(profiles, "BRAVE", Paths.get(localAppData, "BraveSoftware", "Brave-Browser", "User Data"));
        } else {
            String userHome = System.getProperty("user.home");
            findProfiles(profiles, "CHROME", Paths.get(userHome, "Library", "Application Support", "Google", "Chrome"));
            findProfiles(profiles, "EDGE", Paths.get(userHome, "Library", "Application Support", "Microsoft Edge"));
            findProfiles(profiles, "BRAVE", Paths.get(userHome, "Library", "Application Support", "BraveSoftware", "Brave-Browser"));
            findProfiles(profiles, "COMET", Paths.get(userHome, "Library","Application Support", "Comet"));
            findProfiles(profiles, "Mozilla Firefox", Paths.get(userHome, "Library","Application Support", "Firefox"));
            findProfiles(profiles, "SAFARI", Paths.get(userHome, "Library","Application Support", "Safari"));
        }

        for (BrowserProfile profile : profiles) {
            if (!isBrowserRunning(profile.name)) {
                log.debug("Skipping {} profile because browser is not running", profile.name);
                continue;
            }

            Path extensionsPath = profile.path.resolve("Extensions");
            Path localExtensionSettingsPath = profile.path.resolve("Local Extension Settings");

            if (Files.exists(extensionsPath)) {
                File[] extensionDirs = extensionsPath.toFile().listFiles(File::isDirectory);
                if (extensionDirs != null) {
                    for (File dir : extensionDirs) {
                        String extensionId = dir.getName();
                        String extensionName = getExtensionName(dir.toPath());
                        LocalDateTime lastActive = getLastActiveTime(localExtensionSettingsPath, extensionId);

                        extensions
                                .add(new BrowserExtension(profile.name, extensionId, extensionName, true, lastActive, "Browser extension"));
                    }
                }
            }
        }
        return extensions;
    }

    private boolean isBrowserRunning(String browserType) {
        String processName;
        switch (browserType) {
            case "CHROME": processName = "chrome"; break;
            case "EDGE": processName = "msedge"; break;
            case "BRAVE": processName = "brave"; break;
            default: return false;
        }

        return ProcessHandle.allProcesses().anyMatch(p -> {
            String cmd = p.info().command().orElse("").toLowerCase();
            return cmd.contains(processName);
        });
    }

    private static class BrowserProfile {
        String name;
        Path path;

        BrowserProfile(String name, Path path) {
            this.name = name;
            this.path = path;
        }
    }

    private String getExtensionName(Path extensionDir) {
        try (Stream<Path> stream = Files.list(extensionDir)) {
            Optional<Path> versionDir = stream.filter(Files::isDirectory).findFirst();
            if (versionDir.isPresent()) {
                Path vDir = versionDir.get();
                File manifestFile = vDir.resolve("manifest.json").toFile();
                if (manifestFile.exists()) {
                    JsonNode manifest = objectMapper.readTree(manifestFile);
                    if (manifest.has("name")) {
                        String name = manifest.get("name").asText();

                        if (name.startsWith("__MSG_") && name.endsWith("__")) {
                            name = resolveI18nName(vDir, name);
                        }
                        return name;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read manifest for extension at {}: {}", extensionDir, e.getMessage());
        }
        return "Chrome Extension";
    }

    private String resolveI18nName(Path versionDir, String msgKey) {
        String key = msgKey.substring(6, msgKey.length() - 2).toLowerCase();

        String[] localesToTry = { "en", "en_US", "en_GB" };
        for (String locale : localesToTry) {
            Path messagesFile = versionDir.resolve("_locales").resolve(locale).resolve("messages.json");
            String resolved = readMessageFromFile(messagesFile, key);
            if (resolved != null)
                return resolved;
        }

        Path localesDir = versionDir.resolve("_locales");
        if (Files.exists(localesDir)) {
            try (Stream<Path> localeStream = Files.list(localesDir)) {
                Optional<Path> anyLocale = localeStream.filter(Files::isDirectory).findFirst();
                if (anyLocale.isPresent()) {
                    Path messagesFile = anyLocale.get().resolve("messages.json");
                    String resolved = readMessageFromFile(messagesFile, key);
                    if (resolved != null)
                        return resolved;
                }
            } catch (IOException e) {
                log.warn("Failed to list locales: {}", e.getMessage());
            }
        }

        return msgKey;
    }

    private String readMessageFromFile(Path messagesFile, String key) {
        if (Files.exists(messagesFile)) {
            try {
                JsonNode messages = objectMapper.readTree(messagesFile.toFile());

                var fields = messages.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if (entry.getKey().toLowerCase().equals(key)) {
                        JsonNode messageNode = entry.getValue();
                        if (messageNode.has("message")) {
                            return messageNode.get("message").asText();
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read messages file {}: {}", messagesFile, e.getMessage());
            }
        }
        return null;
    }

    private LocalDateTime getLastActiveTime(Path localExtensionSettingsPath, String extensionId) {
        Path settingsDir = localExtensionSettingsPath.resolve(extensionId);
        if (Files.exists(settingsDir) && Files.isDirectory(settingsDir)) {
            try (Stream<Path> stream = Files.walk(settingsDir)) {
                Optional<FileTime> latestTime = stream
                        .filter(Files::isRegularFile)
                        .map(p -> {
                            try {
                                return Files.getLastModifiedTime(p);
                            } catch (IOException e) {
                                return FileTime.fromMillis(0);
                            }
                        })
                        .max(Comparator.naturalOrder());

                if (latestTime.isPresent()) {
                    return LocalDateTime.ofInstant(latestTime.get().toInstant(), ZoneId.systemDefault());
                }
            } catch (IOException e) {
                log.warn("Failed to read local extension settings for {}: {}", extensionId, e.getMessage());
            }
        }
        return null;
    }

    private void findProfiles(List<BrowserProfile> profiles, String name, Path basePath) {
        if (!Files.exists(basePath)) {
            log.info("Base path for {} doesn't exist: {}", name, basePath);
            return;
        }

        // Check if there's a "User Data" subfolder (common on Windows, sometimes on
        // Mac)
        Path userDataPath = basePath.resolve("User Data");
        Path searchPath = Files.exists(userDataPath) ? userDataPath : basePath;

        log.info("Scanning for {} profiles in: {}", name, searchPath);

        try (Stream<Path> stream = Files.list(searchPath)) {
            stream.filter(Files::isDirectory)
                    .forEach(p -> {
                        String folderName = p.getFileName().toString();
                        if (folderName.equals("Default") || folderName.startsWith("Profile ")) {
                            profiles.add(new BrowserProfile(name, p));
                            log.info("Discovered {} browser profile at {}", name, p);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list browser profiles in {}: {}", searchPath, e.getMessage());
        }
    }
}
