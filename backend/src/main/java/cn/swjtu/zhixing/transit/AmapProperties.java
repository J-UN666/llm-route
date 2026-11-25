package cn.swjtu.zhixing.transit;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 高德 Web 服务配置；密钥只由服务端环境变量 AMAP_WEB_SERVICE_KEY 注入。 */
@Validated
@ConfigurationProperties(prefix = "amap")
public class AmapProperties {
    private String webServiceKey = "";
    private String transitBaseUrl = "https://restapi.amap.com/v3/direction/transit/integrated";
    private String roadBaseUrl = "https://restapi.amap.com";
    @Min(1)
    private int connectTimeoutMillis = 3000;
    @Min(1)
    private int readTimeoutMillis = 10000;

    public String getWebServiceKey() {
        return webServiceKey;
    }

    public void setWebServiceKey(String webServiceKey) {
        this.webServiceKey = webServiceKey == null ? "" : webServiceKey.trim();
    }

    public String getTransitBaseUrl() {
        return transitBaseUrl;
    }

    public void setTransitBaseUrl(String transitBaseUrl) {
        if (transitBaseUrl != null && !transitBaseUrl.isBlank()) {
            this.transitBaseUrl = transitBaseUrl.trim();
        }
    }

    public String getRoadBaseUrl() {
        return roadBaseUrl;
    }

    public void setRoadBaseUrl(String roadBaseUrl) {
        if (roadBaseUrl != null && !roadBaseUrl.isBlank()) {
            this.roadBaseUrl = roadBaseUrl.trim();
        }
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }
}
