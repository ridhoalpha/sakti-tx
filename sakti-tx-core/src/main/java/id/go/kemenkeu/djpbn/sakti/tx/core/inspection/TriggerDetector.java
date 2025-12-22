package id.go.kemenkeu.djpbn.sakti.tx.core.inspection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects database triggers on tables
 * Caches results to avoid repeated queries
 */
public class TriggerDetector {
    
    private static final Logger log = LoggerFactory.getLogger(TriggerDetector.class);
    
    // Cache: tableName -> hasTriggers
    private final Map<String, Boolean> triggerCache = new ConcurrentHashMap<>();
    private final Map<String, EntityManager> entityManagers;
    
    public TriggerDetector(Map<String, EntityManager> entityManagers) {
        this.entityManagers = entityManagers;
    }
    
    /**
     * Check if table has triggers
     */
    public boolean hasTriggers(String datasource, String tableName) {
        String cacheKey = datasource + ":" + tableName;
        
        return triggerCache.computeIfAbsent(cacheKey, k -> {
            EntityManager em = entityManagers.get(datasource);
            if (em == null) {
                log.warn("No EntityManager for datasource: {}", datasource);
                return false;
            }
            
            try {
                return detectTriggers(em, tableName);
            } catch (Exception e) {
                log.warn("Failed to detect triggers for {}.{}: {}", 
                    datasource, tableName, e.getMessage());
                return false; // Assume no triggers on error
            }
        });
    }
    
    private boolean detectTriggers(EntityManager em, String tableName) {
        // Try Oracle syntax first
        try {
            String sql = 
                "SELECT COUNT(*) FROM USER_TRIGGERS " +
                "WHERE TABLE_NAME = UPPER(:tableName) AND STATUS = 'ENABLED'";
            
            Query query = em.createNativeQuery(sql);
            query.setParameter("tableName", tableName);
            
            Number count = (Number) query.getSingleResult();
            boolean hasTriggers = count.intValue() > 0;
            
            if (hasTriggers) {
                log.info("Detected {} active triggers on table: {}", count, tableName);
            }
            
            return hasTriggers;
            
        } catch (Exception oracleError) {
            // Try MySQL/PostgreSQL syntax
            try {
                String sql = 
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TRIGGERS " +
                    "WHERE EVENT_OBJECT_TABLE = :tableName";
                
                Query query = em.createNativeQuery(sql);
                query.setParameter("tableName", tableName);
                
                Number count = (Number) query.getSingleResult();
                boolean hasTriggers = count.intValue() > 0;
                
                if (hasTriggers) {
                    log.info("Detected {} triggers on table: {}", count, tableName);
                }
                
                return hasTriggers;
                
            } catch (Exception genericError) {
                log.debug("Could not detect triggers using standard SQL: {}", 
                    genericError.getMessage());
                return false;
            }
        }
    }
    
    /**
     * Get trigger details (for logging)
     */
    public List<TriggerInfo> getTriggerDetails(String datasource, String tableName) {
        EntityManager em = entityManagers.get(datasource);
        if (em == null) {
            return Collections.emptyList();
        }
        
        try {
            // Oracle
            String sql = 
                "SELECT TRIGGER_NAME, TRIGGERING_EVENT, TRIGGER_TYPE " +
                "FROM USER_TRIGGERS " +
                "WHERE TABLE_NAME = UPPER(:tableName) AND STATUS = 'ENABLED'";
            
            Query query = em.createNativeQuery(sql);
            query.setParameter("tableName", tableName);
            
            List<Object[]> results = query.getResultList();
            List<TriggerInfo> triggers = new ArrayList<>();
            
            for (Object[] row : results) {
                triggers.add(new TriggerInfo(
                    (String) row[0], // name
                    (String) row[1], // event
                    (String) row[2]  // type
                ));
            }
            
            return triggers;
            
        } catch (Exception e) {
            log.debug("Could not get trigger details: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Clear cache (for testing or schema changes)
     */
    public void clearCache() {
        triggerCache.clear();
        log.info("Trigger cache cleared");
    }
    
    public static class TriggerInfo {
        private final String name;
        private final String event;
        private final String type;
        
        public TriggerInfo(String name, String event, String type) {
            this.name = name;
            this.event = event;
            this.type = type;
        }
        
        public String getName() { return name; }
        public String getEvent() { return event; }
        public String getType() { return type; }
        
        @Override
        public String toString() {
            return String.format("%s (%s %s)", name, event, type);
        }
    }
}