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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SaktiTxProperties.class)
public class SaktiTxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SaktiTxAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    @ConditionalOnProperty(prefix = "sakti.tx.dragonfly", name = "enabled", havingValue = "true")
    public RedissonClient redissonClient(SaktiTxProperties properties) {
        log.info("Initializing Redisson client for Dragonfly: {}",
                properties.getDragonfly().getUrl());

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

        return Redisson.create(config);
    }

    @Bean
    @ConditionalOnMissingBean(LockManager.class)
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
    public LockManager lockManager(@Autowired(required = false) RedissonClient redissonClient) {
        if (redissonClient == null) {
            log.warn("RedissonClient not available - LockManager will not be created");
            return null;
        }
        return new RedissonLockManager(redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyManager.class)
    @ConditionalOnProperty(prefix = "sakti.tx.idempotency", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
    public IdempotencyManager idempotencyManager(
            @Autowired(required = false) RedissonClient redissonClient,
            SaktiTxProperties properties) {
        if (redissonClient == null) {
            log.warn("RedissonClient not available - IdempotencyManager will not be created");
            return null;
        }
        return new IdempotencyManager(redissonClient, properties.getIdempotency().getPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    @ConditionalOnProperty(prefix = "sakti.tx.cache", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
    public CacheManager cacheManager(
            @Autowired(required = false) RedissonClient redissonClient,
            ObjectMapper objectMapper,
            SaktiTxProperties properties) {
        if (redissonClient == null) {
            log.warn("RedissonClient not available - CacheManager will not be created");
            return null;
        }
        return new CacheManager(redissonClient, objectMapper, properties.getCache().getPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(DragonflyHealthIndicator.class)
    @ConditionalOnProperty(prefix = "sakti.tx.health", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public DragonflyHealthIndicator dragonflyHealthIndicator(
            @Autowired(required = false) RedissonClient redissonClient,
            SaktiTxProperties properties) {
        if (redissonClient == null) {
            log.warn("RedissonClient not available - Health indicator will show disabled");
        }
        return new DragonflyHealthIndicator(redissonClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean(JmsEventPublisher.class)
    @ConditionalOnProperty(prefix = "sakti.tx.jms", name = "enabled", havingValue = "true")
    public JmsEventPublisher jmsEventPublisher(
            ApplicationContext applicationContext,
            SaktiTxProperties properties) {

        ConnectionFactory connectionFactory = null;

        String existingBeanName = properties.getJms().getExistingFactoryBeanName();
        if (existingBeanName != null && !existingBeanName.trim().isEmpty()) {
            try {
                connectionFactory = applicationContext.getBean(existingBeanName, ConnectionFactory.class);
                log.info("Reusing existing ConnectionFactory: {}", existingBeanName);
            } catch (Exception e) {
                log.warn("Failed to find existing ConnectionFactory: {}", existingBeanName);
            }
        }

        if (connectionFactory == null) {
            try {
                connectionFactory = applicationContext.getBean(ConnectionFactory.class);
                log.info("Using default ConnectionFactory from context");
            } catch (Exception e) {
                log.warn("No ConnectionFactory available - JMS publishing will be disabled");
                return null;
            }
        }
        return new JmsEventPublisher(connectionFactory, properties);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiLockAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    public SaktiLockAspect saktiLockAspect(
            @Autowired(required = false) LockManager lockManager,
            SaktiTxProperties properties,
            DragonflyHealthIndicator healthIndicator) {
        if (lockManager == null) {
            log.warn("LockManager not available - @SaktiLock will be no-op");
        }
        return new SaktiLockAspect(lockManager, properties, healthIndicator);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiCacheAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.cache", name = "enabled", havingValue = "true")
    public SaktiCacheAspect saktiCacheAspect(
            @Autowired(required = false) CacheManager cacheManager,
            SaktiTxProperties properties,
            DragonflyHealthIndicator healthIndicator) {
        if (cacheManager == null) {
            log.warn("CacheManager not available - @SaktiCache will be no-op");
        }
        return new SaktiCacheAspect(cacheManager, properties, healthIndicator);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiIdempotentAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.idempotency", name = "enabled", havingValue = "true")
    public SaktiIdempotentAspect saktiIdempotentAspect(
            @Autowired(required = false) IdempotencyManager idempotencyManager,
            SaktiTxProperties properties,
            DragonflyHealthIndicator healthIndicator) {
        if (idempotencyManager == null) {
            log.warn("IdempotencyManager not available - @SaktiIdempotent will be no-op");
        }
        return new SaktiIdempotentAspect(idempotencyManager, properties, healthIndicator);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiDistributedTxAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public SaktiDistributedTxAspect saktiDistributedTxAspect(SaktiTxProperties properties) {
        return new SaktiDistributedTxAspect(properties);
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLockService.class)
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    public DistributedLockService distributedLockService(
            @Autowired(required = false) LockManager lockManager,
            @Autowired(required = false) IdempotencyManager idempotencyManager,
            DragonflyHealthIndicator healthIndicator,
            SaktiTxProperties properties) {
        if (lockManager == null || idempotencyManager == null) {
            log.warn("LockManager or IdempotencyManager not available - DistributedLockService will be limited");
        }
        return new DistributedLockServiceImpl(lockManager, idempotencyManager, healthIndicator, properties);
}
}