package com.vistora.discovery.monitor.agent.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import java.util.Base64;

/**
 * One-time utility to generate the RSA-2048 key pair.
 * Run once, then distribute the keys:
 *   - public key  → Agent resources
 *   - private key → Middle-Agent resources
 */
public class KeyPairGeneratorUtil {

    public static void main(String[] args) throws Exception {
        System.out.println("Generating RSA-2048 Key Pair...");

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String privateKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

        // Save public key to Agent resources
        Path pubPath = Paths.get("src", "main", "resources", "vistora_public.key");
        Files.createDirectories(pubPath.getParent());
        Files.writeString(pubPath, publicKeyB64);
        System.out.println("Public Key saved to: " + pubPath.toAbsolutePath());

        // Save private key to Middle-Agent resources
        Path privPath = Paths.get("..", "middle-agent", "src", "main", "resources", "vistora_private.key");
        Files.createDirectories(privPath.getParent());
        Files.writeString(privPath, privateKeyB64);
        System.out.println("Private Key saved to: " + privPath.toAbsolutePath());

        System.out.println("=== KEY PAIR GENERATED SUCCESSFULLY ===");
    }
}
