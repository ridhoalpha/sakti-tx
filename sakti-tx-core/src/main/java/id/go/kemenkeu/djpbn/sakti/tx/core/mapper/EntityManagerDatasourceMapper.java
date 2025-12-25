package id.go.kemenkeu.djpbn.sakti.tx.core.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps EntityManagerFactory to datasource names
 * Creates thread-safe, transaction-bound EntityManagers on demand
 * 
 * SAFE PATTERN:
 * - Store EntityManagerFactory (thread-safe)
 * - Create EntityManager per transaction (thread-local)
 * - Let Spring manage lifecycle
 * 
 * @version 2.0.0 - PRODUCTION SAFE
 */
public class EntityManagerDatasourceMapper {
    
    private static final Logger log = LoggerFactory.getLogger(EntityManagerDatasourceMapper.class);
    
    // SAFE: Store factories, NOT EntityManagers
    private final Map<String, EntityManagerFactory> datasourceToEmf = new ConcurrentHashMap<>();
    private final Map<EntityManagerFactory, String> emfToDatasource = new ConcurrentHashMap<>();
    
    /**
     * Register EntityManagerFactory dengan validation
     */
    public void registerEntityManagerFactory(String datasourceName, EntityManagerFactory emf) {
        if (emf == null) {
            log.error("❌ Attempting to register NULL EntityManagerFactory for: {}", datasourceName);
            return;
        }
        
        // Normalize datasource name
        String normalizedName = normalizeDatasourceName(datasourceName);
        
        // Check for duplicates
        EntityManagerFactory existing = datasourceToEmf.get(normalizedName);
        if (existing != null && existing != emf) {
            log.warn("⚠ Overwriting existing EntityManagerFactory for: {}", normalizedName);
        }
        
        // Register primary name
        datasourceToEmf.put(normalizedName, emf);
        emfToDatasource.put(emf, normalizedName);
        
        log.info("✓ Registered EntityManagerFactory: {} [entities: {}]", 
            normalizedName, 
            emf.getMetamodel().getEntities().size());
        
        // Also register with "TransactionManager" suffix if not already present
        if (!normalizedName.endsWith("TransactionManager")) {
            String txManagerName = normalizedName + "TransactionManager";
            datasourceToEmf.put(txManagerName, emf);
            log.debug("  → Also registered as: {}", txManagerName);
        }
        
        // Also register without suffix if present
        if (normalizedName.endsWith("TransactionManager")) {
            String shortName = normalizedName.replace("TransactionManager", "");
            if (!datasourceToEmf.containsKey(shortName)) {
                datasourceToEmf.put(shortName, emf);
                log.debug("  → Also registered as: {}", shortName);
            }
        }
    }
    
    /**
     * Normalize datasource name untuk consistency
     */
    private String normalizeDatasourceName(String beanName) {
        if (beanName == null || beanName.trim().isEmpty()) {
            return "default";
        }
        
        String lower = beanName.toLowerCase();
        
        // Pattern 1: db1/db2/db3 EntityManagerFactory
        if (lower.contains("db1") && lower.contains("entitymanagerfactory")) {
            return "db1";
        }
        if (lower.contains("db2") && lower.contains("entitymanagerfactory")) {
            return "db2";
        }
        if (lower.contains("db3") && lower.contains("entitymanagerfactory")) {
            return "db3";
        }
        
        // Pattern 2: db1/db2/db3 TransactionManager
        if (lower.contains("db1") && lower.contains("transactionmanager")) {
            return "db1TransactionManager";
        }
        if (lower.contains("db2") && lower.contains("transactionmanager")) {
            return "db2TransactionManager";
        }
        if (lower.contains("db3") && lower.contains("transactionmanager")) {
            return "db3TransactionManager";
        }
        
        // Pattern 3: Simple db1/db2/db3
        if (beanName.equals("db1") || beanName.equals("db2") || beanName.equals("db3")) {
            return beanName;
        }
        
        // Pattern 4: Primary/Secondary/Third
        if (lower.contains("primary") || lower.contains("first")) {
            return "db1";
        }
        if (lower.contains("secondary") || lower.contains("second")) {
            return "db2";
        }
        if (lower.contains("third")) {
            return "db3";
        }
        
        // Fallback: Use original name
        log.debug("Using original bean name as datasource: {}", beanName);
        return beanName;
    }
    
