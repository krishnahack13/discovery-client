// package com.vistora.discovery.monitor.agent.collector;

// import java.io.BufferedReader;
// import java.io.File;
// import java.io.FileReader;
// import java.io.FileWriter;
// import java.io.IOException;
// import java.io.InputStreamReader;
// import java.util.ArrayList;
// import java.util.Iterator;
// import java.util.List;
// import java.util.regex.Matcher;
// import java.util.regex.Pattern;

// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import com.fasterxml.jackson.databind.JsonNode;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.vistora.discovery.monitor.model.dto.UserIdentity;

// /**
//  * 9-Stage Powerhouse Identity Collector. Merges high-intrusion OS discovery
//  * with smart browser-based fallback.
//  */
// public class UserIdentityCollector {

//     private static final Logger log = LoggerFactory.getLogger(UserIdentityCollector.class);
//     private static final String UNKNOWN_EMAIL = "unknown@local";
//     private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6})");
//     private final ObjectMapper objectMapper = new ObjectMapper();

//     public UserIdentity collect() {
//         String os = System.getProperty("os.name").toLowerCase();
//         String localUser = System.getProperty("user.name");
//         String email = null;
//         String tenantName = "Local System";

//         log.info("=== UserIdentityCollector starting | OS: {} | user: {} ===", os, localUser);

//         try {
//             if (os.contains("win")) {
//                 email = getEmailFromWindows(localUser);

//                 // Identify tenant from Windows Active Directory status
//                 String dsregOutput = executeCommand(true, "dsregcmd /status");
//                 if (dsregOutput != null) {
//                     String workplaceTenant = extractValue(dsregOutput, "WorkplaceTenantName");
//                     if (workplaceTenant != null && !workplaceTenant.isEmpty()) {
//                         tenantName = workplaceTenant;
//                     }
//                 }
//             } else if (os.contains("mac")) {
//                 logOwnJavaPath();
//                 email = getEmailFromMac(localUser);

//                 // Check if device is enrolled in MDM
//                 String profileOutput = executeCommand(false, "profiles status -type enrollment");
//                 if (profileOutput != null && (profileOutput.contains("Enrolled via DEP: Yes") || profileOutput.contains("Organization"))) {
//                     tenantName = "Enterprise MDM (Managed)";
//                 }
//             }

//             // STAGE 10: Smart Fallback (Browser Profiles)
//             if (email == null || email.equals(UNKNOWN_EMAIL)) {
//                 log.info("[IDENTITY] OS-level discovery failed. Attempting Stage 10: Browser Smart Fallback...");
//                 email = getEmailFromBrowsers(os);
//             }

//         } catch (Exception e) {
//             log.error("Error collecting user identity: {}", e.getMessage());
//         }

//         log.info("=== UserIdentityCollector result | email: {} | tenant: {} ===",
//                 email != null ? email : UNKNOWN_EMAIL, tenantName);

//         return new UserIdentity(
//                 email != null ? email : UNKNOWN_EMAIL,
//                 tenantName,
//                 localUser
//         );
//     }

//     // ═════════════════════════════════════════════════════════════════════════
//     // WINDOWS (4-Stage)
//     // ═════════════════════════════════════════════════════════════════════════
//     private String getEmailFromWindows(String username) {
//         log.info("[WIN] Attempting Registry email lookup");

//         // W1 – Registry
//         String[] regKeys = {
//             "HKCU\\Software\\Microsoft\\IdentityCRL\\UserExtendedProperties",
//             "HKCU\\Software\\Microsoft\\Windows Live\\WinLive\\Users",
//             "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\ProfileList"
//         };
//         for (String key : regKeys) {
//             String result = executeCommand(true, "reg query \"" + key + "\" /s");
//             String email = extractEmailFromText(result);
//             if (email != null) {
//                 return email;
//             }
//         }

//         // W2 – PowerShell Secure Identity Check
//         String psCmd = "powershell -NoProfile -NonInteractive -Command \""
//                 + "try { $u = Get-LocalUser -Name $env:USERNAME; "
//                 + "if ($u.PrincipalSource -eq 'MicrosoftAccount') { "
//                 + "  $path = 'HKCU:\\Software\\Microsoft\\IdentityCRL\\UserExtendedProperties'; "
//                 + "  $props = Get-ItemProperty -Path $path -ErrorAction Stop; "
//                 + "  $props.PSObject.Properties | Where-Object { $_.Name -like '*email*' -or $_.Name -like '*@*' } | "
//                 + "  Select-Object -First 1 -ExpandProperty Value } } catch {}\"";
//         String psOutput = executeCommand(true, psCmd);
//         String email = extractEmailFromText(psOutput);
//         if (email != null) {
//             return email;
//         }

//         // W3 – gitconfig
//         email = readGitConfigEmail();
//         if (email != null) {
//             return email;
//         }

//         // W4 – USERNAME@USERDOMAIN (corporate networks)
//         String domain = System.getenv("USERDOMAIN");
//         if (domain != null && !domain.equalsIgnoreCase("WORKGROUP") && !domain.equalsIgnoreCase(System.getenv("COMPUTERNAME"))) {
//             return username.toLowerCase() + "@" + domain.toLowerCase();
//         }

