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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
        log.info("🚀 Initializing SAKTI RedissonClient: {}", properties.getDragonfly().getUrl());

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
    public LockManager lockManager(RedissonClient redissonClient) {
        log.info("✅ Creating SAKTI LockManager");
        return new RedissonLockManager(redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyManager.class)
    @ConditionalOnProperty(prefix = "sakti.tx.idempotency", name = "enabled", havingValue = "true")
    public IdempotencyManager idempotencyManager(
            RedissonClient redissonClient,
            SaktiTxProperties properties) {
        log.info("✅ Creating SAKTI IdempotencyManager");
        return new IdempotencyManager(redissonClient, properties.getIdempotency().getPrefix());
    }

    @Bean("saktiCacheManager")
    @ConditionalOnMissingBean(name = "saktiCacheManager")
    @ConditionalOnProperty(prefix = "sakti.tx.cache", name = "enabled", havingValue = "true")
    public CacheManager saktiCacheManager(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            SaktiTxProperties properties) {
        log.info("✅ Creating SAKTI CacheManager");
        return new CacheManager(redissonClient, objectMapper, properties.getCache().getPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(DragonflyHealthIndicator.class)
    @ConditionalOnProperty(prefix = "sakti.tx.health", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public DragonflyHealthIndicator dragonflyHealthIndicator(
            RedissonClient redissonClient,
            SaktiTxProperties properties) {
        log.info("✅ Creating SAKTI Health Indicator");
        return new DragonflyHealthIndicator(redissonClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean(JmsEventPublisher.class)
    @ConditionalOnProperty(prefix = "sakti.tx.jms", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "jakarta.jms.ConnectionFactory")
    public JmsEventPublisher jmsEventPublisher(
            ApplicationContext applicationContext,
            SaktiTxProperties properties) {
        
        ConnectionFactory connectionFactory = null;
        
        String existingBeanName = properties.getJms().getExistingFactoryBeanName();
        if (existingBeanName != null && !existingBeanName.trim().isEmpty()) {
            try {
                connectionFactory = applicationContext.getBean(existingBeanName, ConnectionFactory.class);
                log.info("✅ Reusing existing ConnectionFactory: {}", existingBeanName);
            } catch (Exception e) {
                log.warn("⚠️ Bean not found: {}", existingBeanName);
            }
        }
        
        if (connectionFactory == null) {
            try {
                connectionFactory = applicationContext.getBean(ConnectionFactory.class);
                log.info("✅ Found default ConnectionFactory");
            } catch (Exception e) {
                log.debug("No default ConnectionFactory found");
            }
        }
        
        if (connectionFactory == null) {
            String brokerUrl = properties.getJms().getBrokerUrl();
            if (brokerUrl != null && !brokerUrl.trim().isEmpty()) {
                try {
                    Class<?> amqFactoryClass = Class.forName("org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory");
                    connectionFactory = (ConnectionFactory) amqFactoryClass.getDeclaredConstructor(String.class)
                        .newInstance(brokerUrl);
                    
                    if (properties.getJms().getUser() != null) {
                        amqFactoryClass.getMethod("setUser", String.class)
                            .invoke(connectionFactory, properties.getJms().getUser());
                        amqFactoryClass.getMethod("setPassword", String.class)
                            .invoke(connectionFactory, properties.getJms().getPassword());
                    }
                    
                    log.info("✅ Created new ActiveMQConnectionFactory: {}", brokerUrl);
                } catch (Exception e) {
                    log.error("❌ Failed to create ActiveMQConnectionFactory", e);
                    return null;
                }
            } else {
                log.warn("❌ JMS enabled but no ConnectionFactory available");
                return null;
            }
        }
        
        return new JmsEventPublisher(connectionFactory, properties);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiLockAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    @ConditionalOnBean(LockManager.class)
    public SaktiLockAspect saktiLockAspect(
            LockManager lockManager,
            SaktiTxProperties properties,
            DragonflyHealthIndicator healthIndicator) {
        log.info("✅ Creating SAKTI Lock Aspect");
        return new SaktiLockAspect(lockManager, properties, healthIndicator);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiCacheAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.cache", name = "enabled", havingValue = "true")
    @ConditionalOnBean(name = "saktiCacheManager")
    public SaktiCacheAspect saktiCacheAspect(
            CacheManager saktiCacheManager,
            SaktiTxProperties properties,
            DragonflyHealthIndicator healthIndicator) {
        log.info("✅ Creating SAKTI Cache Aspect");
        return new SaktiCacheAspect(saktiCacheManager, properties, healthIndicator);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiIdempotentAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.idempotency", name = "enabled", havingValue = "true")
    @ConditionalOnBean(IdempotencyManager.class)
    public SaktiIdempotentAspect saktiIdempotentAspect(
            IdempotencyManager idempotencyManager,
            SaktiTxProperties properties,
            DragonflyHealthIndicator healthIndicator) {
        log.info("✅ Creating SAKTI Idempotent Aspect");
        return new SaktiIdempotentAspect(idempotencyManager, properties, healthIndicator);
    }

    @Bean
    @ConditionalOnMissingBean(SaktiDistributedTxAspect.class)
    @ConditionalOnProperty(prefix = "sakti.tx.multi-db", name = "enabled", havingValue = "true")
    public SaktiDistributedTxAspect saktiDistributedTxAspect(SaktiTxProperties properties) {
        log.info("✅ Creating SAKTI Distributed TX Aspect");
        return new SaktiDistributedTxAspect(properties);
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLockService.class)
    @ConditionalOnProperty(prefix = "sakti.tx.lock", name = "enabled", havingValue = "true")
    @ConditionalOnBean(LockManager.class)
    public DistributedLockService distributedLockService(
            LockManager lockManager,
            IdempotencyManager idempotencyManager,  // ✅ OPTIONAL!
            DragonflyHealthIndicator healthIndicator,
            SaktiTxProperties properties) {
        
        if (idempotencyManager == null) {
            log.warn("⚠️ DistributedLockService created without IdempotencyManager - executeWithLockAndIdempotency() will use lock-only mode");
        } else {
            log.info("✅ Creating SAKTI DistributedLockService with full features");
        }
        
        return new DistributedLockServiceImpl(lockManager, idempotencyManager, healthIndicator, properties);
    }
}