    /**
     * Get datasource name from EntityManagerFactory
     */
    public String getDatasourceName(EntityManagerFactory emf) {
        String name = emfToDatasource.get(emf);
        if (name == null) {
            log.warn("⚠ EntityManagerFactory not registered in mapper - using 'default'");
            log.warn("   Registered datasources: {}", datasourceToEmf.keySet());
            return "default";
        }
        return name;
    }
    
    /**
     * Get datasource name from EntityManager
     * SAFE: Extracts from EM's factory
     */
    public String getDatasourceName(EntityManager em) {
        try {
            EntityManagerFactory emf = em.getEntityManagerFactory();
            return getDatasourceName(emf);
        } catch (Exception e) {
            log.warn("⚠ Cannot determine datasource name for EntityManager: {}", e.getMessage());
            log.warn("   Registered datasources: {}", datasourceToEmf.keySet());
            return "default";
        }
    }
    
    /**
     * Get EntityManagerFactory by datasource name dengan smart lookup
     */
    public EntityManagerFactory getEntityManagerFactory(String datasourceName) {
        EntityManagerFactory emf = datasourceToEmf.get(datasourceName);
        
        if (emf == null) {
            // Try with "TransactionManager" suffix
            if (datasourceName.endsWith("TransactionManager")) {
                String shortName = datasourceName.replace("TransactionManager", "");
                emf = datasourceToEmf.get(shortName);
                
                if (emf != null) {
                    log.debug("Found EMF using short name: {} -> {}", 
                        datasourceName, shortName);
                    // Cache for future lookups
                    datasourceToEmf.put(datasourceName, emf);
                    return emf;
                }
            }
            
            // Try adding "TransactionManager" suffix
            if (!datasourceName.endsWith("TransactionManager")) {
                String longName = datasourceName + "TransactionManager";
                emf = datasourceToEmf.get(longName);
                
                if (emf != null) {
                    log.debug("Found EMF using long name: {} -> {}", 
                        datasourceName, longName);
                    // Cache for future lookups
                    datasourceToEmf.put(datasourceName, emf);
                    return emf;
                }
            }
            
            // Last attempt: Try normalization
            String normalized = normalizeDatasourceName(datasourceName);
            emf = datasourceToEmf.get(normalized);
            
            if (emf != null) {
                log.debug("Found EMF using normalized name: {} -> {}", 
                    datasourceName, normalized);
                // Cache for future lookups
                datasourceToEmf.put(datasourceName, emf);
                return emf;
            }
            
            // Not found - throw clear error
            log.error("═══════════════════════════════════════════════════════════");
            log.error("❌ CRITICAL: EntityManagerFactory not found");
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Requested datasource: {}", datasourceName);
            log.error("Available datasources: {}", datasourceToEmf.keySet());
            log.error("");
            log.error("POSSIBLE CAUSES:");
            log.error("1. Datasource bean name mismatch");
            log.error("2. EntityManagerFactory not registered in Spring context");
            log.error("3. Timing issue - registration not complete");
            log.error("");
            log.error("SOLUTION:");
            log.error("Check your datasource configuration:");
            log.error("  @Bean(name = \"db1EntityManagerFactory\")");
            log.error("  @Bean(name = \"db2EntityManagerFactory\")");
            log.error("  @Bean(name = \"db3EntityManagerFactory\")");
            log.error("═══════════════════════════════════════════════════════════");
            
            throw new IllegalStateException(
                String.format(
                    "No EntityManagerFactory found for datasource '%s'. Available: %s",
                    datasourceName, datasourceToEmf.keySet()
                )
            );
        }
        
        return emf;
    }
    