//         return null;
//     }

//     // ═════════════════════════════════════════════════════════════════════════
//     // macOS (9-Stage Discovery Sequence)
//     // ═════════════════════════════════════════════════════════════════════════
//     private String getEmailFromMac(String username) {
//         log.info("[MAC] Starting 9-stage email detection for user: {}", username);

//         // S1 – Python plistlib (AccountRegistry)
//         String s1 = tryMacS1_PythonPlist();
//         if (s1 != null) {
//             return s1;
//         }

//         // S2 – Keychain (High Intrusion - Triggers Popup)
//         String s2 = tryMacS2_Keychain();
//         if (s2 != null) {
//             return s2;
//         }

//         // S3 – Accounts4.sqlite (Requires Full Disk Access)
//         String s3 = tryMacS3_AccountsSqlite(username);
//         if (s3 != null) {
//             return s3;
//         }

//         // S4 – osascript
//         String s4 = tryMacS4_Osascript();
//         if (s4 != null) {
//             return s4;
//         }

//         // S5 – Group Containers Plists
//         String s5 = tryMacS5_GroupContainers();
//         if (s5 != null) {
//             return s5;
//         }

//         // S6 – Defaults Read (Standard Discovery)
//         String s6 = tryMacS6_DefaultsRead();
//         if (s6 != null) {
//             return s6;
//         }

//         // S7 – .gitconfig
//         String s7 = readGitConfigEmail();
//         if (s7 != null) {
//             return s7;
//         }

//         // S8 – dscl (Directory Services)
//         String s8 = tryMacS8_Dscl(username);
//         if (s8 != null) {
//             return s8;
//         }

//         // S9 – system_profiler
//         String s9 = tryMacS9_SystemProfiler();
//         if (s9 != null) {
//             return s9;
//         }

//         // Diagnostic Check for FDA
//         checkAndWarnFda(username);

//         return null;
//     }

//     // ── MacOS Support Strategies ──────────────────────────────────────────────
//     private String tryMacS1_PythonPlist() {
//         String script
//                 = "import sys, plistlib, os, glob\n"
//                 + "paths = [\n"
//                 + "  os.path.expanduser('~/Library/Preferences/MobileMeAccounts.plist'),\n"
//                 + "  '/Library/Preferences/MobileMeAccounts.plist',\n"
//                 + "  os.path.expanduser('~/Library/Accounts/AccountRegistry.plist'),\n"
//                 + "]\n"
//                 + "gc = os.path.expanduser('~/Library/Group Containers')\n"
//                 + "if os.path.isdir(gc):\n"
//                 + "  paths += glob.glob(gc + '/**/*.plist', recursive=True)[:20]\n"
//                 + "for p in paths:\n"
//                 + "  try:\n"
//                 + "    with open(p,'rb') as f: data=plistlib.load(f)\n"
//                 + "    def find_email(obj):\n"
//                 + "      if isinstance(obj,dict):\n"
//                 + "        for k,v in obj.items():\n"
//                 + "          if 'email' in k.lower() or 'account' in k.lower() or k=='AccountID':\n"
//                 + "            if isinstance(v,str) and '@' in v: print(v); sys.exit(0)\n"
//                 + "          find_email(v)\n"
//                 + "      elif isinstance(obj,list):\n"
//                 + "        for i in obj: find_email(i)\n"
//                 + "    find_email(data)\n"
//                 + "  except: pass\n";

//         File tmpScript = null;
//         try {
//             tmpScript = File.createTempFile("vistora_s1_", ".py");
//             try (FileWriter fw = new FileWriter(tmpScript)) {
//                 fw.write(script);
//             }
//             String result = executeCommand(false, "python3 " + tmpScript.getAbsolutePath());
//             return extractEmailFromText(result);
//         } catch (IOException e) {
//             return null;
//         } finally {
//             if (tmpScript != null) {
//                 tmpScript.delete();
//             }
//         }
//     }

//     private String tryMacS2_Keychain() {
//         String[] services = {"iCloud", "com.apple.account.AppleID.token", "Apple Conferences"};
//         for (String svc : services) {
//             String result = executeCommand(false, "security find-internet-password -s \"" + svc + "\" -g 2>&1 | grep 'acct'");
//             String email = extractEmailFromText(result);
//             if (email != null) {
//                 return email;
//             }
//         }
//         return extractEmailFromText(executeCommand(false, "security dump-keychain 2>/dev/null | grep -E 'acct' | grep '@' | head -10"));
//     }

//     private String tryMacS3_AccountsSqlite(String username) {
//         String dbPath = System.getProperty("user.home") + "/Library/Accounts/Accounts4.sqlite";
//         if (!new File(dbPath).exists()) {
//             return null;
//         }
//         String query = "SELECT ZACCOUNTUSERNAME FROM ZACCOUNT WHERE ZACCOUNTUSERNAME LIKE '%@%' LIMIT 1;";
//         return extractEmailFromText(executeCommand(false, "sqlite3 \"" + dbPath + "\" \"" + query + "\""));
//     }

