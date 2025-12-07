package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.cache.CacheManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.compensate.CompensatingTransactionExecutor;
import id.go.kemenkeu.djpbn.sakti.tx.core.idempotency.IdempotencyManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.listener.EntityOperationListener;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.LockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.RedissonLockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLogManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.mapper.EntityManagerDatasourceMapper;
import id.go.kemenkeu.djpbn.sakti.tx.starter.admin.TransactionAdminController;
import id.go.kemenkeu.djpbn.sakti.tx.starter.aspect.*;
import id.go.kemenkeu.djpbn.sakti.tx.starter.event.JmsEventPublisher;
import id.go.kemenkeu.djpbn.sakti.tx.starter.health.DragonflyHealthIndicator;
import id.go.kemenkeu.djpbn.sakti.tx.starter.interceptor.ServiceOperationInterceptor;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.DistributedLockService;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.impl.DistributedLockServiceImpl;
import id.go.kemenkeu.djpbn.sakti.tx.starter.worker.TransactionRecoveryWorker;
import jakarta.jms.ConnectionFactory;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(SaktiTxProperties.class)
@EnableScheduling
public class SaktiTxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SaktiTxAutoConfiguration.class);

    private final SaktiTxProperties properties;

    public SaktiTxAutoConfiguration(SaktiTxProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validateConfiguration() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("SAKTI Transaction Coordinator v1.0.0 - Initializing...");
        log.info("═══════════════════════════════════════════════════════════");
        
        boolean hasErrors = false;

        if (properties.getMultiDb().isEnabled()) {
            log.info("Setting up automatic entity tracking with JPA listeners");
            EntityOperationListener.setObjectMapper(objectMapper());
        }
        
        // Validate Dragonfly-dependent features
        if (!properties.getDragonfly().isEnabled()) {
            if (properties.getLock().isEnabled()) {
                log.error("CONFIGURATION ERROR: sakti.tx.lock.enabled=true requires sakti.tx.dragonfly.enabled=true");
                log.error("   → Solution: Set sakti.tx.dragonfly.enabled=true OR sakti.tx.lock.enabled=false");
                hasErrors = true;
            }
            if (properties.getCache().isEnabled()) {
                log.error("CONFIGURATION ERROR: sakti.tx.cache.enabled=true requires sakti.tx.dragonfly.enabled=true");
                log.error("   → Solution: Set sakti.tx.dragonfly.enabled=true OR sakti.tx.cache.enabled=false");
                hasErrors = true;
            }
            if (properties.getIdempotency().isEnabled()) {
                log.error("CONFIGURATION ERROR: sakti.tx.idempotency.enabled=true requires sakti.tx.dragonfly.enabled=true");
                log.error("   → Solution: Set sakti.tx.dragonfly.enabled=true OR sakti.tx.idempotency.enabled=false");
                hasErrors = true;
            }
            if (properties.getMultiDb().isEnabled()) {
                log.error("CONFIGURATION ERROR: sakti.tx.multi-db.enabled=true requires sakti.tx.dragonfly.enabled=true");
                hasErrors = true;
            }
            if (!hasErrors) {
                log.warn("Dragonfly is DISABLED - lock/cache/idempotency features will not work");
            }
        }
        
        if (hasErrors) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("APPLICATION STARTUP WILL FAIL DUE TO CONFIGURATION ERRORS");
            log.error("═══════════════════════════════════════════════════════════");
        }
        
        logFeatureStatus("Dragonfly/Redis", properties.getDragonfly().isEnabled(), 
            properties.getDragonfly().isEnabled() ? maskUrl(properties.getDragonfly().getUrl()) : "disabled");
        logFeatureStatus("Distributed Transaction", properties.getMultiDb().isEnabled(), 
            properties.getMultiDb().getRollbackStrategy().toString());
        if (properties.getMultiDb().isEnabled()) {
            logFeatureStatus("  → Recovery Worker", 
                properties.getMultiDb().getRecovery().isEnabled(),
                String.format("scan=%dms, timeout=%dms", 
                    properties.getMultiDb().getRecovery().getScanIntervalMs(),
                    properties.getMultiDb().getRecovery().getStallTimeoutMs()));
            logFeatureStatus("  → Admin API", 
                properties.getMultiDb().getAdminApi().isEnabled(),
                "endpoints: /admin/transactions/*");
        }
        logFeatureStatus("Distributed Lock", properties.getLock().isEnabled(), 
            getDependencyStatus(properties.getLock().isEnabled(), properties.getDragonfly().isEnabled()));logFeatureStatus("Cache Manager", properties.getCache().isEnabled(), 
            getDependencyStatus(properties.getCache().isEnabled(), properties.getDragonfly().isEnabled()));
        logFeatureStatus("Idempotency", properties.getIdempotency().isEnabled(), 
            getDependencyStatus(properties.getIdempotency().isEnabled(), properties.getDragonfly().isEnabled()));
        logFeatureStatus("Circuit Breaker", properties.getCircuitBreaker().isEnabled(), 
            "threshold=" + properties.getCircuitBreaker().getFailureThreshold());
        logFeatureStatus("JMS Events", properties.getJms().isEnabled(), 
            properties.getJms().isEnabled() ? "enabled" : "disabled");
        logFeatureStatus("Health Indicator", properties.getHealth().isEnabled(), "actuator");
        log.info("═══════════════════════════════════════════════════════════");
    }
    
    private void logFeatureStatus(String feature, boolean enabled, String detail) {
        if (enabled) {
            log.info("{} - ENABLED ({})", feature, detail);
        } else {
            log.info("{} - DISABLED", feature);
        }
    }
    
    private String getDependencyStatus(boolean featureEnabled, boolean dragonflyEnabled) {
        if (!featureEnabled) return "disabled";
        if (!dragonflyEnabled) return "REQUIRES DRAGONFLY";
        return "ready";
    }
    
    private String maskUrl(String url) {
        if (url == null || !url.contains("@")) return url;
        return url.replaceAll("://:[^@]+@", "://****@");
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        log.debug("Creating default ObjectMapper");
        return new ObjectMapper();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    @ConditionalOnProperty(prefix = "sakti.tx.dragonfly", name = "enabled", havingValue = "true")
    public RedissonClient redissonClient(SaktiTxProperties properties) {
        log.info("Creating RedissonClient: {}", maskUrl(properties.getDragonfly().getUrl()));

        try {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress(properties.getDragonfly().getUrl())
                    .setPassword(properties.getDragonfly().getPassword().isEmpty() ? null
                            : properties.getDragonfly().getPassword())
                    .setConnectionPoolSize(properties.getDragonfly().getPool().getSize())
                    .setConnectionMinimumIdleSize(properties.getDragonfly().getPool().getMinIdle())
                    .setTimeout(properties.getDragonfly().getTimeout())
                    .setConnectTimeout(properties.getDragonfly().getConnectTimeout())
                    .setRetryAttempts(3)
                    .setRetryInterval(1500);

            RedissonClient client = Redisson.create(config);
            client.getKeys().count();
            
            log.info("RedissonClient created successfully");
            return client;
        } catch (Exception e) {
            log.error("Failed to create RedissonClient: {}", e.getMessage());
            log.error("   → Check Dragonfly connectivity: {}", maskUrl(properties.getDragonfly().getUrl()));
            throw new IllegalStateException("Cannot initialize Dragonfly connection: " + e.getMessage(), e);
        }
    }

    @Bean
    @ConditionalOnMissingBean(LockManager.class)
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    @ConditionalOnBean(RedissonClient.class)
    public LockManager lockManager(RedissonClient redissonClient) {
        log.info("Creating LockManager");
        return new RedissonLockManager(redissonClient);
    }

    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.idempotency", name = "enabled", havingValue = "true")
    @ConditionalOnBean(RedissonClient.class)
    public IdempotencyManager idempotencyManager(
            RedissonClient redissonClient,
            SaktiTxProperties properties) {
        log.info("Creating IdempotencyManager (prefix: {})", properties.getIdempotency().getPrefix());
        return new IdempotencyManager(redissonClient, properties.getIdempotency().getPrefix());
    }

    @Bean("saktiCacheManager")
    @ConditionalOnMissingBean(name = "saktiCacheManager")
    @ConditionalOnProperty(prefix = "sakti.tx.cache", name = "enabled", havingValue = "true")
    @ConditionalOnBean(RedissonClient.class)
    public CacheManager saktiCacheManager(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            SaktiTxProperties properties) {
        log.info("Creating CacheManager (prefix: {})", properties.getCache().getPrefix());
        return new CacheManager(redissonClient, objectMapper, properties.getCache().getPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(DragonflyHealthIndicator.class)
    @ConditionalOnProperty(prefix = "sakti.tx.health", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnBean(RedissonClient.class)
    public DragonflyHealthIndicator dragonflyHealthIndicator(
            RedissonClient redissonClient,
            SaktiTxProperties properties) {
        log.info("Creating DragonflyHealthIndicator");
        return new DragonflyHealthIndicator(redissonClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiLockAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    @ConditionalOnBean(LockManager.class)
    public SaktiLockAspect saktiLockAspect(
            LockManager lockManager,
            SaktiTxProperties properties,
            @Autowired(required = false) RedissonClient redissonClient) {
        log.info("Creating SaktiLockAspect");
        return new SaktiLockAspect(lockManager, properties, redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiCacheAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.cache", name = "enabled", havingValue = "true")
    @ConditionalOnBean(name = "saktiCacheManager")
    public SaktiCacheAspect saktiCacheAspect(
            CacheManager saktiCacheManager,
            SaktiTxProperties properties,
            @Autowired(required = false) RedissonClient redissonClient) {
        log.info("Creating SaktiCacheAspect");
        return new SaktiCacheAspect(saktiCacheManager, properties, redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiIdempotentAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.idempotency", name = "enabled", havingValue = "true")
    @ConditionalOnBean(IdempotencyManager.class)
    public SaktiIdempotentAspect saktiIdempotentAspect(
            SaktiTxProperties properties,
            @Autowired(required = false) IdempotencyManager idempotencyManager,
            @Autowired(required = false) RedissonClient redissonClient) {
        log.info("Creating SaktiIdempotentAspect");
        return new SaktiIdempotentAspect(properties, idempotencyManager, redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean({DistributedLockService.class, IdempotencyManager.class})
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    @ConditionalOnBean(LockManager.class)
    public DistributedLockService distributedLockService(
            LockManager lockManager,
            SaktiTxProperties properties,
            ApplicationContext applicationContext) {
        
        IdempotencyManager idempotencyManager = null;
        DragonflyHealthIndicator healthIndicator = null;
        
        try {
            idempotencyManager = applicationContext.getBean(IdempotencyManager.class);
            log.info("DistributedLockService - IdempotencyManager available");
        } catch (Exception e) {
            log.info("DistributedLockService - IdempotencyManager not available (lock-only mode)");
        }
        
        try {
            healthIndicator = applicationContext.getBean(DragonflyHealthIndicator.class);
            log.info("DistributedLockService - DragonflyHealthIndicator available");
        } catch (Exception e) {
            log.info("DistributedLockService - DragonflyHealthIndicator not available");
        }
        
        log.info("Creating DistributedLockService");
        return new DistributedLockServiceImpl(lockManager, idempotencyManager, healthIndicator, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    @ConditionalOnBean(RedissonClient.class)
    public TransactionLogManager transactionLogManager(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            SaktiTxProperties properties) {
        log.info("Creating TransactionLogManager");
        
        boolean waitForSync = properties.getDragonfly().isWaitForSync();
        int waitTimeoutMs = properties.getDragonfly().getWaitForSyncTimeoutMs();
        
        if (waitForSync) {
            log.info("   WAIT-FOR-SYNC: ENABLED (timeout: {}ms)", waitTimeoutMs);
            log.info("   Transaction logs will wait for disk sync (higher durability, slight latency)");
        } else {
            log.info("   WAIT-FOR-SYNC: DISABLED (better performance, lower durability guarantee)");
            log.info("   Enable with: sakti.tx.dragonfly.wait-for-sync=true");
        }
        
        return new TransactionLogManager(redissonClient, objectMapper, waitForSync, waitTimeoutMs);
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.dragonfly", name = "verify-durability", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(RedissonClient.class)
    public RedisDurabilityVerifier redisDurabilityVerifier(
            RedissonClient redissonClient,
            SaktiTxProperties properties) {
        log.info("Creating RedisDurabilityVerifier");
        return new RedisDurabilityVerifier(redissonClient, properties);
    }
    
    @Bean
    @ConditionalOnMissingBean(EntityManagerDatasourceMapper.class)
    public EntityManagerDatasourceMapper entityManagerDatasourceMapper(ApplicationContext ctx) {
        log.info("Creating EntityManagerDatasourceMapper");
        
        EntityManagerDatasourceMapper mapper = new EntityManagerDatasourceMapper();
        
        // Auto-register all EntityManager beans
        Map<String, EntityManager> emBeans = ctx.getBeansOfType(EntityManager.class);
        for (Map.Entry<String, EntityManager> entry : emBeans.entrySet()) {
            String beanName = entry.getKey();
            EntityManager em = entry.getValue();
            
            // Extract datasource name from bean name
            String datasourceName = extractDatasourceName(beanName);
            mapper.registerEntityManager(datasourceName, em);
            
            log.info("Auto-registered EntityManager: {} -> {}", datasourceName, beanName);
        }
        
        // Also check EntityManagerFactory beans for better detection
        Map<String, LocalContainerEntityManagerFactoryBean> emfBeans = 
            ctx.getBeansOfType(LocalContainerEntityManagerFactoryBean.class);
        
        for (Map.Entry<String, LocalContainerEntityManagerFactoryBean> entry : emfBeans.entrySet()) {
            try {
                String beanName = entry.getKey();
                EntityManager em = entry.getValue().getObject().createEntityManager();
                String datasourceName = extractDatasourceName(beanName);
                
                // Only register if not already registered
                if (mapper.getAllEntityManagers().get(datasourceName) == null) {
                    mapper.registerEntityManager(datasourceName, em);
                    log.info("Auto-registered EntityManager from factory: {} -> {}", 
                        datasourceName, beanName);
                }
            } catch (Exception e) {
                log.warn("Could not create EntityManager from factory: {}", entry.getKey());
            }
        }
        
        return mapper;
    }
    
    private String extractDatasourceName(String beanName) {
        String lower = beanName.toLowerCase();
        
        if (lower.contains("db1") || lower.contains("primary") || lower.contains("first")) {
            return "db1";
        } else if (lower.contains("db2") || lower.contains("secondary") || lower.contains("second")) {
            return "db2";
        } else if (lower.contains("db3") || lower.contains("third")) {
            return "db3";
        } else if (lower.contains("entitymanager")) {
            return "default";
        }
        
        return beanName;
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public CompensatingTransactionExecutor compensatingTransactionExecutor(
            EntityManagerDatasourceMapper mapper,
            ObjectMapper objectMapper) {
        log.info("Creating CompensatingTransactionExecutor");
        
        Map<String, EntityManager> entityManagers = mapper.getAllEntityManagers();
        
        if (entityManagers.isEmpty()) {
            log.warn("No EntityManager beans found - compensating transactions may fail");
        } else {
            log.info("Found {} EntityManager beans", entityManagers.size());
        }
        
        return new CompensatingTransactionExecutor(entityManagers, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean({TransactionLogManager.class, CompensatingTransactionExecutor.class})
    public TransactionRecoveryWorker transactionRecoveryWorker(
            TransactionLogManager logManager,
            CompensatingTransactionExecutor compensator,
            SaktiTxProperties properties) {
        log.info("Creating TransactionRecoveryWorker");
        log.info("   Scan Interval: {}ms", properties.getMultiDb().getRecovery().getScanIntervalMs());
        log.info("   Stall Timeout: {}ms", properties.getMultiDb().getRecovery().getStallTimeoutMs());
        log.info("   Max Recovery Attempts: {}", properties.getMultiDb().getRecovery().getMaxRecoveryAttempts());
        return new TransactionRecoveryWorker(logManager, compensator, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db.admin-api", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean({TransactionLogManager.class, CompensatingTransactionExecutor.class, TransactionRecoveryWorker.class})
    public TransactionAdminController transactionAdminController(
            TransactionLogManager logManager,
            CompensatingTransactionExecutor compensator,
            TransactionRecoveryWorker recoveryWorker) {
        log.info("Creating TransactionAdminController");
        log.info("   Admin API endpoints available at: /admin/transactions/*");
        log.info("   WARNING: Ensure proper authentication/authorization is configured");
        return new TransactionAdminController(logManager, compensator, recoveryWorker);
}
    
    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public ServiceOperationInterceptor serviceOperationInterceptor(ObjectMapper objectMapper) {
        log.info("Creating ServiceOperationInterceptor");
        return new ServiceOperationInterceptor(objectMapper);
    }
    
    @Bean
    @ConditionalOnMissingBean(SaktiDistributedTxAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    @ConditionalOnBean({TransactionLogManager.class, CompensatingTransactionExecutor.class})
    public SaktiDistributedTxAspect saktiDistributedTxAspect(
            SaktiTxProperties properties,
            TransactionLogManager logManager,
            CompensatingTransactionExecutor compensator,
            EntityManagerDatasourceMapper emMapper,
            @Autowired(required = false) LockManager lockManager) {
        log.info("Creating SaktiDistributedTxAspect");
        return new SaktiDistributedTxAspect(properties, logManager, compensator, emMapper, lockManager);
    }

    /**
     * IMPROVED JMS BEAN CONFIGURATION
     * 
     * SCENARIOS HANDLED:
     * 1. User provides ConnectionFactory bean → use that
     * 2. User sets broker-url + has artemis-jakarta-client → auto-create
     * 3. User sets broker-url but NO artemis-jakarta-client → log error + skip
     * 4. JMS disabled → skip
     * 5. jakarta.jms NOT in classpath → skip silently
     */
    @Bean
    @ConditionalOnMissingBean(JmsEventPublisher.class)
    @ConditionalOnProperty(prefix = "sakti.tx.jms", name = "enabled", havingValue = "true")
    public JmsEventPublisher jmsEventPublisher(
            ApplicationContext applicationContext,
            SaktiTxProperties properties) {
        
        log.info("═══════════════════════════════════════════════════════════");
        log.info("JMS Event Publisher - Initialization");
        log.info("═══════════════════════════════════════════════════════════");
        
        Object connectionFactory = null;
        String factorySource = null;
        
        try {
            // ═══════════════════════════════════════════════════════════════
            // STEP 1: Check if jakarta.jms.ConnectionFactory exists
            // ═══════════════════════════════════════════════════════════════
            Class<?> connectionFactoryClass = null;
            try {
                connectionFactoryClass = Class.forName("jakarta.jms.ConnectionFactory");
                log.debug("jakarta.jms.ConnectionFactory found in classpath");
            } catch (ClassNotFoundException e) {
                // Should not happen due to @ConditionalOnClass, but safety check
                log.warn("jakarta.jms.ConnectionFactory not found");
            }

            if (connectionFactoryClass != null) {
                // ═══════════════════════════════════════════════════════════════
                // STEP 2: Try to use existing ConnectionFactory bean (if provided)
                // ═══════════════════════════════════════════════════════════════
                String existingBeanName = properties.getJms().getExistingFactoryBeanName();
                if (existingBeanName != null && !existingBeanName.trim().isEmpty()) {
                    try {
                        connectionFactory = applicationContext.getBean(existingBeanName, connectionFactoryClass);
                        factorySource = "existing bean: " + existingBeanName;
                        log.info("Using existing ConnectionFactory: {}", existingBeanName);
                    } catch (Exception e) {
                        log.warn("Cannot find ConnectionFactory bean '{}': {}", 
                            existingBeanName, e.getMessage());
                    }
                }
                
                // ═══════════════════════════════════════════════════════════════
                // STEP 3: Try to get default ConnectionFactory bean
                // ═══════════════════════════════════════════════════════════════
                if (connectionFactory == null) {
                    try {
                        connectionFactory = applicationContext.getBean(connectionFactoryClass);
                        factorySource = "default bean";
                        log.info("Using default ConnectionFactory bean");
                    } catch (Exception e) {
                        log.debug("No default ConnectionFactory bean found");
                    }
                }   
            }
            
            // ═══════════════════════════════════════════════════════════════
            // STEP 4: Try to auto-create ActiveMQ ConnectionFactory
            // ═══════════════════════════════════════════════════════════════
            if (connectionFactory == null) {
                String brokerUrl = properties.getJms().getBrokerUrl();
                
                if (brokerUrl == null || brokerUrl.trim().isEmpty()) {
                    log.error("═══════════════════════════════════════════════════════════");
                    log.error("JMS Configuration Error");
                    log.error("═══════════════════════════════════════════════════════════");
                    log.error("sakti.tx.jms.enabled=true but no ConnectionFactory available");
                    log.error("");
                    log.error("Solutions:");
                    log.error("  1. Provide existing ConnectionFactory:");
                    log.error("     sakti.tx.jms.existing-factory-bean-name=myConnectionFactory");
                    log.error("");
                    log.error("  2. Auto-create new ConnectionFactory:");
                    log.error("     sakti.tx.jms.broker-url=tcp://localhost:61616");
                    log.error("     (requires artemis-jakarta-client dependency)");
                    log.error("");
                    log.error("  3. Create your own ConnectionFactory bean");
                    log.error("");
                    log.error("  4. Disable JMS:");
                    log.error("     sakti.tx.jms.enabled=false");
                    log.error("═══════════════════════════════════════════════════════════");
                    return null;
                }
                
                // Try to create ActiveMQ ConnectionFactory
                connectionFactory = createActiveMQConnectionFactory(brokerUrl, properties);
                if (connectionFactory != null) {
                    factorySource = "auto-created ActiveMQConnectionFactory: " + brokerUrl;
                }
            }
            
            // ═══════════════════════════════════════════════════════════════
            // STEP 5: Validate and test connection
            // ═══════════════════════════════════════════════════════════════
            if (connectionFactory == null) {
                log.error("Failed to obtain or create ConnectionFactory");
                return null;
            }
            
            log.info("ConnectionFactory obtained: {}", factorySource);
            
            // Optional: Test connection on startup
            if (properties.getJms().isTestOnStartup()) {
                if (!testJmsConnection(connectionFactory, factorySource)) {
                    log.warn("JMS connection test failed - bean will still be created");
                    log.warn("   JMS operations may fail at runtime");
                } else {
                    log.info("JMS connection test successful");
                }
            }
            
            // ═══════════════════════════════════════════════════════════════
            // STEP 6: Create JmsEventPublisher bean
            // ═══════════════════════════════════════════════════════════════
            log.info("Creating JmsEventPublisher");
            log.info("═══════════════════════════════════════════════════════════");
            
            return new JmsEventPublisher(connectionFactory, properties);
            
        } catch (Exception e) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Unexpected error during JMS initialization");
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Error: {}", e.getMessage(), e);
            log.error("");
            log.error("Suggestions:");
            log.error("  • Check ActiveMQ/Artemis is running");
            log.error("  • Verify broker URL is correct");
            log.error("  • Ensure artemis-jakarta-client dependency is present");
            log.error("  • Disable JMS if not needed: sakti.tx.jms.enabled=false");
            log.error("═══════════════════════════════════════════════════════════");
            return null;
        }
    }

    /**
     * Create ActiveMQ Artemis ConnectionFactory using reflection
     * 
     * This method uses reflection to avoid hard dependency on artemis-jakarta-client.
     * If the library is not in classpath, it will gracefully fail with clear instructions.
     * 
     * @return ConnectionFactory instance or null if creation failed
     */
    private Object createActiveMQConnectionFactory(String brokerUrl, SaktiTxProperties properties) {
        
        log.info("Attempting to auto-create ActiveMQConnectionFactory...");
        log.info("Broker URL: {}", brokerUrl);
        
        try {
            // ═══════════════════════════════════════════════════════════════
            // Check if artemis-jakarta-client is available
            // ═══════════════════════════════════════════════════════════════
            Class<?> amqFactoryClass;
            try {
                amqFactoryClass = Class.forName(
                    "org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory"
                );
                log.debug("ActiveMQ Artemis client found in classpath");
            } catch (ClassNotFoundException e) {
                log.error("═══════════════════════════════════════════════════════════");
                log.error("ActiveMQ Artemis client NOT found in classpath");
                log.error("═══════════════════════════════════════════════════════════");
                log.error("Cannot auto-create ConnectionFactory without artemis-jakarta-client");
                log.error("");
                log.error("Add this dependency to your pom.xml:");
                log.error("");
                log.error("  <dependency>");
                log.error("    <groupId>org.apache.activemq</groupId>");
                log.error("    <artifactId>artemis-jakarta-client</artifactId>");
                log.error("    <version>2.38.0</version>");
                log.error("  </dependency>");
                log.error("");
                log.error("OR provide your own ConnectionFactory bean");
                log.error("OR disable JMS: sakti.tx.jms.enabled=false");
                log.error("═══════════════════════════════════════════════════════════");
                return null;
            }
            
            // ═══════════════════════════════════════════════════════════════
            // Create ActiveMQConnectionFactory instance
            // ═══════════════════════════════════════════════════════════════
            Object connectionFactory = amqFactoryClass
                .getDeclaredConstructor(String.class)
                .newInstance(brokerUrl);
            
            log.info("Created ActiveMQConnectionFactory instance");
            
            // ═══════════════════════════════════════════════════════════════
            // Set credentials if provided
            // ═══════════════════════════════════════════════════════════════
            String user = properties.getJms().getUser();
            String password = properties.getJms().getPassword();
            
            if (user != null && !user.trim().isEmpty()) {
                amqFactoryClass.getMethod("setUser", String.class)
                    .invoke(connectionFactory, user);
                amqFactoryClass.getMethod("setPassword", String.class)
                    .invoke(connectionFactory, password);
                log.info("Set JMS credentials for user: {}", user);
            }
            
            // ═══════════════════════════════════════════════════════════════
            // Optional: Configure connection pool and other settings
            // ═══════════════════════════════════════════════════════════════
            try {
                // Set reasonable defaults for production use
                amqFactoryClass.getMethod("setConnectionTTL", long.class)
                    .invoke(connectionFactory, 60000L); // 60 seconds
                
                amqFactoryClass.getMethod("setReconnectAttempts", int.class)
                    .invoke(connectionFactory, -1); // Infinite reconnect
                
                amqFactoryClass.getMethod("setRetryInterval", long.class)
                    .invoke(connectionFactory, 1000L); // 1 second
                
                log.debug("Configured ActiveMQ connection settings");
            } catch (Exception e) {
                // Non-critical - continue even if settings fail
                log.debug("Could not configure advanced settings: {}", e.getMessage());
            }
            
            log.info("ActiveMQConnectionFactory created successfully");
            return connectionFactory;
            
        } catch (Exception e) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Failed to create ActiveMQConnectionFactory");
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Error: {}", e.getMessage());
            log.error("Broker URL: {}", brokerUrl);
            log.error("");
            log.error("Possible causes:");
            log.error("  • ActiveMQ/Artemis broker not running");
            log.error("  • Incorrect broker URL format");
            log.error("  • Network connectivity issues");
            log.error("  • Authentication required but not provided");
            log.error("");
            log.error("Solutions:");
            log.error("  • Verify broker is running: telnet host port");
            log.error("  • Check URL format: tcp://host:port");
            log.error("  • Provide credentials if needed:");
            log.error("    sakti.tx.jms.user=admin");
            log.error("    sakti.tx.jms.password=admin");
            log.error("═══════════════════════════════════════════════════════════");
            return null;
        }
    }

    /**
     * Test JMS connection (optional but recommended)
     * 
     * @return true if connection successful, false otherwise
     */
    private boolean testJmsConnection(Object connectionFactory, String source) {
        if (connectionFactory == null) {
            return false;
        }
        
        Object connection = null;
        try {
            log.debug("Testing JMS connection: {}", source);
            
            // Use reflection: connection = connectionFactory.createConnection()
            Class<?> factoryClass = connectionFactory.getClass();
            Object connectionObj = factoryClass.getMethod("createConnection").invoke(connectionFactory);
            connection = connectionObj;
            
            // Use reflection: connection.start()
            connectionObj.getClass().getMethod("start").invoke(connectionObj);
            
            log.info("JMS connection test successful");
            return true;
            
        } catch (Exception e) {
            log.error("JMS connection test failed: {}", e.getMessage());
            log.error("   Verify broker is running and accessible");
            return false;
            
        } finally {
            if (connection != null) {
                try {
                    // Use reflection: connection.close()
                    connection.getClass().getMethod("close").invoke(connection);
                } catch (Exception e) {
                    log.debug("Error closing test connection: {}", e.getMessage());
                }
            }
        }
    }
}