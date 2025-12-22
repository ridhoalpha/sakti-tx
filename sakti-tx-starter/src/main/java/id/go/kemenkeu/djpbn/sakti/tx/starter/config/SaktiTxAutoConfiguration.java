package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import id.go.kemenkeu.djpbn.sakti.tx.core.cache.CacheManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.compensate.CompensatingTransactionExecutor;
import id.go.kemenkeu.djpbn.sakti.tx.core.compensate.CompensationCircuitBreaker;
import id.go.kemenkeu.djpbn.sakti.tx.core.idempotency.IdempotencyManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.inspection.CascadeDetector;
import id.go.kemenkeu.djpbn.sakti.tx.core.inspection.TriggerDetector;
import id.go.kemenkeu.djpbn.sakti.tx.core.listener.EntityOperationListener;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.LockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.RedissonLockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLogManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.mapper.EntityManagerDatasourceMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.metrics.TransactionMetrics;
import id.go.kemenkeu.djpbn.sakti.tx.core.validation.DefaultPreCommitValidator;
import id.go.kemenkeu.djpbn.sakti.tx.core.validation.PreCommitValidator;
import id.go.kemenkeu.djpbn.sakti.tx.starter.admin.TransactionAdminController;
import id.go.kemenkeu.djpbn.sakti.tx.starter.aspect.*;
import id.go.kemenkeu.djpbn.sakti.tx.starter.event.JmsEventPublisher;
import id.go.kemenkeu.djpbn.sakti.tx.starter.health.DragonflyHealthIndicator;
import id.go.kemenkeu.djpbn.sakti.tx.starter.interceptor.ServiceOperationInterceptor;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.DistributedLockService;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.impl.DistributedLockServiceImpl;
import id.go.kemenkeu.djpbn.sakti.tx.starter.worker.TransactionRecoveryWorker;
import jakarta.jms.ConnectionFactory;
import jakarta.persistence.EntityManager;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
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
        log.info("SAKTI Transaction Coordinator v2.0.0 - Initializing...");
        log.info("═══════════════════════════════════════════════════════════");
        
        boolean hasErrors = false;

        if (properties.getMultiDb().isEnabled()) {
            log.info("✓ Distributed Transaction: ENABLED");
            log.info("  → Auto-tracking with JPA event listeners");
            log.info("  → Pre-commit validation");
            log.info("  → Smart compensation with circuit breaker");
        }
        
        if (!properties.getDragonfly().isEnabled()) {
            if (properties.getLock().isEnabled()) {
                log.error("❌ CONFIG ERROR: lock requires dragonfly.enabled=true");
                hasErrors = true;
            }
            if (properties.getCache().isEnabled()) {
                log.error("❌ CONFIG ERROR: cache requires dragonfly.enabled=true");
                hasErrors = true;
            }
            if (properties.getIdempotency().isEnabled()) {
                log.error("❌ CONFIG ERROR: idempotency requires dragonfly.enabled=true");
                hasErrors = true;
            }
            if (properties.getMultiDb().isEnabled()) {
                log.error("❌ CONFIG ERROR: multi-db requires dragonfly.enabled=true");
                hasErrors = true;
            }
        }
        
        if (hasErrors) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("❌ APPLICATION STARTUP WILL FAIL - FIX CONFIGURATION");
            log.error("═══════════════════════════════════════════════════════════");
        }
        
        logFeatureStatus("Dragonfly/Redis", properties.getDragonfly().isEnabled());
        logFeatureStatus("Distributed Transaction", properties.getMultiDb().isEnabled());
        logFeatureStatus("Distributed Lock", properties.getLock().isEnabled());
        logFeatureStatus("Cache Manager", properties.getCache().isEnabled());
        logFeatureStatus("Idempotency", properties.getIdempotency().isEnabled());
        logFeatureStatus("Circuit Breaker", properties.getCircuitBreaker().isEnabled());
        logFeatureStatus("Pre-Commit Validation", properties.getValidation().isEnabled());
        logFeatureStatus("Observability Metrics", properties.getObservability().isMetricsEnabled());
        log.info("═══════════════════════════════════════════════════════════");
    }
    
    private void logFeatureStatus(String feature, boolean enabled) {
        log.info("{}: {}", feature, enabled ? "✓ ENABLED" : "○ DISABLED");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CORE BEANS
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @Order(0)
    public ObjectMapper saktiTxObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.findAndRegisterModules();
        
        EntityOperationListener.setObjectMapper(mapper);
        
        log.info("✓ ObjectMapper with JSR-310 support created");
        return mapper;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    @ConditionalOnProperty(prefix = "sakti.tx.dragonfly", name = "enabled", havingValue = "true")
    public RedissonClient redissonClient(SaktiTxProperties properties) {
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
        
        log.info("✓ RedissonClient created");
        return client;
    }

    @Bean
    @ConditionalOnMissingBean(LockManager.class)
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    @ConditionalOnBean(RedissonClient.class)
    public LockManager lockManager(RedissonClient redissonClient) {
        log.info("✓ LockManager created");
        return new RedissonLockManager(redissonClient);
    }

    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.idempotency", name = "enabled", havingValue = "true")
    @ConditionalOnBean(RedissonClient.class)
    public IdempotencyManager idempotencyManager(
            RedissonClient redissonClient,
            SaktiTxProperties properties) {
        log.info("✓ IdempotencyManager created");
        return new IdempotencyManager(redissonClient, properties.getIdempotency().getPrefix());
    }

    @Bean("saktiCacheManager")
    @ConditionalOnMissingBean(name = "saktiCacheManager")
    @ConditionalOnProperty(prefix = "sakti.tx.cache", name = "enabled", havingValue = "true")
    @ConditionalOnBean(RedissonClient.class)
    public CacheManager saktiCacheManager(
            RedissonClient redissonClient,
            @Qualifier("saktiTxObjectMapper") ObjectMapper saktiTxObjectMapper,
            SaktiTxProperties properties) {
        log.info("✓ CacheManager created");
        return new CacheManager(redissonClient, saktiTxObjectMapper, properties.getCache().getPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(DragonflyHealthIndicator.class)
    @ConditionalOnProperty(prefix = "sakti.tx.health", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnBean(RedissonClient.class)
    public DragonflyHealthIndicator dragonflyHealthIndicator(
            RedissonClient redissonClient,
            SaktiTxProperties properties) {
        log.info("✓ DragonflyHealthIndicator created");
        return new DragonflyHealthIndicator(redissonClient, properties);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ASPECT BEANS
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnMissingBean(SaktiLockAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    @ConditionalOnBean(LockManager.class)
    public SaktiLockAspect saktiLockAspect(
            LockManager lockManager,
            SaktiTxProperties properties,
            @Autowired(required = false) RedissonClient redissonClient) {
        log.info("✓ SaktiLockAspect created");
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
        log.info("✓ SaktiCacheAspect created");
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
        log.info("✓ SaktiIdempotentAspect created");
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
        } catch (Exception e) {
            log.debug("IdempotencyManager not available");
        }
        
        try {
            healthIndicator = applicationContext.getBean(DragonflyHealthIndicator.class);
        } catch (Exception e) {
            log.debug("DragonflyHealthIndicator not available");
        }
        
        log.info("✓ DistributedLockService created");
        return new DistributedLockServiceImpl(lockManager, idempotencyManager, healthIndicator, properties);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ NEW: OBSERVABILITY & METRICS BEANS
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnMissingBean(TransactionMetrics.class)
    @ConditionalOnProperty(prefix = "sakti.tx.observability", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
    public TransactionMetrics transactionMetrics() {
        log.info("✓ TransactionMetrics created");
        return new TransactionMetrics();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ NEW: CIRCUIT BREAKER BEAN
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnMissingBean(CompensationCircuitBreaker.class)
    @ConditionalOnProperty(prefix = "sakti.tx.circuit-breaker", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CompensationCircuitBreaker compensationCircuitBreaker(SaktiTxProperties properties) {
        log.info("✓ CompensationCircuitBreaker created");
        log.info("  → Failure Threshold: {}", properties.getCircuitBreaker().getCompensationFailureThreshold());
        log.info("  → Recovery Window: {}ms", properties.getCircuitBreaker().getCompensationRecoveryWindowMs());
        
        return new CompensationCircuitBreaker(
            properties.getCircuitBreaker().getCompensationFailureThreshold(),
            Duration.ofMillis(properties.getCircuitBreaker().getCompensationRecoveryWindowMs())
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DISTRIBUTED TRANSACTION BEANS
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    @ConditionalOnBean({RedissonClient.class, ObjectMapper.class})
    public TransactionLogManager transactionLogManager(
            RedissonClient redissonClient,
            @Qualifier("saktiTxObjectMapper") ObjectMapper saktiTxObjectMapper,
            SaktiTxProperties properties) {
        
        boolean waitForSync = properties.getDragonfly().isWaitForSync();
        int waitTimeoutMs = properties.getDragonfly().getWaitForSyncTimeoutMs();
        
        log.info("✓ TransactionLogManager created (wait-for-sync: {})", waitForSync);
        
        return new TransactionLogManager(redissonClient, saktiTxObjectMapper, waitForSync, waitTimeoutMs);
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.dragonfly", name = "verify-durability", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(RedissonClient.class)
    public RedisDurabilityVerifier redisDurabilityVerifier(
            RedissonClient redissonClient,
            SaktiTxProperties properties) {
        log.info("✓ RedisDurabilityVerifier created");
        return new RedisDurabilityVerifier(redissonClient, properties);
    }
    
    @Bean
    @ConditionalOnMissingBean(EntityManagerDatasourceMapper.class)
    public EntityManagerDatasourceMapper entityManagerDatasourceMapper(ApplicationContext ctx) {
        log.info("✓ EntityManagerDatasourceMapper created");
        
        EntityManagerDatasourceMapper mapper = new EntityManagerDatasourceMapper();
        
        Map<String, EntityManager> emBeans = ctx.getBeansOfType(EntityManager.class);
        for (Map.Entry<String, EntityManager> entry : emBeans.entrySet()) {
            String datasourceName = extractDatasourceName(entry.getKey());
            mapper.registerEntityManager(datasourceName, entry.getValue());
            log.info("  → Registered: {} -> {}", datasourceName, entry.getKey());
        }
        
        Map<String, LocalContainerEntityManagerFactoryBean> emfBeans = 
            ctx.getBeansOfType(LocalContainerEntityManagerFactoryBean.class);
        
        for (Map.Entry<String, LocalContainerEntityManagerFactoryBean> entry : emfBeans.entrySet()) {
            try {
                String beanName = entry.getKey();
                EntityManager em = entry.getValue().getObject().createEntityManager();
                String datasourceName = extractDatasourceName(beanName);
                
                if (mapper.getAllEntityManagers().get(datasourceName) == null) {
                    mapper.registerEntityManager(datasourceName, em);
                    log.info("  → Registered from factory: {}", datasourceName);
                }
            } catch (Exception e) {
                log.warn("Could not create EM from factory: {}", entry.getKey());
            }
        }
        
        return mapper;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ NEW: RISK DETECTION BEANS
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnMissingBean(TriggerDetector.class)
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public TriggerDetector triggerDetector(EntityManagerDatasourceMapper mapper) {
        log.info("✓ TriggerDetector created");
        Map<String, EntityManager> entityManagers = mapper.getAllEntityManagers();
        TriggerDetector detector = new TriggerDetector(entityManagers);
        
        // Set static field in EntityOperationListener
        EntityOperationListener.setTriggerDetector(detector);
        
        return detector;
    }

    @Bean
    @ConditionalOnMissingBean(CascadeDetector.class)
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public CascadeDetector cascadeDetector(EntityManagerDatasourceMapper mapper) {
        log.info("✓ CascadeDetector created");
        Map<String, EntityManager> entityManagers = mapper.getAllEntityManagers();
        CascadeDetector detector = new CascadeDetector(entityManagers);
        
        // Set static field in EntityOperationListener
        EntityOperationListener.setCascadeDetector(detector);
        
        return detector;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ NEW: PRE-COMMIT VALIDATION BEAN
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnMissingBean(PreCommitValidator.class)
    @ConditionalOnProperty(prefix = "sakti.tx.validation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PreCommitValidator preCommitValidator(
            EntityManagerDatasourceMapper mapper,
            SaktiTxProperties properties) {
        log.info("✓ PreCommitValidator created");
        log.info("  → Long Running Threshold: {}ms", properties.getValidation().getLongRunningThresholdMs());
        log.info("  → Strict Version Check: {}", properties.getValidation().isStrictVersionCheck());
        
        Map<String, EntityManager> entityManagers = mapper.getAllEntityManagers();
        Duration threshold = Duration.ofMillis(properties.getValidation().getLongRunningThresholdMs());
        
        return new DefaultPreCommitValidator(entityManagers, threshold);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ NEW: HIBERNATE STATEMENT INSPECTOR REGISTRATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public SaktiHibernateStatementInspectorRegistrar statementInspectorRegistrar() {
        log.info("✓ Hibernate StatementInspector Registrar created");
        return new SaktiHibernateStatementInspectorRegistrar();
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ MODIFIED: COMPENSATING TRANSACTION EXECUTOR (with circuit breaker)
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public CompensatingTransactionExecutor compensatingTransactionExecutor(
            EntityManagerDatasourceMapper mapper,
            @Qualifier("saktiTxObjectMapper") ObjectMapper saktiTxObjectMapper,
            CompensationCircuitBreaker circuitBreaker,
            SaktiTxProperties properties) {
        log.info("✓ CompensatingTransactionExecutor v2.0 created (with circuit breaker)");
        
        Map<String, EntityManager> entityManagers = mapper.getAllEntityManagers();
        boolean strictVersionCheck = properties.getValidation().isStrictVersionCheck();
        
        return new CompensatingTransactionExecutor(
            entityManagers, 
            saktiTxObjectMapper, 
            circuitBreaker,
            strictVersionCheck
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean({TransactionLogManager.class, CompensatingTransactionExecutor.class})
    public TransactionRecoveryWorker transactionRecoveryWorker(
            TransactionLogManager logManager,
            CompensatingTransactionExecutor compensator,
            SaktiTxProperties properties) {
        log.info("✓ TransactionRecoveryWorker created");
        return new TransactionRecoveryWorker(logManager, compensator, properties);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ NEW: ADMIN API BEAN (UNCOMMENTED)
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db.admin-api", name = "enabled", havingValue = "true")
    @ConditionalOnBean({TransactionLogManager.class, CompensatingTransactionExecutor.class, TransactionRecoveryWorker.class})
    public TransactionAdminController transactionAdminController(
            TransactionLogManager logManager,
            CompensatingTransactionExecutor compensator,
            TransactionRecoveryWorker recoveryWorker) {
        log.info("✓ TransactionAdminController created");
        log.info("  → Admin API available at: /admin/transactions/*");
        log.info("  ⚠ WARNING: Add Spring Security for production!");
        return new TransactionAdminController(logManager, compensator, recoveryWorker);
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public ServiceOperationInterceptor serviceOperationInterceptor(
            @Qualifier("saktiTxObjectMapper") ObjectMapper saktiTxObjectMapper) {
        log.info("✓ ServiceOperationInterceptor created");
        return new ServiceOperationInterceptor(saktiTxObjectMapper);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ MODIFIED: DISTRIBUTED TX ASPECT (with validation & metrics)
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Bean
    @ConditionalOnMissingBean(SaktiDistributedTxAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    @ConditionalOnBean({TransactionLogManager.class, CompensatingTransactionExecutor.class})
    public SaktiDistributedTxAspect saktiDistributedTxAspect(
            SaktiTxProperties properties,
            TransactionLogManager logManager,
            CompensatingTransactionExecutor compensator,
            EntityManagerDatasourceMapper emMapper,
            PreCommitValidator preCommitValidator,
            TransactionMetrics metrics,
            @Autowired(required = false) LockManager lockManager,
            @Autowired(required = false) Map<String, PlatformTransactionManager> transactionManagers) {
        log.info("✓ SaktiDistributedTxAspect v2.0 created (with validation & metrics)");
        return new SaktiDistributedTxAspect(
            properties, 
            logManager, 
            compensator, 
            emMapper, 
            lockManager, 
            preCommitValidator,
            metrics,
            transactionManagers
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JMS EVENT PUBLISHER (OPTIONAL)
    // ═══════════════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnMissingBean(JmsEventPublisher.class)
    @ConditionalOnProperty(prefix = "sakti.tx.jms", name = "enabled", havingValue = "true")
    public JmsEventPublisher jmsEventPublisher(
            ApplicationContext applicationContext,
            SaktiTxProperties properties) {
        
        Object connectionFactory = null;
        
        try {
            Class<?> connectionFactoryClass = Class.forName("jakarta.jms.ConnectionFactory");
            
            String existingBeanName = properties.getJms().getExistingFactoryBeanName();
            if (existingBeanName != null && !existingBeanName.trim().isEmpty()) {
                try {
                    connectionFactory = applicationContext.getBean(existingBeanName, connectionFactoryClass);
                    log.info("✓ Using existing ConnectionFactory: {}", existingBeanName);
                } catch (Exception e) {
                    log.warn("Cannot find ConnectionFactory bean '{}'", existingBeanName);
                }
            }
            
            if (connectionFactory == null) {
                try {
                    connectionFactory = applicationContext.getBean(connectionFactoryClass);
                    log.info("✓ Using default ConnectionFactory bean");
                } catch (Exception e) {
                    log.debug("No default ConnectionFactory bean found");
                }
            }
            
            if (connectionFactory == null) {
                String brokerUrl = properties.getJms().getBrokerUrl();
                if (brokerUrl != null && !brokerUrl.trim().isEmpty()) {
                    connectionFactory = createActiveMQConnectionFactory(brokerUrl, properties);
                }
            }
            
            if (connectionFactory != null) {
                log.info("✓ JmsEventPublisher created");
                return new JmsEventPublisher(connectionFactory, properties);
            }
            
        } catch (Exception e) {
            log.error("Failed to create JmsEventPublisher: {}", e.getMessage());
        }
        
        return null;
    }

    private Object createActiveMQConnectionFactory(String brokerUrl, SaktiTxProperties properties) {
        try {
            Class<?> amqFactoryClass = Class.forName(
                "org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory"
            );
            
            Object connectionFactory = amqFactoryClass
                .getDeclaredConstructor(String.class)
                .newInstance(brokerUrl);
            
            String user = properties.getJms().getUser();
            String password = properties.getJms().getPassword();
            
            if (user != null && !user.trim().isEmpty()) {
                amqFactoryClass.getMethod("setUser", String.class)
                    .invoke(connectionFactory, user);
                amqFactoryClass.getMethod("setPassword", String.class)
                    .invoke(connectionFactory, password);
            }
            
            log.info("✓ ActiveMQConnectionFactory created");
            return connectionFactory;
            
        } catch (Exception e) {
            log.error("Failed to create ActiveMQConnectionFactory: {}", e.getMessage());
            return null;
        }
    }
}