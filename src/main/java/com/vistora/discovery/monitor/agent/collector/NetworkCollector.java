package com.vistora.discovery.monitor.agent.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class NetworkCollector {
    private static final Logger log = LoggerFactory.getLogger(NetworkCollector.class);

    public record TcpConnection(String localIp, String remoteIp, int pid) {}
    public record CorrelationResult(int pid, String matchedIp) {}

    public List<TcpConnection> getActiveConnections() {
        List<TcpConnection> connections = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("netstat", "-ano").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("TCP") || line.startsWith("UDP")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 4) {
                            String local = parts[1];
                            String remote = parts[2];
                            String pidStr = parts[parts.length - 1]; // Last token is always PID

                            // Ensure it's an established TCP stream, or a UDP stream to a remote host (QUIC)
                            if (line.startsWith("TCP") && !line.contains("ESTABLISHED")) {
                                continue;
                            }

                            if (!remote.startsWith("*")) {
                                String remoteIp = extractIp(remote);
                                if (remoteIp != null) {
                                    try {
                                        connections.add(new TcpConnection(local, remoteIp, Integer.parseInt(pidStr)));
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to run netstat: {}", e.getMessage());
        }
        return connections;
    }

    private String extractIp(String address) {
        if (address == null || address.isEmpty()) return null;
        if (address.startsWith("[")) {
            int endBracket = address.indexOf("]");
            return endBracket > 0 ? address.substring(1, endBracket) : null;
        } else {
            int colonIndex = address.lastIndexOf(":");
            return colonIndex > 0 ? address.substring(0, colonIndex) : address;
        }
    }

    public CorrelationResult findBrowserPidForIps(List<String> targetIps) {
        List<TcpConnection> conns = getActiveConnections();
        for (TcpConnection conn : conns) {
            if (targetIps.contains(conn.remoteIp())) {
                Optional<ProcessHandle> ph = ProcessHandle.of(conn.pid());
                if (ph.isPresent()) {
                    String cmd = ph.get().info().command().orElse("").toLowerCase();
                    if (cmd.contains("chrome") || cmd.contains("msedge") || cmd.contains("brave")) {
                        return new CorrelationResult(conn.pid(), conn.remoteIp());
                    }
                }
            }
        }
        return null;
    }
}
