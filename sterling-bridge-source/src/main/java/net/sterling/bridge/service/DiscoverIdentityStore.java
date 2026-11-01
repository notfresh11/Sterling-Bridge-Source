package net.sterling.bridge.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class DiscoverIdentityStore {
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public DiscoverIdentityStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public Identity load() throws IOException {
        if (Files.notExists(path)) {
            return new Identity();
        }
        Identity identity;
        try {
            identity = gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), Identity.class);
        } catch (RuntimeException exception) {
            throw new IOException("Sterling Discover identity file is invalid.", exception);
        }
        if (identity == null) {
            throw new IOException("Sterling Discover identity file is empty or invalid.");
        }
        identity.normalize();
        return identity;
    }

    public void save(Identity identity) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "discover-identity-", ".tmp");
        try {
            setPrivatePermissions(temporary);
            Files.writeString(temporary, gson.toJson(identity), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            setPrivatePermissions(path);
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

    public static final class Identity {
        private String catalogUrl = "";
        private String serverId = "";
        private String credential = "";
        private String enrollmentId = "";
        private String enrollmentSecret = "";
        private String challengeToken = "";
        private String expiresAt = "";

        public String getCatalogUrl() {
            return catalogUrl;
        }

        public void setCatalogUrl(String catalogUrl) {
            this.catalogUrl = catalogUrl == null ? "" : catalogUrl;
        }

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId == null ? "" : serverId;
        }

        public String getCredential() {
            return credential;
        }

        public void setCredential(String credential) {
            this.credential = credential == null ? "" : credential;
        }

        public String getEnrollmentId() {
            return enrollmentId;
        }

        public void setEnrollmentId(String enrollmentId) {
            this.enrollmentId = enrollmentId == null ? "" : enrollmentId;
        }

        public String getEnrollmentSecret() {
            return enrollmentSecret;
        }

        public void setEnrollmentSecret(String enrollmentSecret) {
            this.enrollmentSecret = enrollmentSecret == null ? "" : enrollmentSecret;
        }

        public String getChallengeToken() {
            return challengeToken;
        }

        public void setChallengeToken(String challengeToken) {
            this.challengeToken = challengeToken == null ? "" : challengeToken;
        }

        public String getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(String expiresAt) {
            this.expiresAt = expiresAt == null ? "" : expiresAt;
        }

        public boolean hasServerCredential() {
            return !serverId.isBlank() && !credential.isBlank();
        }

        public boolean hasPendingEnrollment() {
            return !enrollmentId.isBlank() && !enrollmentSecret.isBlank() && !challengeToken.isBlank();
        }

        public void clearPendingEnrollment() {
            enrollmentId = "";
            enrollmentSecret = "";
            challengeToken = "";
            expiresAt = "";
        }

        public void clearServerCredential() {
            serverId = "";
            credential = "";
        }

        public void normalize() {
            setCatalogUrl(catalogUrl);
            setServerId(serverId);
            setCredential(credential);
            setEnrollmentId(enrollmentId);
            setEnrollmentSecret(enrollmentSecret);
            setChallengeToken(challengeToken);
            setExpiresAt(expiresAt);
        }
    }
}