    /**
     * SAFE: Create transaction-bound EntityManager
     * This should ONLY be called within an active Spring transaction
     */
    public EntityManager createEntityManager(String datasourceName) {
        EntityManagerFactory emf = getEntityManagerFactory(datasourceName);
        
        // Create new EM - will be managed by current transaction
        EntityManager em = emf.createEntityManager();
        
        log.trace("Created transaction-bound EntityManager for: {}", datasourceName);
        
        return em;
    }
    
    /**
     * Get all registered datasource names
     */
    public Map<String, EntityManagerFactory> getAllEntityManagerFactories() {
        // Return deduplicated map
        Map<String, EntityManagerFactory> result = new ConcurrentHashMap<>();
        
        for (Map.Entry<String, EntityManagerFactory> entry : datasourceToEmf.entrySet()) {
            String datasource = entry.getKey();
            EntityManagerFactory emf = entry.getValue();
            
            // Skip duplicate entries (aliases)
            if (datasource.endsWith("TransactionManager")) {
                String shortName = datasource.replace("TransactionManager", "");
                if (datasourceToEmf.containsKey(shortName)) {
                    // Prefer short name
                    continue;
                }
            }
            
            result.put(datasource, emf);
        }
        
        return result;
    }
    
    /**
     * SAFE: Get EntityManagers for compensation
     * Creates NEW instances per compensation operation
     */
    public Map<String, EntityManager> createEntityManagersForCompensation() {
        Map<String, EntityManager> entityManagers = new ConcurrentHashMap<>();
        
        Map<String, EntityManagerFactory> deduplicated = getAllEntityManagerFactories();
        
        for (Map.Entry<String, EntityManagerFactory> entry : deduplicated.entrySet()) {
            String datasource = entry.getKey();
            EntityManagerFactory emf = entry.getValue();
            
            try {
                EntityManager em = emf.createEntityManager();
                entityManagers.put(datasource, em);
                log.trace("Created compensation EntityManager for: {}", datasource);
            } catch (Exception e) {
                log.error("Failed to create EntityManager for compensation: {}", 
                    datasource, e);
            }
        }
        
        return entityManagers;
    }
    
    /**
     * Validate registration after Spring context initialization
     * Call this from @PostConstruct in auto-configuration
     */
    public void validateRegistrations() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("EntityManagerFactory Registration Validation");
        log.info("═══════════════════════════════════════════════════════════");
        
        if (datasourceToEmf.isEmpty()) {
            log.error("❌ NO EntityManagerFactory registered!");
            log.error("   Distributed transactions will NOT work!");
            log.error("");
            log.error("SOLUTION:");
            log.error("Ensure you have datasource configuration:");
            log.error("  @Configuration");
            log.error("  @EnableJpaRepositories");
            log.error("  public class DatasourceConfig {");
            log.error("    @Bean public LocalContainerEntityManagerFactoryBean db1EntityManagerFactory() { ... }");
            log.error("    @Bean public LocalContainerEntityManagerFactoryBean db2EntityManagerFactory() { ... }");
            log.error("  }");
            log.error("═══════════════════════════════════════════════════════════");
            return;
        }
        
        // Get deduplicated count
        Map<String, EntityManagerFactory> deduplicated = getAllEntityManagerFactories();
        
        log.info("Registered datasources: {}", deduplicated.size());
        for (Map.Entry<String, EntityManagerFactory> entry : deduplicated.entrySet()) {
            String datasource = entry.getKey();
            EntityManagerFactory emf = entry.getValue();
            int entityCount = emf.getMetamodel().getEntities().size();
            
            log.info("  ✓ {} - {} entities", datasource, entityCount);
            
            // List some sample entities
            if (entityCount > 0) {
                emf.getMetamodel().getEntities().stream()
                    .limit(3)
                    .forEach(et -> log.debug("    → {}", et.getName()));
                
                if (entityCount > 3) {
                    log.debug("    → ... and {} more", entityCount - 3);
                }
            }
        }
        
        log.info("═══════════════════════════════════════════════════════════");
    }
}