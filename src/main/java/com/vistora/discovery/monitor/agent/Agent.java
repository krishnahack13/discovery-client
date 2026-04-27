package com.vistora.discovery.monitor.agent;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vistora.discovery.monitor.agent.collector.*;
import com.vistora.discovery.monitor.agent.service.CryptoService;
import com.vistora.discovery.monitor.agent.service.PrivacyFilter;
import com.vistora.discovery.monitor.agent.service.VaultService;
import com.vistora.discovery.monitor.model.dto.*;

public class Agent {

    // Middle-Agent Router Mapping (Dynamically Loaded from System Environment)
    // Points to the middle-agent ingestion endpoint: POST /grafyn-agent/info
    // Override via env var GRAFYN_AGENT_URL for staging/prod (e.g. https://your-ngrok-url/grafyn-agent/grafyn-agent/info)
    private static final String GRAFYN_AGENT_URL = System.getenv().getOrDefault("GRAFYN_AGENT_URL",
            "http://127.0.0.1:8082/grafyn-agent/grafyn-agent/info");

    private static final String DEVICE_ID = System.getProperty("os.name") + "-" + System.getProperty("user.name");

    private final ProcessCollector processCollector = new ProcessCollector();
    private final IDECollector ideCollector = new IDECollector();
    private final BrowserCollector browserCollector = new BrowserCollector();
    private final LocalLLMDetector localLLMDetector = new LocalLLMDetector();
    private final EnvVariableCollector envVariableCollector = new EnvVariableCollector();
    private final UserIdentityCollector userIdentityCollector = new UserIdentityCollector();
    private final AgenticToolCollector agenticToolCollector = new AgenticToolCollector();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final CryptoService cryptoService;
    private final VaultService vaultService;
    private final PrivacyFilter privacyFilter = new PrivacyFilter();

    private volatile boolean monitoringPaused = false;
    private volatile int intervalMinutes = 1;
    private ScheduledExecutorService scheduler;

    public Agent() {
        this.cryptoService = new CryptoService(DEVICE_ID);
        this.vaultService = new VaultService(cryptoService);
    }

