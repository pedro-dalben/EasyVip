package br.com.pedrodalben.easyvip.webstore.model;

import java.util.ArrayList;
import java.util.List;

public class FulfillmentRecord {

    private String fulfillmentId;
    private String orderId;
    private String minecraftUuid;
    private String minecraftUsername;
    private String payloadDigest;
    private String status;
    private String requestKeyId;
    private long createdAt;
    private Long completedAt;
    private Long failedAt;
    private String failureCode;

    private final List<FulfillmentItemRecord> items = new ArrayList<>();

    public String getFulfillmentId() { return fulfillmentId; }
    public void setFulfillmentId(String v) { this.fulfillmentId = v; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String v) { this.orderId = v; }

    public String getMinecraftUuid() { return minecraftUuid; }
    public void setMinecraftUuid(String v) { this.minecraftUuid = v; }

    public String getMinecraftUsername() { return minecraftUsername; }
    public void setMinecraftUsername(String v) { this.minecraftUsername = v; }

    public String getPayloadDigest() { return payloadDigest; }
    public void setPayloadDigest(String v) { this.payloadDigest = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getRequestKeyId() { return requestKeyId; }
    public void setRequestKeyId(String v) { this.requestKeyId = v; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long v) { this.createdAt = v; }

    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long v) { this.completedAt = v; }

    public Long getFailedAt() { return failedAt; }
    public void setFailedAt(Long v) { this.failedAt = v; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String v) { this.failureCode = v; }

    public List<FulfillmentItemRecord> getItems() { return items; }
}
