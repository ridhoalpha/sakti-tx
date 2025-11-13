package id.go.kemenkeu.djpbn.sakti.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sakti.tx")
public class SaktiProperties {
    private Lock lock = new Lock();
    private Idempotency idempotency = new Idempotency();
    private Redis redis = new Redis();
    private Jms jms = new Jms();

    public static class Lock { private long waitMs = 5000; private long leaseMs = 30000; private String prefix = "sakti:lock:"; public long getWaitMs(){return waitMs;} public long getLeaseMs(){return leaseMs;} public String getPrefix(){return prefix;} public void setWaitMs(long v){this.waitMs=v;} public void setLeaseMs(long v){this.leaseMs=v;} public void setPrefix(String p){this.prefix=p;} }
    public static class Idempotency { private long ttlSeconds = 7200; private String prefix = "sakti:idemp:"; public long getTtlSeconds(){return ttlSeconds;} public String getPrefix(){return prefix;} public void setTtlSeconds(long v){this.ttlSeconds=v;} public void setPrefix(String p){this.prefix=p;} }
    public static class Redis { private String host = "127.0.0.1"; private int port = 6379; public String getHost(){return host;} public int getPort(){return port;} public void setHost(String h){this.host=h;} public void setPort(int p){this.port=p;} }
    public static class Jms { private boolean enabled = true; private long defaultTtlMs = 1800000; public boolean isEnabled(){return enabled;} public long getDefaultTtlMs(){return defaultTtlMs;} public void setEnabled(boolean e){this.enabled=e;} public void setDefaultTtlMs(long t){this.defaultTtlMs=t;} }

    public Lock getLock(){return lock;} public Idempotency getIdempotency(){return idempotency;} public Redis getRedis(){return redis;} public Jms getJms(){return jms;} }
