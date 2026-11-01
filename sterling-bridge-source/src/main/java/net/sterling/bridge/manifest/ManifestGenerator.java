package net.sterling.bridge.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ManifestGenerator {
    private static final int RETAINED_SNAPSHOTS = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestGenerator.class);

    private final Path modsDir;
    private final Path manifestPath;
    private final Path snapshotsDir;
    private final ManifestSigningIdentity signingIdentity;
    private final SterlingConfig config;
    private final String minecraftVersion;
    private final String loaderVersion;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ManifestGenerator(
            Path modsDir,
            Path manifestPath,
            SterlingConfig config,
            String minecraftVersion,
            String loaderVersion
    ) {
        this.modsDir = modsDir.toAbsolutePath().normalize();
        this.manifestPath = manifestPath.toAbsolutePath().normalize();
        this.snapshotsDir = this.manifestPath.getParent().resolve("snapshots");
        this.signingIdentity = new ManifestSigningIdentity(
                this.manifestPath.getParent().resolve("manifest-signing-identity.json"));
        this.config = config;
        this.minecraftVersion = minecraftVersion;
        this.loaderVersion = loaderVersion;
    }

    private void pruneSnapshotsSafely(String activeSnapshotId) {
        try {
            pruneSnapshots(activeSnapshotId);
        } catch (IOException exception) {
            LOGGER.warn("Snapshot {} is active, but old snapshots could not be pruned.",
                    activeSnapshotId, exception);
        }
    }

    public synchronized ManifestModel generateAndSaveManifest() {
        return generateAndSaveManifest(config);
    }

    public synchronized ManifestModel generateAndSaveManifest(SterlingConfig snapshotConfig) {
        String snapshotId = UUID.randomUUID().toString();
        Path stagingDir = snapshotsDir.resolve(".staging-" + snapshotId);
        Path snapshotDir = snapshotsDir.resolve(snapshotId);
        boolean activated = false;
        try {
            Files.createDirectories(snapshotsDir);
            cleanStagingDirectories();
            Files.createDirectory(stagingDir);
            ManifestModel manifest = createSnapshot(stagingDir, snapshotId, snapshotConfig);
            signingIdentity.sign(manifest);
            writeManifest(stagingDir.resolve("manifest.json"), manifest);
            moveAtomically(stagingDir, snapshotDir, false);
            activateManifest(snapshotDir.resolve("manifest.json"));
            activated = true;
            pruneSnapshotsSafely(snapshotId);
            return manifest;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not publish a complete Sterling content snapshot.", exception);
        } finally {
            deleteStagingDirectory(stagingDir);
            if (!activated) {
                deleteStagingDirectory(snapshotDir);
            }
        }
    }

    public synchronized ManifestModel restoreSnapshot(String snapshotId) {
        if (snapshotId == null || !snapshotId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("Invalid Sterling snapshot ID.");
        }
        Path snapshotDir = snapshotsDir.resolve(snapshotId).normalize();
        if (!snapshotDir.startsWith(snapshotsDir) || Files.isSymbolicLink(snapshotDir)) {
            throw new IllegalArgumentException("Invalid Sterling snapshot path.");
        }
        Path snapshotManifest = snapshotDir.resolve("manifest.json");
        try {
            if (!Files.isRegularFile(snapshotManifest) || Files.isSymbolicLink(snapshotManifest)) {
                throw new IOException("Snapshot manifest is missing.");
            }
            ManifestModel manifest = gson.fromJson(
                    Files.readString(snapshotManifest, StandardCharsets.UTF_8), ManifestModel.class);
            if (manifest == null || !snapshotId.equals(manifest.getSnapshotId())) {
                throw new IOException("Snapshot manifest does not match the requested snapshot ID.");
            }
            if (manifest.getSchemaVersion() != 1 || manifest.getInstallType() == null
                    || manifest.getMinecraftVersion() == null || manifest.getLoaderVersion() == null) {
                throw new IOException("Snapshot manifest is incomplete or uses an unsupported schema.");
            }
            com.google.gson.JsonObject snapshotJson = gson.fromJson(
                    Files.readString(snapshotManifest, StandardCharsets.UTF_8),
                    com.google.gson.JsonObject.class);
            ManifestSigningIdentity.verify(snapshotJson);
            if (!signingIdentity.matchesCurrentIdentity(snapshotJson)) {
                throw new IOException("Snapshot was signed by a different Sterling Bridge identity.");
            }
            validateSnapshotFiles(snapshotDir, manifest);
            activateManifest(snapshotManifest);
            Files.setLastModifiedTime(snapshotDir, FileTime.from(Instant.now()));
            pruneSnapshotsSafely(snapshotId);
            return manifest;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not restore Sterling snapshot " + snapshotId + ".", exception);
        }
    }

    public String getActiveSnapshotId() {
        ManifestModel manifest = readActiveManifest();
        return manifest.getSnapshotId() == null ? "unknown" : manifest.getSnapshotId();
    }

    public ManifestModel getActiveManifest() {
        return readActiveManifest();
    }

    private ManifestModel readActiveManifest() {
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("No active Sterling manifest is published yet.");
        }
        try {
            ManifestModel manifest = gson.fromJson(
                    Files.readString(manifestPath, StandardCharsets.UTF_8), ManifestModel.class);
            if (manifest == null) {
                throw new IOException("Active manifest is empty or invalid.");
            }
            return manifest;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not read the active Sterling manifest from "
                    + manifestPath + ".", exception);
        }
    }

    public Path getSnapshotsDir() {
        return snapshotsDir;
    }

    public String getIdentityFingerprint() {
        try {
            return signingIdentity.fingerprint();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the Sterling Bridge manifest identity.", exception);
        }
    }

    private ManifestModel createSnapshot(
            Path stagingDir,
            String snapshotId,
            SterlingConfig snapshotConfig
    ) throws IOException {
        ManifestModel manifest = new ManifestModel();
        manifest.setSchemaVersion(1);
        manifest.setSnapshotId(snapshotId);
        manifest.setInstallType(snapshotConfig.isModpack() ? "modrinth_mrpack" : "manifest");
        manifest.setServerName(snapshotConfig.getServerName());
        manifest.setMinecraftVersion(minecraftVersion);
        manifest.setLoaderType("fabric");
        manifest.setLoaderVersion(loaderVersion);
        manifest.setGeneratedAt(Instant.now().toString());
        if (snapshotConfig.isModpack()) {
            manifest.setModpackDownloadLink(snapshotConfig.getModpackDownloadLink());
            manifest.setMods(List.of());
            return manifest;
        }

        if (!Files.isDirectory(modsDir)) {
            throw new IOException("The server mods directory does not exist: " + modsDir);
        }
        Path snapshotModsDir = Files.createDirectory(stagingDir.resolve("mods"));
        List<String> excludedMods = snapshotConfig.getExcludedMods().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        List<ManifestModel.ModEntry> entries = new ArrayList<>();
        String baseUrl = snapshotConfig.getPublicBaseUrl().replaceAll("/+$", "");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path source : stream) {
                if (!Files.isRegularFile(source)) {
                    continue;
                }
                String fileName = source.getFileName().toString();
                String modId = fileName.substring(0, fileName.length() - ".jar".length());
                if (excludedMods.contains(fileName.toLowerCase(Locale.ROOT))
                        || excludedMods.contains(modId.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                Path snapshotFile = snapshotModsDir.resolve(fileName);
                Files.copy(source, snapshotFile);
                long sizeBytes = Files.size(snapshotFile);
                String sha256 = HashUtils.calculateSHA256(snapshotFile);
                String downloadUrl = baseUrl + "/snapshots/" + snapshotId + "/mods/"
                        + encodePathSegment(fileName);
                entries.add(new ManifestModel.ModEntry(
                        fileName, sha256, downloadUrl, modId, "1.0.0", sizeBytes, true));
            }
        }
        manifest.setMods(entries);
        return manifest;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void activateManifest(Path sourceManifest) throws IOException {
        Path temporaryManifest = Files.createTempFile(manifestPath.getParent(), ".manifest-", ".tmp");
        try {
            Files.copy(sourceManifest, temporaryManifest, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(temporaryManifest, manifestPath, true);
        } finally {
            Files.deleteIfExists(temporaryManifest);
        }
    }

    private void writeManifest(Path path, ManifestModel manifest) throws IOException {
        Files.writeString(path, gson.toJson(manifest), StandardCharsets.UTF_8);
    }

    private void validateSnapshotFiles(Path snapshotDir, ManifestModel manifest) throws IOException {
        if (manifest.getMods() == null) {
            throw new IOException("Snapshot contains no mod list.");
        }
        for (ManifestModel.ModEntry mod : manifest.getMods()) {
            if (mod.getFileName() == null || mod.getFileName().isBlank()
                    || mod.getFileName().contains("/") || mod.getFileName().contains("\\")) {
                throw new IOException("Snapshot contains an invalid mod filename.");
            }
            Path file = snapshotDir.resolve("mods").resolve(mod.getFileName()).normalize();
            if (!file.startsWith(snapshotDir) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                throw new IOException("Snapshot file is missing: " + mod.getFileName());
            }
            if (mod.getSizeBytes() == null || Files.size(file) != mod.getSizeBytes()
                    || !HashUtils.calculateSHA256(file).equalsIgnoreCase(mod.getSha256())) {
                throw new IOException("Snapshot file failed integrity verification: " + mod.getFileName());
            }
        }
    }

    private void pruneSnapshots(String activeSnapshotId) throws IOException {
        List<Path> snapshots;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapshotsDir)) {
            snapshots = new ArrayList<>();
            for (Path path : stream) {
                if (Files.isDirectory(path) && !Files.isSymbolicLink(path)
                        && path.getFileName().toString().matches("[0-9a-fA-F-]{36}")) {
                    snapshots.add(path);
                }
            }
        }
        snapshots.sort(Comparator.comparing(ManifestGenerator::lastModified).reversed());
        List<Path> retained = new ArrayList<>();
        Path active = snapshotsDir.resolve(activeSnapshotId);
        if (snapshots.remove(active)) {
            retained.add(active);
        }
        retained.addAll(snapshots.stream().limit(RETAINED_SNAPSHOTS - retained.size()).toList());
        for (Path snapshot : snapshots) {
            if (!retained.contains(snapshot)) {
                deleteStagingDirectory(snapshot);
            }
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }

    private void cleanStagingDirectories() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapshotsDir, ".staging-*")) {
            for (Path staging : stream) {
                deleteStagingDirectory(staging);
                if (Files.exists(staging)) {
                    throw new IOException("Could not remove interrupted staging directory: " + staging);
                }
            }
        }
    }

    private static void moveAtomically(Path source, Path destination, boolean replaceExisting) throws IOException {
        try {
            if (replaceExisting) {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("The filesystem does not support atomic snapshot publication.", exception);
        }
    }

    private static void deleteStagingDirectory(Path directory) {
        if (!Files.exists(directory) || Files.isSymbolicLink(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not clean temporary Sterling snapshot directory {}.", directory, exception);
        }
    }
}
