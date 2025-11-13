package id.go.kemenkeu.djpbn.sakti.starter;

import id.go.kemenkeu.djpbn.sakti.core.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

@Configuration
@EnableConfigurationProperties(SaktiProperties.class)
public class SaktiAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(SaktiProperties props) {
        Config cfg = new Config();
        String addr = "redis://" + props.getRedis().getHost() + ":" + props.getRedis().getPort();
        cfg.useSingleServer().setAddress(addr);
        return Redisson.create(cfg);
    }

    @Bean
    @ConditionalOnMissingBean
    public LockManager lockManager(RedissonClient redisson) { return new RedissonLockManager(redisson); }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyManager idempotencyManager(RedissonClient redisson, SaktiProperties props) { return new IdempotencyManager(redisson, props.getIdempotency().getPrefix()); }

    @Bean
    @ConditionalOnMissingBean
    public SaktiTransactionManager saktiTransactionManager(LockManager lockManager, @Autowired List<DataSource> dataSources) {
        return new SaktiTransactionManager(lockManager, dataSources);
    }

    @Bean
    @ConditionalOnMissingBean
    public TxLogRepository txLogRepository(@Autowired DataSource mainDataSource) { return new TxLogRepository(mainDataSource); }

    @Bean
    public RecoveryWorker recoveryWorker(@Autowired DataSource mainDataSource) { return new RecoveryWorker(mainDataSource, 60000L); }
}