//     private String tryMacS4_Osascript() {
//         String result = executeCommand(false, "osascript -e 'tell application \"System Events\" to get value of text field 1 of window 1 of application process \"System Preferences\"' 2>/dev/null");
//         String email = extractEmailFromText(result);
//         if (email != null) {
//             return email;
//         }
//         return extractEmailFromText(executeCommand(false, "osascript -e 'do shell script \"defaults read MobileMeAccounts Accounts 2>/dev/null || defaults read com.apple.iCloud AccountID 2>/dev/null\"'"));
//     }

//     private String tryMacS5_GroupContainers() {
//         String gcPath = System.getProperty("user.home") + "/Library/Group Containers";
//         String result = executeCommand(false, "find \"" + gcPath + "\" -name '*.plist' 2>/dev/null | head -15 | xargs -I{} sh -c 'plutil -p \"{}\" 2>/dev/null' | grep -E '@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}' | head -3");
//         return extractEmailFromText(result);
//     }

//     private String tryMacS6_DefaultsRead() {
//         String[] commands = {"defaults read MobileMeAccounts 2>/dev/null", "defaults read com.apple.iCloud 2>/dev/null", "defaults read NSGlobalDomain AppleID 2>/dev/null"};
//         for (String cmd : commands) {
//             String email = extractEmailFromText(executeCommand(false, cmd));
//             if (email != null) {
//                 return email;
//             }
//         }
//         return null;
//     }

//     private String tryMacS8_Dscl(String username) {
//         String result = executeCommand(false, "dscl . -read /Users/" + username + " EMailAddress 2>/dev/null");
//         if (result != null && result.contains("EMailAddress:")) {
//             String val = result.substring(result.lastIndexOf(":") + 1).trim();
//             if (val.contains("@")) {
//                 return val;
//             }
//         }
//         return extractEmailFromText(executeCommand(false, "dscl /Search -read /Users/" + username + " EMailAddress 2>/dev/null"));
//     }

//     private String tryMacS9_SystemProfiler() {
//         return extractEmailFromText(executeCommand(false, "system_profiler SPSoftwareDataType 2>/dev/null | grep -i 'owner\\|email\\|account'"));
//     }

//     // ═════════════════════════════════════════════════════════════════════════
//     // STAGE 10: Smart Browser Fallback
//     // ═════════════════════════════════════════════════════════════════════════
//     private String getEmailFromBrowsers(String os) {
//         List<String> paths = new ArrayList<>();
//         String userHome = System.getProperty("user.home");
//         if (os.contains("win")) {
//             String localAppData = System.getenv("LOCALAPPDATA");
//             paths.add(localAppData + "/Google/Chrome/User Data/Local State");
//             paths.add(localAppData + "/Microsoft/Edge/User Data/Local State");
//             paths.add(localAppData + "/BraveSoftware/Brave-Browser/User Data/Local State");
//         } else if (os.contains("mac")) {
//             paths.add(userHome + "/Library/Application Support/Google/Chrome/Local State");
//             paths.add(userHome + "/Library/Application Support/Microsoft Edge/Local State");
//             paths.add(userHome + "/Library/Application Support/BraveSoftware/Brave-Browser/Local State");
//         }

//         for (String path : paths) {
//             File localStateFile = new File(path);
//             if (localStateFile.exists()) {
//                 try {
//                     JsonNode root = objectMapper.readTree(localStateFile);
//                     JsonNode infoCache = root.path("profile").path("info_cache");
//                     Iterator<String> fieldNames = infoCache.fieldNames();
//                     while (fieldNames.hasNext()) {
//                         String email = infoCache.get(fieldNames.next()).path("user_name").asText();
//                         if (isValidEmail(email)) {
//                             return email;
//                         }
//                     }
//                 } catch (Exception ignored) {
//                 }
//             }
//         }
//         return null;
//     }

//     // ═════════════════════════════════════════════════════════════════════════
//     // SHARED HELPERS
//     // ═════════════════════════════════════════════════════════════════════════
//     private String readGitConfigEmail() {
//         String result = executeCommand(false, "git config --global user.email 2>/dev/null");
//         if (result != null && result.contains("@")) {
//             return result.trim();
//         }
//         File gitconfig = new File(System.getProperty("user.home"), ".gitconfig");
//         if (gitconfig.exists()) {
//             try (BufferedReader br = new BufferedReader(new FileReader(gitconfig))) {
//                 String line;
//                 while ((line = br.readLine()) != null) {
//                     if (line.trim().startsWith("email") && line.contains("=")) {
//                         String val = line.substring(line.indexOf('=') + 1).trim();
//                         if (val.contains("@")) {
//                             return val;
//                         }
//                     }
//                 }
//             } catch (IOException ignored) {
//             }
//         }
//         return null;
//     }

//     private void logOwnJavaPath() {
//         String javaHome = System.getProperty("java.home");
//         if (javaHome != null) {
//             log.info("[MAC] Running from Java binary: {}/bin/java", javaHome);
//         }
//     }