    public void start() {
        System.out.println("Agent started securely for: " + DEVICE_ID);
        System.out.println("Local Vault initialized for zero-loss telemetry.");

        this.scheduler = Executors.newScheduledThreadPool(2);

        // Shutdown hook: fires when the user manually kills the agent (Task Manager,
        // Ctrl+C, OS signal, etc.) and sends a trigger message to the server.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Agent] Shutdown detected — sending AGENT_MANUALLY_DISABLED alert...");
            sendDisabledAlert();
            System.out.println("[Agent] Disable alert dispatched. Goodbye.");
        }, "agent-shutdown-hook"));

        // Loop 1: Data Collection (Store) - Immediate first scan, then
        // self-rescheduling
        scheduler.execute(() -> {
            collectAndStore();
            scheduleNextCollection();
        });

        // Loop 2: Data Synchronization (Forward)
        scheduler.scheduleAtFixedRate(this::syncVault, 30, 30, TimeUnit.SECONDS);
    }

    private void scheduleNextCollection() {
        scheduler.schedule(() -> {
            collectAndStore();
            scheduleNextCollection(); // Wait for the specified interval before next scan
        }, intervalMinutes, TimeUnit.MINUTES);
    }

    private void collectAndStore() {
        if (monitoringPaused) {
            System.out.println("Monitoring is currently PAUSED by remote command. Skipping scan.");
            return;
        }

        try {
            System.out.println("Collecting system response...");

            List<ProcessInfo> processes = privacyFilter.filterProcesses(processCollector.collect());
            List<VsCodeExtension> ides = privacyFilter.filterVsCodeExtensions(ideCollector.collect());
            List<BrowserExtension> extensions = privacyFilter.filterBrowserExtensions(browserCollector.collect());
            List<ShadowAiEvent> shadowAiEvents = privacyFilter.filterShadowAi(browserCollector.getAndClearShadowAiEvents());
            System.out
                    .println("[Agent] Collected " + shadowAiEvents.size() + " Shadow AI events from BrowserCollector.");

            if (!shadowAiEvents.isEmpty()) {
                for (ShadowAiEvent event : shadowAiEvents) {
                    System.out.println("[Agent] Loading Shadow AI Data -> Type: " + event.actionType() +
                            ", Model: " + event.model() + ", Timestamp: " + event.timestamp());
                }
            }

            // Zero-Config: Scan all dot-folders and default LLM ports
            List<ConfigFileInfo> configs = localLLMDetector.detectConfigDirectories();
            List<Integer> openPorts = localLLMDetector.detectOpenPorts();
            List<String> envVars = envVariableCollector.collect();
            List<AgenticTool> agenticTools = agenticToolCollector.collect();

            String hostname = InetAddress.getLocalHost().getHostName();
            String os = System.getProperty("os.name");
            String osVersion = System.getProperty("os.version");
            String agentVersion = "1.2.0"; // Upgraded version for Agentic tool support

            UserIdentity identity = userIdentityCollector.collect();

            DeviceResponse response = new DeviceResponse(
                    DEVICE_ID,
                    hostname,
                    os,
                    osVersion,
                    agentVersion,
                    LocalDateTime.now(),
                    processes,
                    List.<InstalledApp>of(), // no installed apps collector yet
                    ides,
                    extensions,
                    configs,
                    openPorts,
                    envVars,
                    shadowAiEvents,
                    agenticTools,
                    identity,
                    "ACTIVE");

            String jsonPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);

            // Securely Vault the payload locally for guaranteed delivery
            vaultService.saveToVault(jsonPayload);
            System.out.println("Telemetry scan stored securely in local vault.");

        } catch (Exception e) {
            System.err.println("CRITICAL FAILURE: Error collecting system telemetry.");
            e.printStackTrace();
        }
    }

    private synchronized void syncVault() {
        List<VaultService.VaultFile> scans = vaultService.getPendingScans();
        if (scans.isEmpty())
            return;

        System.out.println("Syncing " + scans.size() + " pending scans from vault...");

        for (VaultService.VaultFile scan : scans) {
            try {
                String encryptedPayload = vaultService.readRaw(scan.getFileName());

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GRAFYN_AGENT_URL))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString(encryptedPayload))
                        .build();

                HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (httpResponse.statusCode() == 200 || httpResponse.statusCode() == 201) {
                    System.out.println("Successfully uploaded scan: " + scan.getFileName());
                    vaultService.deleteFromVault(scan.getFileName());

                    // NEW: Check for remote commands in the response
                    handleRemoteCommand(httpResponse.body());
                } else {
                    System.err.println("Middle-Agent rejected scan [" + scan.getFileName() + "]. Code: "
                            + httpResponse.statusCode());
                    break; // Stop and retry later if server is erroring
                }
            } catch (Exception e) {
                System.err.println("Network unavailable or vault error for: " + scan.getFileName());
                break; // Stop and retry next cycle
            }
        }
    }

    private void handleRemoteCommand(String responseBody) {
        if (responseBody == null || responseBody.isBlank())
            return;

        try {
            // Check for commands like: {"command": "PAUSE"}, {"command": "RESUME"},
            // {"command": "REFRESH"}
            var node = objectMapper.readTree(responseBody);
            if (node.has("command")) {
                String cmd = node.get("command").asText().toUpperCase();
                System.out.println(">>> REMOTE COMMAND RECEIVED: " + cmd);

                switch (cmd) {
                    case "PAUSE":
                        this.monitoringPaused = true;
                        break;
                    case "RESUME":
                    case "START":
                        this.monitoringPaused = false;
                        break;
                    case "REFRESH":
                        // Immediately trigger a scan in a separate thread
                        new Thread(this::collectAndStore).start();
                        break;
                    case "STOP":
                        System.out.println("CRITICAL: Remote shutdown command received. Terminating Agent.");
                        System.exit(0);
                        break;
                    case "UPDATE_INTERVAL":
                        if (node.has("value")) {
                            int newInterval = node.get("value").asInt();
                            if (newInterval > 0) {
                                System.out.println("Changing scan interval to: " + newInterval + " minutes.");
                                this.intervalMinutes = newInterval;
                            }
                        }
                        break;
                    default:
                        System.out.println("Unknown command: " + cmd);
                }
            }
        } catch (Exception e) {
            // Silently ignore if response isn't a command JSON
        }
    }

    /**
     * Sends a best-effort alert to the middle-agent when the agent process is
     * manually disabled by the user (via shutdown hook). Uses a synchronous
     * HTTP call so the message goes out before the JVM exits.
     * Posts to the same GRAFYN_AGENT_URL as regular telemetry.
     */
    private void sendDisabledAlert() {
        try {
            // Generate a lightweight DeviceResponse with the disabled status
            DeviceResponse response = new DeviceResponse(
                    DEVICE_ID,
                    InetAddress.getLocalHost().getHostName(),
                    System.getProperty("os.name"),
                    System.getProperty("os.version"),
                    "1.2.0",
                    LocalDateTime.now(),
                    List.of(), // processes
                    List.of(), // apps
                    List.of(), // vscode
                    List.of(), // browser
                    List.of(), // configs
                    List.of(), // ports
                    List.of(), // envVars
                    List.of(), // shadowAiEvents
                    List.of(), // agenticTools
                    null,      // user identity
                    "MANUALLY_DISABLED");

            String payload = objectMapper.writeValueAsString(response);

            // Encrypt and vault first (so it survives even if the HTTP call fails)
            vaultService.saveToVault(payload);

            // Then attempt a live HTTP push to the same telemetry endpoint
            String encryptedPayload = cryptoService.encrypt(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GRAFYN_AGENT_URL)) // same URL as telemetry sync
                    .header("Content-Type", "text/plain")
                    .header("X-Event-Type", "AGENT_MANUALLY_DISABLED")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(encryptedPayload))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Agent] Disable alert response: HTTP " + httpResponse.statusCode());
        } catch (Exception e) {
            // Best-effort — log but don't block JVM shutdown
            System.err.println("[Agent] Failed to send disable alert: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        new Agent().start();
    }
}
