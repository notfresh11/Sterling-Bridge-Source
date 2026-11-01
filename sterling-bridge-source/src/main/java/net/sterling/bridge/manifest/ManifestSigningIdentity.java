package net.sterling.bridge.manifest;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ManifestSigningIdentity {
    private static final String ALGORITHM = "Ed25519";
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
    private final Path identityPath;
    private final Gson gson = new Gson();

    public ManifestSigningIdentity(Path identityPath) {
        this.identityPath = identityPath.toAbsolutePath().normalize();
    }

    public synchronized void sign(ManifestModel manifest) throws IOException {
        KeyPair keyPair = loadOrCreateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        manifest.setIdentityAlgorithm(ALGORITHM);
        manifest.setIdentityPublicKey(publicKey);
        manifest.setManifestSignature(null);
        JsonObject payload = gson.toJsonTree(manifest).getAsJsonObject();
        payload.remove("manifestSignature");
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(keyPair.getPrivate());
            signature.update(canonicalBytes(payload));
            manifest.setManifestSignature(Base64.getEncoder().encodeToString(signature.sign()));
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not sign the Sterling manifest.", exception);
        }
    }

    public synchronized boolean matchesCurrentIdentity(JsonObject manifest) throws IOException {
        KeyPair keyPair = loadExistingKeyPair();
        String publicKey = manifest.has("identityPublicKey")
                ? manifest.get("identityPublicKey").getAsString()
                : "";
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()).equals(publicKey);
    }

    public synchronized String fingerprint() throws IOException {
        KeyPair keyPair = loadOrCreateKeyPair();
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(keyPair.getPublic().getEncoded()));
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not calculate the Sterling Bridge identity fingerprint.", exception);
        }
    }

    public static String verify(JsonObject manifest) throws IOException {
        try {
            if (!manifest.has("identityAlgorithm")
                    || !ALGORITHM.equals(manifest.get("identityAlgorithm").getAsString())
                    || !manifest.has("identityPublicKey")
                    || !manifest.has("manifestSignature")) {
                throw new IOException("The Bridge manifest is unsigned. Update Sterling Bridge before installing.");
            }
            byte[] publicKeyBytes = Base64.getDecoder().decode(manifest.get("identityPublicKey").getAsString());
            byte[] signatureBytes = Base64.getDecoder().decode(manifest.get("manifestSignature").getAsString());
            PublicKey publicKey = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            JsonObject payload = manifest.deepCopy();
            payload.remove("manifestSignature");
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(canonicalBytes(payload));
            if (!signature.verify(signatureBytes)) {
                throw new IOException("Bridge manifest signature is invalid; no files were installed.");
            }
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(publicKeyBytes));
        } catch (IllegalArgumentException | GeneralSecurityException | IllegalStateException
                 | NullPointerException exception) {
            throw new IOException("Bridge manifest identity or signature is invalid.", exception);
        }
    }

    private KeyPair loadOrCreateKeyPair() throws IOException {
        if (Files.exists(identityPath)) {
            return loadExistingKeyPair();
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            KeyPair keyPair = generator.generateKeyPair();
            saveKeyPair(keyPair);
            return keyPair;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not create the Sterling Bridge manifest identity.", exception);
        }
    }

    private KeyPair loadExistingKeyPair() throws IOException {
        if (!Files.isRegularFile(identityPath)) {
            throw new IOException("Sterling Bridge manifest signing identity is missing: " + identityPath);
        }
        try {
            JsonObject stored = JsonParser.parseString(
                    Files.readString(identityPath, StandardCharsets.UTF_8)).getAsJsonObject();
            byte[] privateBytes = Base64.getDecoder().decode(stored.get("privateKey").getAsString());
            byte[] publicBytes = Base64.getDecoder().decode(stored.get("publicKey").getAsString());
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicBytes));
            Signature keyCheck = Signature.getInstance(ALGORITHM);
            keyCheck.initSign(privateKey);
            keyCheck.update("Sterling Bridge signing identity check".getBytes(StandardCharsets.UTF_8));
            byte[] proof = keyCheck.sign();
            keyCheck.initVerify(publicKey);
            keyCheck.update("Sterling Bridge signing identity check".getBytes(StandardCharsets.UTF_8));
            if (!keyCheck.verify(proof)) {
                throw new IOException("Sterling Bridge manifest signing key pair does not match.");
            }
            return new KeyPair(publicKey, privateKey);
        } catch (IllegalArgumentException | GeneralSecurityException | NullPointerException exception) {
            throw new IOException("Sterling Bridge manifest signing identity is invalid.", exception);
        }
    }

    private void saveKeyPair(KeyPair keyPair) throws IOException {
        Files.createDirectories(identityPath.getParent());
        JsonObject stored = new JsonObject();
        stored.addProperty("privateKey", Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        stored.addProperty("publicKey", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        Path temporary = Files.createTempFile(identityPath.getParent(), ".manifest-identity-", ".tmp");
        try {
            setPrivatePermissions(temporary);
            Files.writeString(temporary, gson.toJson(stored), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, identityPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, identityPath);
            }
            setPrivatePermissions(identityPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void setPrivatePermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PRIVATE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows inherits permissions from the parent directory.
        }
    }

    private static byte[] canonicalBytes(JsonObject object) {
        return new Gson().toJson(sortRecursively(object)).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonElement sortRecursively(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            Map<String, JsonElement> properties = new TreeMap<>();
            element.getAsJsonObject().entrySet()
                    .forEach(entry -> properties.put(entry.getKey(), entry.getValue()));
            properties.forEach((key, value) -> sorted.add(key, sortRecursively(value)));
            return sorted;
        }
        if (element.isJsonArray()) {
            var sorted = new com.google.gson.JsonArray();
            element.getAsJsonArray().forEach(value -> sorted.add(sortRecursively(value)));
            return sorted;
        }
        return element.deepCopy();
    }
}
