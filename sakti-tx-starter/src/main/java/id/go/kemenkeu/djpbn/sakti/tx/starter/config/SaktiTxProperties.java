package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sakti.tx")
public class SaktiTxProperties {
    
    private Dragonfly dragonfly = new Dragonfly();
    private Lock lock = new Lock();
    private Cache cache = new Cache();
    private Idempotency idempotency = new Idempotency();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Jms jms = new Jms();
    private MultiDb multiDb = new MultiDb();
    private Health health = new Health();
    
    public static class Dragonfly {
        private boolean enabled = false;
        private String url = "redis://localhost:6379";
        private String password = "";
        private Pool pool = new Pool();
        private int timeout = 3000;
        private int connectTimeout = 5000;
        private boolean verifyDurability = true;
        private boolean waitForSync = false;
        private int waitForSyncTimeoutMs = 5000;
        
        public static class Pool {
            private int size = 64;
            private int minIdle = 10;
            
            public int getSize() { return size; }
            public void setSize(int size) { this.size = size; }
            
            public int getMinIdle() { return minIdle; }
            public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
        }
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        
        public Pool getPool() { return pool; }
        public void setPool(Pool pool) { this.pool = pool; }
        
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
        
        public int getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
        
        public boolean isVerifyDurability() { return verifyDurability; }
        public void setVerifyDurability(boolean verifyDurability) { 
            this.verifyDurability = verifyDurability; 
        }
        
        public boolean isWaitForSync() { return waitForSync; }
        public void setWaitForSync(boolean waitForSync) { this.waitForSync = waitForSync; }
        
        public int getWaitForSyncTimeoutMs() { return waitForSyncTimeoutMs; }
        public void setWaitForSyncTimeoutMs(int waitForSyncTimeoutMs) { 
            this.waitForSyncTimeoutMs = waitForSyncTimeoutMs; 
        }
    }
    
    public static class Lock {
        private boolean enabled = false;
        private String prefix = "sakti:lock:";
        private long waitTimeMs = 5000;
        private long leaseTimeMs = 30000;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        
        public long getWaitTimeMs() { return waitTimeMs; }
        public void setWaitTimeMs(long waitTimeMs) { this.waitTimeMs = waitTimeMs; }
        
        public long getLeaseTimeMs() { return leaseTimeMs; }
        public void setLeaseTimeMs(long leaseTimeMs) { this.leaseTimeMs = leaseTimeMs; }
    }
    
    public static class Cache {
        private boolean enabled = false;
        private String prefix = "sakti:cache:";
        private long defaultTtlSeconds = 600;
        private int maxEntries = 10000;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        
        public long getDefaultTtlSeconds() { return defaultTtlSeconds; }
        public void setDefaultTtlSeconds(long defaultTtlSeconds) { 
            this.defaultTtlSeconds = defaultTtlSeconds; 
        }
        
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
    }
    
    public static class Idempotency {
        private boolean enabled = false;
        private String prefix = "sakti:idemp:";
        private long ttlSeconds = 7200;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }
    
    public static class CircuitBreaker {
        private boolean enabled = true;
        private int failureThreshold = 5;
        private long recoveryTimeoutMs = 30000;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { 
            this.failureThreshold = failureThreshold; 
        }
        
        public long getRecoveryTimeoutMs() { return recoveryTimeoutMs; }
        public void setRecoveryTimeoutMs(long recoveryTimeoutMs) { 
            this.recoveryTimeoutMs = recoveryTimeoutMs; 
        }
    }
    
    public static class Jms {
        private boolean enabled = false;
        private String brokerUrl = "";
        private String user = "";
        private String password = "";
        private long defaultTtlMs = 1800000;
        private String existingFactoryBeanName = "";
        private boolean testOnStartup = true;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getBrokerUrl() { return brokerUrl; }
        public void setBrokerUrl(String brokerUrl) { this.brokerUrl = brokerUrl; }
        
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        
        public long getDefaultTtlMs() { return defaultTtlMs; }
        public void setDefaultTtlMs(long defaultTtlMs) { this.defaultTtlMs = defaultTtlMs; }
        
