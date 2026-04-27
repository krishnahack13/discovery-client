package com.vistora.discovery.monitor.agent.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the local file-based vault for telemetry durability.
 * Stores encrypted scans until they can be successfully uploaded.
 */
public class VaultService {

    private final Path vaultPath;
    private final CryptoService cryptoService;

    public VaultService(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
        
        // Determine the best hidden path based on OS
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        
        if (os.contains("win")) {
            this.vaultPath = Paths.get(System.getenv("LOCALAPPDATA"), "Vistora", "vault");
        } else {
            this.vaultPath = Paths.get(userHome, ".vistora", "vault");
        }

        try {
            Files.createDirectories(this.vaultPath);
        } catch (java.io.IOException | SecurityException e) {
            System.err.println("CRITICAL: Could not create local vault directory at " + this.vaultPath);
        }
    }

    public String saveToVault(String jsonPayload) throws Exception {
        String encryptedData = cryptoService.encrypt(jsonPayload);
        String fileName = "scan_" + System.currentTimeMillis() + ".enc";
        Path filePath = vaultPath.resolve(fileName);
        
        Files.writeString(filePath, encryptedData, StandardCharsets.UTF_8);
        return fileName;
    }

    public List<VaultFile> getPendingScans() {
        try (Stream<Path> paths = Files.list(vaultPath)) {
            return paths
                .filter(p -> p.toString().endsWith(".enc"))
                .map(p -> new VaultFile(p.getFileName().toString(), p))
                .sorted(Comparator.comparing(VaultFile::getFileName)) // Process oldest first
                .collect(Collectors.toList());
        } catch (Exception e) {
            System.err.println("Error listing pending scans in vault: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Reads the encrypted file as-is (no decryption).
     * Used for sending encrypted blobs directly to the Middle-Agent.
     */
    public String readRaw(String fileName) throws Exception {
        Path filePath = vaultPath.resolve(fileName);
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    public void deleteFromVault(String fileName) {
        try {
            Files.deleteIfExists(vaultPath.resolve(fileName));
        } catch (java.io.IOException | SecurityException e) {
            System.err.println("Failed to delete processed scan: " + fileName);
        }
    }

    public static class VaultFile {
        private final String fileName;
        private final Path path;

        public VaultFile(String fileName, Path path) {
            this.fileName = fileName;
            this.path = path;
        }

        public String getFileName() { return fileName; }
        public Path getPath() { return path; }
    }
}