//     private void checkAndWarnFda(String username) {
//         File db = new File(System.getProperty("user.home") + "/Library/Accounts/Accounts4.sqlite");
//         if (db.exists() && !db.canRead()) {
//             log.warn("[MAC] !!! FULL DISK ACCESS NOT GRANTED !!! Current user cannot read Accounts4.sqlite");
//         }
//     }

//     private String extractEmailFromText(String text) {
//         if (text == null) {
//             return null;
//         }
//         Matcher m = EMAIL_PATTERN.matcher(text);
//         return m.find() ? m.group(1) : null;
//     }

//     private boolean isValidEmail(String input) {
//         return input != null && EMAIL_PATTERN.matcher(input).matches();
//     }

//     private String executeCommand(boolean isWindows, String command) {
//         try {
//             ProcessBuilder pb = isWindows ? new ProcessBuilder("cmd", "/c", command) : new ProcessBuilder("sh", "-c", command);
//             Process process = pb.start();
//             StringBuilder sb = new StringBuilder();
//             try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
//                 String line;
//                 while ((line = reader.readLine()) != null) {
//                     sb.append(line).append("\n");
//                 }
//             }
//             if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
//                 return sb.toString().trim();
//             }
//             process.destroyForcibly();
//             return null;
//         } catch (Exception e) {
//             return null;
//         }
//     }

//     private String extractValue(String output, String key) {
//         Pattern pattern = Pattern.compile(key + "\\s*:\\s*(.*)");
//         Matcher matcher = pattern.matcher(output);
//         return matcher.find() ? matcher.group(1).trim() : null;
//     }
// }


package com.vistora.discovery.monitor.agent.collector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistora.discovery.monitor.model.dto.UserIdentity;

/**
* Industry-grade 10-Stage + Browser Identity Collector.
*
* Strategy order:
*  Mac:     S1 Python plist → S2 Keychain → S3 Accounts4.sqlite (FDA) →
*           S4 osascript → S5 Group Containers → S6 defaults read →
*           S7 .gitconfig/.npmrc/env vars → S8 dscl (LDAP/corporate) →
*           S9 system_profiler → S10 MDM/Jamf → Browser fallback
*  Windows: W1 Registry → W2 PowerShell → W3 .gitconfig → W4 USERDOMAIN
*
* Work email preference: corporate emails are always preferred over
* personal accounts (gmail, yahoo, hotmail, etc.).
*/
public class UserIdentityCollector {

    private static final Logger log = LoggerFactory.getLogger(UserIdentityCollector.class);
    private static final String UNKNOWN_EMAIL = "unknown@local";
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("([a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,6})");

    /** Free/personal email domains — deprioritised but still used as fallback. */
    private static final Set<String> PERSONAL_DOMAINS = new HashSet<>(Arrays.asList(
        "gmail.com", "yahoo.com", "yahoo.co.in", "hotmail.com", "outlook.com",
        "live.com", "icloud.com", "me.com", "mac.com", "protonmail.com",
        "proton.me", "tutanota.com", "zoho.com", "aol.com", "yandex.com",
        "rediffmail.com", "msn.com"
    ));

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────────
    // ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    public UserIdentity collect() {
        String os        = System.getProperty("os.name", "").toLowerCase();
        String localUser = System.getProperty("user.name", "unknown");
        String tenantName = "Local System";

        log.info("=== UserIdentityCollector starting | OS: {} | user: {} ===", os, localUser);

        // Collect ALL candidate emails → pick the best (corporate wins)
        List<String> candidates = new ArrayList<>();

        try {
            if (os.contains("win")) {
                collectWindowsCandidates(localUser, candidates);

                String dsregOutput = executeCommand(true, "dsregcmd /status");
                if (dsregOutput != null) {
                    String tenant = extractValue(dsregOutput, "WorkplaceTenantName");
                    if (tenant != null && !tenant.isEmpty()) tenantName = tenant;
                }

            } else if (os.contains("mac") || os.contains("darwin")) {
                logOwnJavaPath();
                collectMacCandidates(localUser, candidates);

                // Check MDM/Jamf enrolment
                String profileOut = executeCommand(false, "profiles status -type enrollment 2>/dev/null");
                if (profileOut != null && (profileOut.contains("Enrolled via DEP: Yes")
                        || profileOut.contains("Organization"))) {
                    tenantName = "Enterprise MDM (Managed)";
                }
            }

            // Stage 10: Browser fallback (Chrome/Edge/Brave/Arc/Opera/Vivaldi/Firefox)
            if (candidates.isEmpty()) {
                log.info("[IDENTITY] OS-level discovery exhausted. Escalating to Stage 10: Browser Profiles...");
                collectBrowserCandidates(os, candidates);
            }

        } catch (Exception e) {
            log.error("[IDENTITY] Unexpected error during collection: {}", e.getMessage());
        }

        String best = pickBestEmail(candidates);
        log.info("=== [IDENTITY][FINAL] User identity resolved to: {} | Tenant: {} ===",
                best != null ? best : UNKNOWN_EMAIL, tenantName);

