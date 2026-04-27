package com.vistora.discovery.monitor.agent.collector;

import com.vistora.discovery.monitor.model.dto.VsCodeExtension;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class IDECollector {

    public List<VsCodeExtension> collect() {
        List<VsCodeExtension> extensions = new ArrayList<>();
        String userHome = System.getProperty("user.home");

        // VS Code Extensions - only recently used (modified within last 30 days)
        Path extensionsPath = Paths.get(userHome, ".vscode", "extensions");
        if (Files.exists(extensionsPath)) {
            File[] folders = extensionsPath.toFile().listFiles(File::isDirectory);
            if (folders != null) {
                for (File folder : folders) {
                    if (isRecentlyUsed(folder)) {
                        extensions.add(new VsCodeExtension(folder.getName(), "unknown", true, "VS Code", "IDE tool"));
                    }
                }
            }
        }

        // Cursor Extensions - only recently used
        Path cursorExtensions = Paths.get(userHome, ".cursor", "extensions");
        if (Files.exists(cursorExtensions)) {
            File[] folders = cursorExtensions.toFile().listFiles(File::isDirectory);
            if (folders != null) {
                for (File folder : folders) {
                    if (isRecentlyUsed(folder)) {
                        extensions.add(new VsCodeExtension(folder.getName(), "unknown", true, "Cursor", "IDE tool"));
                    }
                }
            }
        }

        // Windsurf Extensions - only recently used
        Path windsurfExtensions = Paths.get(userHome, ".windsurf", "extensions");
        if (Files.exists(windsurfExtensions)) {
            File[] folders = windsurfExtensions.toFile().listFiles(File::isDirectory);
            if (folders != null) {
                for (File folder : folders) {
                    if (isRecentlyUsed(folder)) {
                        extensions.add(new VsCodeExtension(folder.getName(), "unknown", true, "Windsurf", "IDE tool"));
                    }
                }
            }
        }

        // IntelliJ
        List<Path> jetbrainsSearchPaths = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            jetbrainsSearchPaths.add(Paths.get(appData, "JetBrains"));
        }
        String userHomeLib = System.getProperty("user.home");
        if (userHomeLib != null) {
            jetbrainsSearchPaths.add(Paths.get(userHomeLib, "Library", "Application Support", "JetBrains"));
        }

        for (Path jetbrainsDir : jetbrainsSearchPaths) {
            if (Files.exists(jetbrainsDir)) {
                File[] jetbrainsProducts = jetbrainsDir.toFile().listFiles(File::isDirectory);
                if (jetbrainsProducts != null) {
                    for (File productDir : jetbrainsProducts) {
                        String productName = productDir.getName(); // e.g. "IntelliJIdea2025.3"

                       
                        String ideName = formatIdeName(productName);

                        if (productName.startsWith("IntelliJIdea") || productName.startsWith("WebStorm")
                                || productName.startsWith("PyCharm") || productName.startsWith("GoLand")
                                || productName.startsWith("Rider") || productName.startsWith("CLion")) {

                            Path pluginsDir = productDir.toPath().resolve("plugins");
                            if (Files.exists(pluginsDir)) {
                                File[] plugins = pluginsDir.toFile().listFiles();
                                if (plugins != null) {
                                    for (File plugin : plugins) {
                                        if (isRecentlyUsed(plugin)) {
                                            extensions.add(new VsCodeExtension(plugin.getName(), "unknown", true, ideName, "IDE tool"));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return extensions;
    }

    private boolean isRecentlyUsed(File file) {
        try {
            // Check if file/folder was modified within last 30 days
            long lastModified = file.lastModified();
            long thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
            return lastModified >= thirtyDaysAgo;
        } catch (Exception e) {
            // If can't determine, include it anyway
            return true;
        }
    }

    private String formatIdeName(String dirName) {
        // "IntelliJIdea2025.3" -> "IntelliJ IDEA 2025.3"
        if (dirName.startsWith("IntelliJIdea")) {
            return "IntelliJ IDEA " + dirName.substring("IntelliJIdea".length());
        } else if (dirName.startsWith("WebStorm")) {
            return "WebStorm " + dirName.substring("WebStorm".length());
        } else if (dirName.startsWith("PyCharm")) {
            return "PyCharm " + dirName.substring("PyCharm".length());
        } else if (dirName.startsWith("GoLand")) {
            return "GoLand " + dirName.substring("GoLand".length());
        } else if (dirName.startsWith("Rider")) {
            return "Rider " + dirName.substring("Rider".length());
        } else if (dirName.startsWith("CLion")) {
            return "CLion " + dirName.substring("CLion".length());
        }
        return dirName;
    }
}
