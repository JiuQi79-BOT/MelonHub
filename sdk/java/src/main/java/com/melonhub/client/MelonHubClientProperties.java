package com.melonhub.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * melonhub.client.* 配置项。base-url 默认 http://127.0.0.1:8080；api-key 为必填启用开关。
 */
@ConfigurationProperties("melonhub.client")
public class MelonHubClientProperties {

    /** 平台基址，如 http://127.0.0.1:8080 */
    private String baseUrl = "http://127.0.0.1:8080";

    /** 业务软件的 API Key（mh_...），配置后启用自动装配 */
    private String apiKey;

    /** 用户外部标识请求头 */
    private String userHeader = MelonHubPermissionInterceptor.DEFAULT_USER_HEADER;

    /** 部门编号请求头 */
    private String departmentHeader = MelonHubPermissionInterceptor.DEFAULT_DEPARTMENT_HEADER;

    private long connectTimeoutMillis = 3000;
    private long requestTimeoutMillis = 5000;

    /** 429/503 的最大重试次数 */
    private int maxRetries = 2;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getUserHeader() { return userHeader; }
    public void setUserHeader(String userHeader) { this.userHeader = userHeader; }
    public String getDepartmentHeader() { return departmentHeader; }
    public void setDepartmentHeader(String departmentHeader) { this.departmentHeader = departmentHeader; }
    public long getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(long connectTimeoutMillis) { this.connectTimeoutMillis = connectTimeoutMillis; }
    public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
    public void setRequestTimeoutMillis(long requestTimeoutMillis) { this.requestTimeoutMillis = requestTimeoutMillis; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
}