        return new UserIdentity(
                best != null ? best : UNKNOWN_EMAIL,
                tenantName,
                localUser
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WORK EMAIL PICKER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * From a list of discovered email candidates:
     *  1. Returns first corporate email (not in PERSONAL_DOMAINS).
     *  2. Falls back to first personal email if no corporate found.
     *  3. Returns null if list is empty.
     */
    private String pickBestEmail(List<String> candidates) {
        String personalFallback = null;
        for (String email : candidates) {
            if (email == null || !email.contains("@")) continue;
            String domain = email.substring(email.indexOf('@') + 1).toLowerCase();
            if (!PERSONAL_DOMAINS.contains(domain)) {
                log.info("[IDENTITY] Picked corporate email: {}", email);
                return email;
            }
            if (personalFallback == null) personalFallback = email;
        }
        if (personalFallback != null) {
            log.info("[IDENTITY] No corporate email found — using personal fallback: {}", personalFallback);
        }
        return personalFallback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WINDOWS
    // ═════════════════════════════════════════════════════════════════════════

    private void collectWindowsCandidates(String username, List<String> out) {
        // W1 – Registry
        String[] regKeys = {
            "HKCU\\Software\\Microsoft\\IdentityCRL\\UserExtendedProperties",
            "HKCU\\Software\\Microsoft\\Windows Live\\WinLive\\Users",
            "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\ProfileList"
        };
        for (String key : regKeys) {
            String email = extractEmailFromText(executeCommand(true, "reg query \"" + key + "\" /s"));
            if (addCandidate(out, email, "WIN_W1_Registry(" + key + ")")) return;
        }

        // W2 – PowerShell Microsoft Account
        String psCmd = "powershell -NoProfile -NonInteractive -Command \""
                + "try { $u = Get-LocalUser -Name $env:USERNAME; "
                + "if ($u.PrincipalSource -eq 'MicrosoftAccount') { "
                + "  $props = Get-ItemProperty 'HKCU:\\Software\\Microsoft\\IdentityCRL\\UserExtendedProperties' -EA Stop; "
                + "  $props.PSObject.Properties | Where-Object { $_.Name -like '*email*' -or $_.Name -like '*@*' } | "
                + "  Select-Object -First 1 -ExpandProperty Value } } catch {}\"";
        String email = extractEmailFromText(executeCommand(true, psCmd));
        if (addCandidate(out, email, "WIN_W2_PowerShell")) return;

        // W3 – .gitconfig / env vars
        collectConfigFileCandidates(out);

        // W4 – USERDOMAIN (corporate)
        String domain = System.getenv("USERDOMAIN");
        if (domain != null && !domain.equalsIgnoreCase("WORKGROUP")
                && !domain.equalsIgnoreCase(System.getenv("COMPUTERNAME"))) {
            String derived = username.toLowerCase() + "@" + domain.toLowerCase();
            addCandidate(out, derived, "WIN_W4_USERDOMAIN");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // macOS
    // ═════════════════════════════════════════════════════════════════════════

    private void collectMacCandidates(String username, List<String> out) {
        log.info("[MAC] Initializing 10-Stage Discovery sequence...");

        stageCheck(out, tryMacS1_PythonPlist(),          "MAC_S1_PythonPlist");
        stageCheck(out, tryMacS2_Keychain(),             "MAC_S2_Keychain");
        stageCheck(out, tryMacS3_AccountsSqlite(username), "MAC_S3_Accounts4Sqlite");
        stageCheck(out, tryMacS4_Osascript(),            "MAC_S4_Osascript");
        stageCheck(out, tryMacS5_GroupContainers(),      "MAC_S5_GroupContainers");
        stageCheck(out, tryMacS6_DefaultsRead(),         "MAC_S6_Defaults");

        // S7: config files + environment variables
        collectConfigFileCandidates(out);
        log.info("[IDENTITY][{}] Stage: MAC_S7_ConfigAndEnv", out.isEmpty() ? "MISS" : "HIT");

        stageCheck(out, tryMacS8_Dscl(username),         "MAC_S8_Dscl");
        stageCheck(out, tryMacS9_SystemProfiler(),       "MAC_S9_Profiler");
        stageCheck(out, tryMacS10_Mdm(),                 "MAC_S10_MDM");

        if (out.isEmpty()) {
            checkAndWarnFda(username);
        }
    }

    /** Helper: logs stage result and adds to candidates if non-null. */
    private void stageCheck(List<String> out, String email, String stageName) {
        if (email != null) {
            log.info("[IDENTITY][HIT]  Stage: {} | Email: {}", stageName, email);
            out.add(email);
        } else {
            log.info("[IDENTITY][MISS] Stage: {}", stageName);
        }
    }

    // ── S1: Python plistlib ───────────────────────────────────────────────────
    private String tryMacS1_PythonPlist() {
        String script =
            "import sys, plistlib, os, glob\n" +
            "paths = [\n" +
            "  os.path.expanduser('~/Library/Preferences/MobileMeAccounts.plist'),\n" +
            "  '/Library/Preferences/MobileMeAccounts.plist',\n" +
            "  os.path.expanduser('~/Library/Accounts/AccountRegistry.plist'),\n" +
            "]\n" +
            "gc = os.path.expanduser('~/Library/Group Containers')\n" +
            "if os.path.isdir(gc):\n" +
            "  paths += glob.glob(gc + '/**/*.plist', recursive=True)[:20]\n" +
            "for p in paths:\n" +
            "  try:\n" +
            "    with open(p,'rb') as f: data=plistlib.load(f)\n" +
            "    def find_email(obj):\n" +
            "      if isinstance(obj,dict):\n" +
            "        for k,v in obj.items():\n" +
            "          if any(x in k.lower() for x in ['email','account','accountid']):\n" +
            "            if isinstance(v,str) and '@' in v and '.' in v.split('@')[1]: print(v); sys.exit(0)\n" +
            "          find_email(v)\n" +
            "      elif isinstance(obj,list):\n" +
            "        for i in obj: find_email(i)\n" +
            "    find_email(data)\n" +
            "  except: pass\n";
        File tmp = null;
        try {
            tmp = File.createTempFile("vistora_s1_", ".py");
            try (FileWriter fw = new FileWriter(tmp)) { fw.write(script); }
            return extractEmailFromText(executeCommand(false, "python3 " + tmp.getAbsolutePath()));
        } catch (IOException e) {
            return null;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    // ── S2: Keychain ──────────────────────────────────────────────────────────
    private String tryMacS2_Keychain() {
        String[] services = {
            "iCloud", "com.apple.account.AppleID.token",
            "com.apple.account.iCloudKit.token", "Apple Conferences"
        };
        for (String svc : services) {
            String email = extractEmailFromText(
                executeCommand(false, "security find-internet-password -s \"" + svc + "\" -g 2>&1 | grep 'acct'"));
            if (email != null) return email;
        }
        return extractEmailFromText(
            executeCommand(false, "security dump-keychain 2>/dev/null | grep -E 'acct' | grep '@' | head -10"));
    }

    // ── S3: Accounts4.sqlite (requires Full Disk Access) ─────────────────────
    private String tryMacS3_AccountsSqlite(String username) {
        String dbPath = System.getProperty("user.home") + "/Library/Accounts/Accounts4.sqlite";
        File db = new File(dbPath);
        if (!db.exists()) return null;
        log.info("[MAC] S3: Accounts4.sqlite exists (readable={})", db.canRead());
        String[] queries = {
            "SELECT ZACCOUNTUSERNAME FROM ZACCOUNT WHERE ZACCOUNTUSERNAME LIKE '%@%' LIMIT 1;",
            "SELECT ZVALUE FROM ZACCOUNTPROPERTY WHERE ZPROPERTY='email' OR ZPROPERTY='EmailAddress' LIMIT 1;",
            "SELECT ZACCOUNTUSERNAME FROM ZACCOUNT LIMIT 10;"
        };
        for (String q : queries) {
            String email = extractEmailFromText(
                executeCommand(false, "sqlite3 \"" + dbPath + "\" \"" + q + "\""));
            if (email != null) return email;
        }
        return null;
    }

    // ── S4: osascript ─────────────────────────────────────────────────────────
    private String tryMacS4_Osascript() {
        String r1 = executeCommand(false,
            "osascript -e 'tell application \"System Events\" to get value of text field 1 of window 1 of application process \"System Preferences\"' 2>/dev/null");
        String email = extractEmailFromText(r1);
        if (email != null) return email;
        return extractEmailFromText(executeCommand(false,
            "osascript -e 'do shell script \"defaults read MobileMeAccounts Accounts 2>/dev/null\"'"));
    }

    // ── S5: Group Containers plists ───────────────────────────────────────────
    private String tryMacS5_GroupContainers() {
        String gcPath = System.getProperty("user.home") + "/Library/Group Containers";
        if (!new File(gcPath).exists()) return null;
        return extractEmailFromText(executeCommand(false,
            "find \"" + gcPath + "\" -name '*.plist' 2>/dev/null | head -15 | " +
            "xargs -I{} sh -c 'plutil -p \"{}\" 2>/dev/null' | " +
            "grep -E '@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}' | head -3"));
    }

    // ── S6: defaults read ─────────────────────────────────────────────────────
    private String tryMacS6_DefaultsRead() {
        String[] cmds = {
            "defaults read MobileMeAccounts 2>/dev/null",
            "defaults read com.apple.iCloud 2>/dev/null",
            "defaults read com.apple.preferences.AppleIDPrefPane 2>/dev/null",
            "defaults read NSGlobalDomain AppleID 2>/dev/null"
        };
        for (String cmd : cmds) {
            String email = extractEmailFromText(executeCommand(false, cmd));
            if (email != null) return email;
        }
        return null;
    }

    // ── S8: dscl (corporate/LDAP Macs) ───────────────────────────────────────
    private String tryMacS8_Dscl(String username) {
        String result = executeCommand(false,
            "dscl . -read /Users/" + username + " EMailAddress 2>/dev/null");
        if (result != null && result.contains("EMailAddress:")) {
            String val = result.substring(result.lastIndexOf(':') + 1).trim();
            if (val.contains("@")) return val;
        }
        return extractEmailFromText(executeCommand(false,
            "dscl /Search -read /Users/" + username + " EMailAddress 2>/dev/null"));
    }

    // ── S9: system_profiler ───────────────────────────────────────────────────
    private String tryMacS9_SystemProfiler() {
        return extractEmailFromText(executeCommand(false,
            "system_profiler SPSoftwareDataType 2>/dev/null | grep -i 'owner\\|email\\|account'"));
    }

    // ── S10: MDM / Jamf (corporate device management) ────────────────────────
    private String tryMacS10_Mdm() {
        // From MDM profile XML
        String mdmEmail = extractEmailFromText(executeCommand(false,
            "profiles -P 2>/dev/null | grep -i 'email\\|@'"));
        if (mdmEmail != null) return mdmEmail;

        // From managed preferences (pushed by IT/Jamf)
        String home = System.getProperty("user.home");
        String username = System.getProperty("user.name");
        String[] managedPaths = {
            "/Library/Managed Preferences/" + username + "/",
            "/Library/Managed Preferences/"
        };
        for (String dir : managedPaths) {
            if (!new File(dir).exists()) continue;
            String result = executeCommand(false,
                "find \"" + dir + "\" -name '*.plist' 2>/dev/null | head -5 | " +
                "xargs -I{} sh -c 'plutil -p \"{}\" 2>/dev/null' | grep '@' | head -3");
            String email = extractEmailFromText(result);
            if (email != null) return email;
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // S7 / W3: Config Files + Environment Variables (cross-platform)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Checks (in order):
     *  1. Environment variables: EMAIL, GIT_AUTHOR_EMAIL, GIT_COMMITTER_EMAIL, NPM_EMAIL
     *  2. ~/.gitconfig
     *  3. ~/.npmrc   (email = user@example.com)
     *  4. ~/.m2/settings.xml (Maven)
     */
    private void collectConfigFileCandidates(List<String> out) {
        String home = System.getProperty("user.home");

        // 1. Environment variables
        String[] envVars = {"EMAIL", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_EMAIL", "NPM_EMAIL"};
        for (String var : envVars) {
            String val = System.getenv(var);
            if (val != null && val.contains("@")) {
                addCandidate(out, val.trim(), "EnvVar(" + var + ")");
            }
        }

        // 2. ~/.gitconfig
        String gitEmail = readGitConfigEmail();
        addCandidate(out, gitEmail, "GitConfig");

        // 3. ~/.npmrc
        File npmrc = new File(home, ".npmrc");
        if (npmrc.exists()) {
            String email = parseKeyValueFile(npmrc, "email");
            addCandidate(out, email, "NpmRC");
        }

        // 4. ~/.m2/settings.xml (Maven — look for <email> tag)
        File mavenSettings = new File(home, ".m2/settings.xml");
        if (mavenSettings.exists()) {
            String email = extractEmailFromText(readFileContents(mavenSettings, 200));
            addCandidate(out, email, "MavenSettings");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STAGE 10: Browser Smart Fallback
    // Covers: Chrome, Edge, Brave, Arc, Opera, Vivaldi → Chromium Local State
    //         Firefox → signedInUser.json (Firefox Sync account)
    // ═════════════════════════════════════════════════════════════════════════

    private void collectBrowserCandidates(String os, List<String> out) {
        String home = System.getProperty("user.home");
        boolean win = os.contains("win");
        String localAppData = win ? System.getenv("LOCALAPPDATA") : null;

        // ── Chromium-based browsers (all use same Local State JSON format) ───
        List<String> chromiumPaths = new ArrayList<>();
        if (win) {
            chromiumPaths.add(localAppData + "/Google/Chrome/User Data/Local State");
            chromiumPaths.add(localAppData + "/Microsoft/Edge/User Data/Local State");
            chromiumPaths.add(localAppData + "/BraveSoftware/Brave-Browser/User Data/Local State");
            chromiumPaths.add(localAppData + "/Opera Software/Opera Stable/Local State");
            chromiumPaths.add(localAppData + "/Vivaldi/User Data/Local State");
        } else {
            // macOS paths
            chromiumPaths.add(home + "/Library/Application Support/Google/Chrome/Local State");
            chromiumPaths.add(home + "/Library/Application Support/Microsoft Edge/Local State");
            chromiumPaths.add(home + "/Library/Application Support/BraveSoftware/Brave-Browser/Local State");
            chromiumPaths.add(home + "/Library/Application Support/Arc/User Data/Local State");
            chromiumPaths.add(home + "/Library/Application Support/com.operasoftware.Opera/Local State");
            chromiumPaths.add(home + "/Library/Application Support/Vivaldi/Local State");
        }

        for (String path : chromiumPaths) {
            File f = new File(path);
            if (!f.exists()) continue;
            try {
                JsonNode root = objectMapper.readTree(f);
                JsonNode infoCache = root.path("profile").path("info_cache");
                Iterator<String> keys = infoCache.fieldNames();
                while (keys.hasNext()) {
                    String email = infoCache.get(keys.next()).path("user_name").asText(null);
                    if (isValidEmail(email)) {
                        log.info("[IDENTITY][SUCCESS] Stage: BROWSER_LocalState [{}] | Resolved Email: {}", f.getParentFile().getParentFile().getName(), email);
                        out.add(email);
                    }
                }
            } catch (Exception ignored) {}
        }

        // ── Firefox: signedInUser.json (Firefox Sync account email) ──────────
        String ffBase = win
            ? System.getenv("APPDATA") + "/Mozilla/Firefox/Profiles"
            : home + "/Library/Application Support/Firefox/Profiles";
        File ffProfiles = new File(ffBase);
        if (ffProfiles.exists()) {
            File[] profiles = ffProfiles.listFiles(File::isDirectory);
            if (profiles != null) {
                for (File profile : profiles) {
                    File signedIn = new File(profile, "signedInUser.json");
                    if (!signedIn.exists()) continue;
                    try {
                        JsonNode root = objectMapper.readTree(signedIn);
                        // structure: { "accountData": { "email": "user@example.com" } }
                        String email = root.path("accountData").path("email").asText(null);
                        if (isValidEmail(email)) {
                            log.info("[IDENTITY][SUCCESS] Stage: BROWSER_Firefox_Sync | Resolved Email: {}", email);
                            out.add(email);
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FDA WARNING
    // ═════════════════════════════════════════════════════════════════════════

    private void checkAndWarnFda(String username) {
        File db = new File(System.getProperty("user.home") + "/Library/Accounts/Accounts4.sqlite");
        if (db.exists() && !db.canRead()) {
            String javaPath = getOwnJavaBinaryPath();
            log.warn("[MAC] !!! CRITICAL: FULL DISK ACCESS BLOCKED !!! Application cannot read Accounts4.sqlite database.");
            log.warn("[MAC] To fix — go to: System Settings → Privacy & Security → Full Disk Access → click '+' → add:");
            log.warn("[MAC]   Binary path: {}", javaPath != null ? javaPath : "(run 'which java' to find it)");
            log.warn("[MAC] Then restart the agent.");
        }
    }

    private String getOwnJavaBinaryPath() {
        // Method 1: java.home (always set by JVM)
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            File bin = new File(javaHome, "bin/java");
            if (bin.exists()) return bin.getAbsolutePath();
            try {
                File binParent = new File(javaHome, "../bin/java");
                if (binParent.getCanonicalFile().exists()) return binParent.getCanonicalPath();
            } catch (IOException ignored) {}
        }
        // Method 2: ProcessHandle (Java 9+)
        try {
            java.util.Optional<String> cmd = ProcessHandle.current().info().command();
            if (cmd.isPresent()) return cmd.get();
        } catch (Exception ignored) {}
        // Method 3: which java
        String which = executeCommand(false, "which java 2>/dev/null");
        return (which != null && !which.isBlank()) ? which.trim() : null;
    }

    private void logOwnJavaPath() {
        String path = getOwnJavaBinaryPath();
        if (path != null) log.info("[MAC] JVM Path: {}", path);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SHARED HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Read email from ~/.gitconfig */
    private String readGitConfigEmail() {
        String result = executeCommand(false, "git config --global user.email 2>/dev/null");
        if (result != null && result.contains("@")) return result.trim();

        File gitconfig = new File(System.getProperty("user.home"), ".gitconfig");
        return gitconfig.exists() ? parseKeyValueFile(gitconfig, "email") : null;
    }

    /** Parse a simple key = value file and return the value for the given key. */
    private String parseKeyValueFile(File file, String key) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key) && trimmed.contains("=")) {
                    String val = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                    if (val.contains("@")) return val;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /** Read first N lines of a file as a single string. */
    private String readFileContents(File file, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null && count++ < maxLines) {
                sb.append(line).append("\n");
            }
        } catch (IOException ignored) {}
        return sb.toString();
    }

    /** Add email to candidates list, log the stage. Returns true if added. */
    private boolean addCandidate(List<String> out, String email, String stage) {
        if (email != null && email.contains("@")) {
            log.info("[IDENTITY][HIT]  Stage: {} | Email: {}", stage, email);
            out.add(email);
            return true;
        }
        return false;
    }

    /** Extract first valid email address from arbitrary text using regex. */
    private String extractEmailFromText(String text) {
        if (text == null) return null;
        Matcher m = EMAIL_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Full regex match — for validating a string that should be ONLY an email. */
    private boolean isValidEmail(String input) {
        return input != null && !input.isEmpty() && EMAIL_PATTERN.matcher(input).matches();
    }

    /**
     * Execute a shell command and return stdout, or null on failure/timeout.
     * Uses cmd /c on Windows, sh -c on Mac/Linux.
     */
    private String executeCommand(boolean isWindows, String command) {
        try {
            ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
            Process process = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            }
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractValue(String output, String key) {
        Matcher m = Pattern.compile(key + "\\s*:\\s*(.*)").matcher(output);
        return m.find() ? m.group(1).trim() : null;
    }
}

 