package com.github.yevhen.googleservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.google")
public class GoogleProperties {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String authUri;
    private String tokenUri;
    private String userinfoUri;
    private String calendarRedirectUri;
    private String calendarBaseUrl;

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

    public String getCalendarRedirectUri() { return calendarRedirectUri; }
    public void setCalendarRedirectUri(String calendarRedirectUri) { this.calendarRedirectUri = calendarRedirectUri; }

    public String getAuthUri() { return authUri; }
    public void setAuthUri(String authUri) { this.authUri = authUri; }

    public String getTokenUri() { return tokenUri; }
    public void setTokenUri(String tokenUri) { this.tokenUri = tokenUri; }

    public String getUserinfoUri() { return userinfoUri; }
    public void setUserinfoUri(String userinfoUri) { this.userinfoUri = userinfoUri; }

    public String getCalendarBaseUrl() { return calendarBaseUrl; }
    public void setCalendarBaseUrl(String calendarBaseUrl) { this.calendarBaseUrl = calendarBaseUrl; }
}
