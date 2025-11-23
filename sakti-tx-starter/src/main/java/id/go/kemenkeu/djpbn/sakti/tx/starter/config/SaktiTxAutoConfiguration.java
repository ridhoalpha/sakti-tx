package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.cache.CacheManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.idempotency.IdempotencyManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.LockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.RedissonLockManager;
import id.go.kemenkeu.djpbn.sakti.tx.starter.aspect.*;
import id.go.kemenkeu.djpbn.sakti.tx.starter.event.JmsEventPublisher;
import id.go.kemenkeu.djpbn.sakti.tx.starter.health.DragonflyHealthIndicator;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.DistributedLockService;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.impl.DistributedLockServiceImpl;
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

import jakarta.annotation.PostConstruct;

@Configuration
@EnableConfigurationProperties(SaktiTxProperties.class)
public class SaktiTxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SaktiTxAutoConfiguration.class);

    private final SaktiTxProperties properties;

    public SaktiTxAutoConfiguration(SaktiTxProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validateConfiguration() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("SAKTI Transaction Coordinator v1.0.2 - Initializing...");
        log.info("═══════════════════════════════════════════════════════════");
        
        boolean hasErrors = false;
        
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
        logFeatureStatus("Distributed Lock", properties.getLock().isEnabled(), 
            getDependencyStatus(properties.getLock().isEnabled(), properties.getDragonfly().isEnabled()));
        logFeatureStatus("Cache Manager", properties.getCache().isEnabled(), 
            getDependencyStatus(properties.getCache().isEnabled(), properties.getDragonfly().isEnabled()));
        logFeatureStatus("Idempotency", properties.getIdempotency().isEnabled(), 
            getDependencyStatus(properties.getIdempotency().isEnabled(), properties.getDragonfly().isEnabled()));
        logFeatureStatus("Circuit Breaker", properties.getCircuitBreaker().isEnabled(), 
            "threshold=" + properties.getCircuitBreaker().getFailureThreshold());
        logFeatureStatus("JMS Events", properties.getJms().isEnabled(), 
            properties.getJms().isEnabled() ? "enabled" : "disabled");
        logFeatureStatus("Multi-DB Transaction", properties.getMultiDb().isEnabled(), 
            properties.getMultiDb().getRollbackStrategy().toString());
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
            
            log.info("RedissonClient created and tested successfully");
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
        log.info("Creating LockManager (RedissonLockManager)");
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
    @ConditionalOnProperty(prefix = "sakti.tx.idempotency", name = "enabled", havingValue = "true")
    @ConditionalOnBean(IdempotencyManager.class)
    public SaktiIdempotentAspect saktiIdempotentAspect(
            IdempotencyManager idempotencyManager,
            SaktiTxProperties properties,
            @Autowired(required = false) RedissonClient redissonClient) {
        log.info("Creating SaktiIdempotentAspect");
        return new SaktiIdempotentAspect(idempotencyManager, properties, redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiDistributedTxAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public SaktiDistributedTxAspect saktiDistributedTxAspect(SaktiTxProperties properties) {
        log.info("Creating SaktiDistributedTxAspect");
        return new SaktiDistributedTxAspect(properties);
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLockService.class)
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
    @ConditionalOnMissingBean(JmsEventPublisher.class)
    @ConditionalOnProperty(prefix = "sakti.tx.jms", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "jakarta.jms.ConnectionFactory")
    public JmsEventPublisher jmsEventPublisher(
            ApplicationContext applicationContext,
            SaktiTxProperties properties) {
        
        log.info("Initializing JMS Event Publisher...");
        
        Object connectionFactory = null;
        String factorySource = null;
        
        try {
            Class<?> connectionFactoryClass;
            try {
                connectionFactoryClass = Class.forName("jakarta.jms.ConnectionFactory");
            } catch (ClassNotFoundException e) {
                log.error("jakarta.jms.ConnectionFactory not found - this should not happen due to @ConditionalOnClass");
                return null;
            }

            String existingBeanName = properties.getJms().getExistingFactoryBeanName();
            if (existingBeanName != null && !existingBeanName.trim().isEmpty()) {
                try {
                    connectionFactory = applicationContext.getBean(existingBeanName, connectionFactoryClass);
                    factorySource = "existing bean: " + existingBeanName;
                    log.info("Found existing ConnectionFactory: {}", existingBeanName);
                } catch (Exception e) {
                    log.warn("Cannot find ConnectionFactory bean: {} - {}", existingBeanName, e.getMessage());
                }
            }
            
            // ═════════════════════════════════════════════════════════════════
            // STEP 3: Try to get default ConnectionFactory bean
            // ═════════════════════════════════════════════════════════════════
            if (connectionFactory == null) {
                try {
                    connectionFactory = applicationContext.getBean(connectionFactoryClass);
                    factorySource = "default bean";
                    log.info("Found default ConnectionFactory");
                } catch (Exception e) {
                    log.debug("No default ConnectionFactory found");
                }
            }
            
            // ═════════════════════════════════════════════════════════════════
            // STEP 4: Try to create new ActiveMQ ConnectionFactory
            // ═════════════════════════════════════════════════════════════════
            if (connectionFactory == null) {
                String brokerUrl = properties.getJms().getBrokerUrl();
                
                if (brokerUrl == null || brokerUrl.trim().isEmpty()) {
                    log.error("JMS Configuration Error:");
                    log.error("   → sakti.tx.jms.enabled=true but no ConnectionFactory available");
                    log.error("   → Solutions:");
                    log.error("      1. Set sakti.tx.jms.existing-factory-bean-name=yourBeanName");
                    log.error("      2. Set sakti.tx.jms.broker-url=tcp://host:port");
                    log.error("      3. Create your own ConnectionFactory bean");
                    log.error("      4. Disable JMS: sakti.tx.jms.enabled=false");
                    return null;
                }
                
                // Try to create ActiveMQ ConnectionFactory
                try {
                    log.debug("Attempting to create ActiveMQConnectionFactory for: {}", brokerUrl);
                    
                    // Check if artemis-jakarta-client is in classpath
                    Class<?> amqFactoryClass;
                    try {
                        amqFactoryClass = Class.forName("org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory");
                    } catch (ClassNotFoundException e) {
                        log.error("ActiveMQ Artemis client not found in classpath");
                        log.error("   → Add dependency:");
                        log.error("      <dependency>");
                        log.error("        <groupId>org.apache.activemq</groupId>");
                        log.error("        <artifactId>artemis-jakarta-client</artifactId>");
                        log.error("        <version>2.38.0</version>");
                        log.error("      </dependency>");
                        log.error("   → Or disable JMS: sakti.tx.jms.enabled=false");
                        return null;
                    }
                    
                    // Create factory instance
                    connectionFactory = amqFactoryClass
                        .getDeclaredConstructor(String.class)
                        .newInstance(brokerUrl);
                    
                    // Set credentials if provided
                    String user = properties.getJms().getUser();
                    String password = properties.getJms().getPassword();
                    
                    if (user != null && !user.trim().isEmpty()) {
                        amqFactoryClass.getMethod("setUser", String.class)
                            .invoke(connectionFactory, user);
                        amqFactoryClass.getMethod("setPassword", String.class)
                            .invoke(connectionFactory, password);
                        log.debug("Set JMS credentials: user={}", user);
                    }
                    
                    factorySource = "new ActiveMQConnectionFactory: " + brokerUrl;
                    log.info("Created new ActiveMQConnectionFactory: {}", brokerUrl);
                    
                } catch (Exception e) {
                    log.error("Failed to create ActiveMQConnectionFactory: {}", e.getMessage());
                    log.error("   → Check broker URL: {}", brokerUrl);
                    log.error("   → Verify artemis-jakarta-client dependency");
                    return null;
                }
            }
            
            // ═════════════════════════════════════════════════════════════════
            // STEP 5: Test connection (optional but recommended)
            // ═════════════════════════════════════════════════════════════════
            if (properties.getJms().isTestOnStartup()) {
                if (!testJmsConnection(connectionFactory, factorySource)) {
                    log.warn("JMS connection test failed - bean will still be created");
                    log.warn("   → JMS operations may fail at runtime");
                    log.warn("   → Set sakti.tx.jms.test-on-startup=false to skip this test");
                }
            }
            
            // ═════════════════════════════════════════════════════════════════
            // STEP 6: Create JmsEventPublisher bean
            // ═════════════════════════════════════════════════════════════════
            log.info("Creating JmsEventPublisher (source: {})", factorySource);
            
            // Cast to jakarta.jms.ConnectionFactory (safe because we checked the class)
            return new JmsEventPublisher(connectionFactory, properties);
            
        } catch (Exception e) {
            log.error("Unexpected error during JMS initialization: {}", e.getMessage(), e);
            log.error("   → Disable JMS if not needed: sakti.tx.jms.enabled=false");
            return null;
        }
    }
    
    /**
     * Test JMS connection
     * Uses reflection to avoid direct JMS type references
     * Non-blocking, returns quickly
     * Closes resources properly
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
            
            log.info("JMS connection test successful: {}", source);
            return true;
            
        } catch (Exception e) {
            log.error("JMS connection test failed: {} - {}", source, e.getMessage());
            log.error("   → Verify ActiveMQ/Artemis is running and accessible");
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