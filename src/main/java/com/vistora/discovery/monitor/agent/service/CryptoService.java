package com.vistora.discovery.monitor.agent.service;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hybrid RSA + AES Encryption Service.
 * 
 * The Agent can ONLY ENCRYPT (has public key).
 * The Middle-Agent can ONLY DECRYPT (has private key).
 * 
 * Flow:
 *   1. Generate a random AES-256 key (unique per scan)
 *   2. Encrypt data with AES-256-GCM (fast, handles large payloads)
 *   3. Encrypt the AES key with RSA-2048 public key (secure wrapping)
 *   4. Package: [AES key length (4 bytes)] + [RSA-encrypted AES key] + [IV (12 bytes)] + [AES ciphertext]
 */
public class CryptoService {

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final String RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final PublicKey publicKey;

    public CryptoService(String deviceId) {
        this.publicKey = loadPublicKey();
    }

    /**
     * Encrypts data using RSA+AES hybrid encryption.
     * Only the Middle-Agent (with the private key) can decrypt this.
     */
    public String encrypt(String plainText) throws Exception {
        // 1. Generate a random AES-256 key (unique per scan)
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey aesKey = keyGen.generateKey();

        // 2. Generate a random IV
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        // 3. Encrypt the data with AES-256-GCM
        Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] cipherText = aesCipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        // 4. Encrypt the AES key with RSA public key
        Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        // 5. Package everything together
        ByteBuffer buffer = ByteBuffer.allocate(
                4 + encryptedAesKey.length + IV_LENGTH + cipherText.length);
        buffer.putInt(encryptedAesKey.length);   // 4 bytes: key length
        buffer.put(encryptedAesKey);              // RSA-encrypted AES key
        buffer.put(iv);                           // 12 bytes: IV
        buffer.put(cipherText);                   // AES-GCM ciphertext

        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private PublicKey loadPublicKey() {
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream("vistora_public.key");
            if (is == null) {
                throw new RuntimeException("Public key file not found in resources!");
            }
            String keyB64 = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            byte[] keyBytes = Base64.getDecoder().decode(keyB64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load RSA public key: " + e.getMessage(), e);
        }
    }
}
