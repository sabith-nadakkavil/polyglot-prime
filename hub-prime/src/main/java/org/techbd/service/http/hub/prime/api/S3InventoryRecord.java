package org.techbd.service.http.hub.prime.api;

public class S3InventoryRecord {
    private String bucket;
    private String key;
    private String lastModifiedDate;
    private String versionId;
    private Boolean isLatest;
    private Boolean isDeleteMarker;
    private String size;
    private String eTag;
    private String storageClass;
    private String encrptionStatus;
    private String objectLockRetainUntilDate;
    private String objectLockMode;
    private String objectLockLegalHoldStatus;
    private String intelligentTieringAccessTier;
    private String interactionId;
    private String fileName;
    private String msgType;
    private String uploadDate;
    private String fileSize;
    private String sourceSystem;
    private String s3ObjectPath;
    private String tenantId;
    private String timestamp;

    // Constructors
    public S3InventoryRecord() {}

    public S3InventoryRecord(String bucket, String key, String versionId, Boolean isLatest, Boolean isDeleteMarker, 
            String size, String lastModifiedDate, String eTag, String storageClass, String encrptionStatus, 
            String objectLockRetainUntilDate, String objectLockMode, String objectLockLegalHoldStatus, 
            String intelligentTieringAccessTier, String interactionId, String fileName, String msgType,
            String uploadDate, String fileSize, String sourceSystem, String s3ObjectPath, String tenantId,
            String timestamp) {
        this.bucket = bucket;
        this.key = key;
        this.versionId = versionId;
        this.isLatest = isLatest;
        this.isDeleteMarker = isDeleteMarker;
        this.size = size;
        this.lastModifiedDate = lastModifiedDate;
        this.eTag = eTag;
        this.storageClass = storageClass;
        this.encrptionStatus = encrptionStatus;
        this.objectLockRetainUntilDate = objectLockRetainUntilDate;
        this.objectLockMode = objectLockMode;
        this.objectLockLegalHoldStatus = objectLockLegalHoldStatus;
        this.intelligentTieringAccessTier = intelligentTieringAccessTier;
        this.interactionId = interactionId;
        this.fileName = fileName;
        this.msgType = msgType;
        this.uploadDate = uploadDate;
        this.fileSize = fileSize;
        this.sourceSystem = sourceSystem;
        this.s3ObjectPath = s3ObjectPath;
        this.tenantId = tenantId;
        this.timestamp = timestamp;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(String lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public Boolean getIsLatest() {
        return isLatest;
    }

    public void setIsLatest(Boolean isLatest) {
        this.isLatest = isLatest;
    }

    public Boolean getIsDeleteMarker() {
        return isDeleteMarker;
    }

    public void setIsDeleteMarker(Boolean isDeleteMarker) {
        this.isDeleteMarker = isDeleteMarker;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String geteTag() {
        return eTag;
    }

    public void seteTag(String eTag) {
        this.eTag = eTag;
    }

    public String getStorageClass() {
        return storageClass;
    }

    public void setStorageClass(String storageClass) {
        this.storageClass = storageClass;
    }

    public String getEncrptionStatus() {
        return encrptionStatus;
    }

    public void setEncrptionStatus(String encrptionStatus) {
        this.encrptionStatus = encrptionStatus;
    }

    public String getObjectLockRetainUntilDate() {
        return objectLockRetainUntilDate;
    }

    public void setObjectLockRetainUntilDate(String objectLockRetainUntilDate) {
        this.objectLockRetainUntilDate = objectLockRetainUntilDate;
    }

    public String getObjectLockMode() {
        return objectLockMode;
    }

    public void setObjectLockMode(String objectLockMode) {
        this.objectLockMode = objectLockMode;
    }

    public String getObjectLockLegalHoldStatus() {
        return objectLockLegalHoldStatus;
    }

    public void setObjectLockLegalHoldStatus(String objectLockLegalHoldStatus) {
        this.objectLockLegalHoldStatus = objectLockLegalHoldStatus;
    }

    public String getIntelligentTieringAccessTier() {
        return intelligentTieringAccessTier;
    }

    public void setIntelligentTieringAccessTier(String intelligentTieringAccessTier) {
        this.intelligentTieringAccessTier = intelligentTieringAccessTier;
    }

    public String getInteractionId() {
        return interactionId;
    }

    public void setInteractionId(String interactionId) {
        this.interactionId = interactionId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getUploadDate() {
        return uploadDate;
    }

    public void setUploadDate(String uploadDate) {
        this.uploadDate = uploadDate;
    }

    public String getFileSize() {
        return fileSize;
    }

    public void setFileSize(String fileSize) {
        this.fileSize = fileSize;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getS3ObjectPath() {
        return s3ObjectPath;
    }

    public void setS3ObjectPath(String s3ObjectPath) {
        this.s3ObjectPath = s3ObjectPath;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
    
}
