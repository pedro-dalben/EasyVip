package br.com.pedrodalben.easyvip.webstore;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FulfillmentConfig {

    public boolean enabled = false;
    public String bindAddress = "127.0.0.1";
    public int port = 28765;
    public int maxRequestBytes = 16384;
    public int timestampToleranceSeconds = 60;
    public int requestTimeoutSeconds = 10;
    public boolean allowPublicBind = false;
    public boolean requireSql = true;
    public int maxNonceCacheSize = 20000;

    public final FulfillmentKeyConfig keys = new FulfillmentKeyConfig();
    public final Map<String, FulfillmentProductConfig> products = new LinkedHashMap<>();
}
