package com.vistora.discovery.monitor.agent.collector;

import com.vistora.discovery.monitor.model.dto.AgenticTool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class AgenticToolCollector {

    public List<AgenticTool> collect() {
        List<AgenticTool> agenticTools = new ArrayList<>();
        String userHome = System.getProperty("user.home");

        // 1. Antigravity Detection
        Path antigravityPath = Paths.get(userHome, ".gemini", "antigravity");
        if (Files.exists(antigravityPath)) {
            Path conversationsPath = antigravityPath.resolve("conversations");
            int totalConversations = 0;
            long lastModifiedMillis = 0;

            if (Files.exists(conversationsPath)) {
                File[] folders = conversationsPath.toFile().listFiles(File::isDirectory);
                if (folders != null) {
                    totalConversations = folders.length;
                    for (File folder : folders) {
                        long modTime = folder.lastModified();
                        if (modTime > lastModifiedMillis) {
                            lastModifiedMillis = modTime;
                        }
                    }
                }
            }
            
            // If the conversations dir was empty but the root exists, check the root's modified time
            if (lastModifiedMillis == 0) {
                lastModifiedMillis = antigravityPath.toFile().lastModified();
            }

            String lastActive = "Unknown";
            if (lastModifiedMillis > 0) {
                lastActive = Instant.ofEpochMilli(lastModifiedMillis)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }

            String usageLevel = "Low";
            if (totalConversations > 20) {
                usageLevel = "High";
            } else if (totalConversations > 5) {
                usageLevel = "Medium";
            }

            agenticTools.add(new AgenticTool(
                    "Antigravity",
                    usageLevel,
                    totalConversations,
                    lastActive
            ));
        }

        // We can add paths for other tools here in the future
        // e.g. Cursor or Windsurf workspace footprint metrics if we want deep usage rather than just IDE extensions.

        return agenticTools;
    }
}
