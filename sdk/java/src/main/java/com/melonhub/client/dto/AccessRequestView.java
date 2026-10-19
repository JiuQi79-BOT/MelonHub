package com.melonhub.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 角色申请单。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessRequestView {

    private Long id;
    private String status;
    private String reason;
    private String decisionComment;
    private String grantExpiresAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDecisionComment() { return decisionComment; }
    public void setDecisionComment(String decisionComment) { this.decisionComment = decisionComment; }
    public String getGrantExpiresAt() { return grantExpiresAt; }
    public void setGrantExpiresAt(String grantExpiresAt) { this.grantExpiresAt = grantExpiresAt; }
}
