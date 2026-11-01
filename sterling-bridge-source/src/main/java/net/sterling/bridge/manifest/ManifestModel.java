package net.sterling.bridge.manifest;

import java.util.ArrayList;
import java.util.List;

public class ManifestModel {
    private int schemaVersion;
    private String snapshotId;
    private String installType;
    private String serverName;
    private String minecraftVersion;
    private String loaderType;
    private String loaderVersion;
    private String generatedAt;
    private String modpackDownloadLink;
    private String identityAlgorithm;
    private String identityPublicKey;
    private String manifestSignature;
    private List<ModEntry> mods = new ArrayList<>();

    public ManifestModel() {}

    public ManifestModel(String serverName, String minecraftVersion, String loaderType, String loaderVersion, String generatedAt) {
        this.serverName = serverName;
        this.minecraftVersion = minecraftVersion;
        this.loaderType = loaderType;
        this.loaderVersion = loaderVersion;
        this.generatedAt = generatedAt;
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

    public String getInstallType() { return installType; }
    public void setInstallType(String installType) { this.installType = installType; }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getMinecraftVersion() { return minecraftVersion; }
    public void setMinecraftVersion(String minecraftVersion) { this.minecraftVersion = minecraftVersion; }

    public String getLoaderType() { return loaderType; }
    public void setLoaderType(String loaderType) { this.loaderType = loaderType; }

    public String getLoaderVersion() { return loaderVersion; }
    public void setLoaderVersion(String loaderVersion) { this.loaderVersion = loaderVersion; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public String getModpackDownloadLink() { return modpackDownloadLink; }
    public void setModpackDownloadLink(String modpackDownloadLink) { this.modpackDownloadLink = modpackDownloadLink; }

    public String getIdentityAlgorithm() { return identityAlgorithm; }
    public void setIdentityAlgorithm(String identityAlgorithm) { this.identityAlgorithm = identityAlgorithm; }

    public String getIdentityPublicKey() { return identityPublicKey; }
    public void setIdentityPublicKey(String identityPublicKey) { this.identityPublicKey = identityPublicKey; }

    public String getManifestSignature() { return manifestSignature; }
    public void setManifestSignature(String manifestSignature) { this.manifestSignature = manifestSignature; }

    public List<ModEntry> getMods() { return mods; }
    public void setMods(List<ModEntry> mods) { this.mods = mods; }

    public static class ModEntry {
        private String fileName;
        private String sha256;
        private String downloadUrl;
        private String modId;
        private String version;
        private Long sizeBytes;
        private Boolean required;

        public ModEntry() {}

        public ModEntry(String fileName, String sha256, String downloadUrl, String modId, String version) {
            this.fileName = fileName;
            this.sha256 = sha256;
            this.downloadUrl = downloadUrl;
            this.modId = modId;
            this.version = version;
        }

        public ModEntry(String fileName, String sha256, String downloadUrl, String modId, String version,
                        long sizeBytes, boolean required) {
            this(fileName, sha256, downloadUrl, modId, version);
            this.sizeBytes = sizeBytes;
            this.required = required;
        }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public String getSha256() { return sha256; }
        public void setSha256(String sha256) { this.sha256 = sha256; }

        public String getDownloadUrl() { return downloadUrl; }
        public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

        public String getModId() { return modId; }
        public void setModId(String modId) { this.modId = modId; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public Long getSizeBytes() { return sizeBytes; }
        public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

        public Boolean getRequired() { return required; }
        public void setRequired(Boolean required) { this.required = required; }
    }
}