        public String getExistingFactoryBeanName() { return existingFactoryBeanName; }
        public void setExistingFactoryBeanName(String existingFactoryBeanName) { 
            this.existingFactoryBeanName = existingFactoryBeanName; 
        }
        
        public boolean isTestOnStartup() { return testOnStartup; }
        public void setTestOnStartup(boolean testOnStartup) { this.testOnStartup = testOnStartup; }
    }
    
    public static class MultiDb {
        private boolean enabled = false;
        private RollbackStrategy rollbackStrategy = RollbackStrategy.COMPENSATING;
        private int maxRollbackRetries = 3;
        private long rollbackRetryBackoffMs = 1000;
        private Recovery recovery = new Recovery();
        private AdminApi adminApi = new AdminApi();
        
        public enum RollbackStrategy {
            COMPENSATING,
            MANUAL
        }
        
        /**
         * Recovery worker configuration
         */
        public static class Recovery {
            private boolean enabled = true;
            private long scanIntervalMs = 60000;      // 1 minute
            private long initialDelayMs = 30000;      // 30 seconds
            private long stallTimeoutMs = 300000;     // 5 minutes
            private int maxRecoveryAttempts = 5;
            
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            
            public long getScanIntervalMs() { return scanIntervalMs; }
            public void setScanIntervalMs(long scanIntervalMs) { 
                this.scanIntervalMs = scanIntervalMs; 
            }
            
            public long getInitialDelayMs() { return initialDelayMs; }
            public void setInitialDelayMs(long initialDelayMs) { 
                this.initialDelayMs = initialDelayMs; 
            }
            
            public long getStallTimeoutMs() { return stallTimeoutMs; }
            public void setStallTimeoutMs(long stallTimeoutMs) { 
                this.stallTimeoutMs = stallTimeoutMs; 
            }
            
            public int getMaxRecoveryAttempts() { return maxRecoveryAttempts; }
            public void setMaxRecoveryAttempts(int maxRecoveryAttempts) { 
                this.maxRecoveryAttempts = maxRecoveryAttempts; 
            }
        }
        
        /**
         * Admin API configuration
         */
        public static class AdminApi {
            private boolean enabled = true;
            
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
        }
        
        // Existing getters and setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public RollbackStrategy getRollbackStrategy() { return rollbackStrategy; }
        public void setRollbackStrategy(RollbackStrategy rollbackStrategy) { 
            this.rollbackStrategy = rollbackStrategy; 
        }
        
        public int getMaxRollbackRetries() { return maxRollbackRetries; }
        public void setMaxRollbackRetries(int maxRollbackRetries) { 
            this.maxRollbackRetries = maxRollbackRetries; 
        }
        
        public long getRollbackRetryBackoffMs() { return rollbackRetryBackoffMs; }
        public void setRollbackRetryBackoffMs(long rollbackRetryBackoffMs) { 
            this.rollbackRetryBackoffMs = rollbackRetryBackoffMs; 
        }
        
        // NEW getters
        public Recovery getRecovery() { return recovery; }
        public void setRecovery(Recovery recovery) { this.recovery = recovery; }
        
        public AdminApi getAdminApi() { return adminApi; }
        public void setAdminApi(AdminApi adminApi) { this.adminApi = adminApi; }
    }
    
    public static class Health {
        private boolean enabled = true;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
    
    public Dragonfly getDragonfly() { return dragonfly; }
    public void setDragonfly(Dragonfly dragonfly) { this.dragonfly = dragonfly; }
    
    public Lock getLock() { return lock; }
    public void setLock(Lock lock) { this.lock = lock; }
    
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }
    
    public Idempotency getIdempotency() { return idempotency; }
    public void setIdempotency(Idempotency idempotency) { this.idempotency = idempotency; }
    
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreaker circuitBreaker) { 
        this.circuitBreaker = circuitBreaker; 
    }
    
    public Jms getJms() { return jms; }
    public void setJms(Jms jms) { this.jms = jms; }
    
    public MultiDb getMultiDb() { return multiDb; }
    public void setMultiDb(MultiDb multiDb) { this.multiDb = multiDb; }
    
    public Health getHealth() { return health; }
    public void setHealth(Health health) { this.health = health; }
}