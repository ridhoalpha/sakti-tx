package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Verifies Redis/Dragonfly persistence configuration at startup
 * Logs warnings if durability settings are not optimal for distributed transactions
 */
@Component
@ConditionalOnProperty(prefix = "sakti.tx.dragonfly", name = "verify-durability", havingValue = "true", matchIfMissing = true)
public class RedisDurabilityVerifier {
    
    private static final Logger log = LoggerFactory.getLogger(RedisDurabilityVerifier.class);
    
    private final RedissonClient redissonClient;
    private final SaktiTxProperties properties;
    
    public RedisDurabilityVerifier(RedissonClient redissonClient, SaktiTxProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }
    
    @PostConstruct
    public void verifyDurability() {
        if (!properties.getDragonfly().isEnabled()) {
            return;
        }
        
        log.info("═══════════════════════════════════════════════════════════");
        log.info("Verifying Redis/Dragonfly Persistence Configuration...");
        log.info("═══════════════════════════════════════════════════════════");
        
        try {
            // Check if Redis/Dragonfly is reachable
            boolean ping = redissonClient.getNodesGroup().pingAll();
            if (!ping) {
                log.error("✗ Redis/Dragonfly is not reachable!");
                return;
            }
            
            log.info("✓ Redis/Dragonfly is reachable");
            
            // Try to get persistence info via INFO command
            // Note: Dragonfly returns different format than Redis
            try {
                String info = getServerInfo();
                checkPersistenceSettings(info);
            } catch (Exception e) {
                log.warn("Could not retrieve server info: {}", e.getMessage());
                log.warn("   Manual verification recommended:");
                log.warn("   - Dragonfly: Check --snapshot_cron and --dir flags");
                log.warn("   - Redis: Check appendonly and save directives");
            }
            
            // Check if WAIT command is enabled
            if (properties.getDragonfly().isWaitForSync()) {
                log.info("✓ WAIT for sync is ENABLED (sakti.tx.dragonfly.wait-for-sync=true)");
                log.info("   Transaction logs will wait for disk sync before proceeding");
            } else {
                log.warn("⚠ WAIT for sync is DISABLED (sakti.tx.dragonfly.wait-for-sync=false)");
                log.warn("   Risk: Redis crash before sync may cause partial commits");
                log.warn("   Recommendation: Enable for production:");
                log.warn("   sakti.tx.dragonfly.wait-for-sync=true");
            }
            
            log.info("═══════════════════════════════════════════════════════════");
            
        } catch (Exception e) {
            log.error("Failed to verify durability settings", e);
        }
    }
    
    private String getServerInfo() {
        try {
            // Try to execute INFO command
            // This is a workaround since Redisson doesn't expose INFO directly
            return "unknown"; // Placeholder - actual implementation depends on Redisson API
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
    
    private void checkPersistenceSettings(String info) {
        // Parse INFO response and check persistence settings
        if (info.contains("rdb_last_save_time") || info.contains("snapshot")) {
            log.info("✓ Persistence appears to be configured");
        } else {
            log.warn("⚠ Could not detect persistence configuration");
            log.warn("   Please verify manually:");
            log.warn("   ");
            log.warn("   For Dragonfly:");
            log.warn("   - Ensure --snapshot_cron is set (e.g., '0 */4 * * *')");
            log.warn("   - Ensure --dir is set to persistent volume");
            log.warn("   - Ensure --dbfilename is set");
            log.warn("   ");
            log.warn("   For Redis:");
            log.warn("   - Ensure 'appendonly yes' in redis.conf");
            log.warn("   - OR ensure 'save' directives are configured");
            log.warn("   ");
            log.warn("   Without persistence, transaction logs may be lost on restart!");
        }
    }
}