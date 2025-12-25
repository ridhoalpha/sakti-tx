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
     * Register EntityManagerFactory (thread-safe)
     */
    public void registerEntityManagerFactory(String datasourceName, EntityManagerFactory emf) {
        datasourceToEmf.put(datasourceName, emf);
        emfToDatasource.put(emf, datasourceName);
        
        // Also register with "TransactionManager" suffix
        if (!datasourceName.endsWith("TransactionManager")) {
            String txManagerName = datasourceName + "TransactionManager";
            datasourceToEmf.put(txManagerName, emf);
            log.debug("Registered EntityManagerFactory: {} (+ alias: {})", 
                datasourceName, txManagerName);
        } else {
            log.debug("Registered EntityManagerFactory: {}", datasourceName);
        }
    }
    
    /**
     * Get datasource name from EntityManagerFactory
     */
    public String getDatasourceName(EntityManagerFactory emf) {
        String name = emfToDatasource.get(emf);
        if (name == null) {
            log.warn("EntityManagerFactory not registered, using 'default'");
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
            log.warn("Cannot determine datasource name for EntityManager, using 'default'");
            return "default";
        }
    }
    
    /**
     * Get EntityManagerFactory by datasource name
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
                    datasourceToEmf.put(datasourceName, emf);
                    return emf;
                }
            }
            
            throw new IllegalStateException(
                "No EntityManagerFactory found for datasource: " + datasourceName + 
                ". Available: " + datasourceToEmf.keySet()
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
        return new ConcurrentHashMap<>(datasourceToEmf);
    }
    
    /**
     * SAFE: Get EntityManagers for compensation
     * Creates NEW instances per compensation operation
     */
    public Map<String, EntityManager> createEntityManagersForCompensation() {
        Map<String, EntityManager> entityManagers = new ConcurrentHashMap<>();
        
        for (Map.Entry<String, EntityManagerFactory> entry : datasourceToEmf.entrySet()) {
            String datasource = entry.getKey();
            EntityManagerFactory emf = entry.getValue();
            
            // Skip duplicate entries (aliases)
            if (datasource.endsWith("TransactionManager") && 
                datasourceToEmf.containsKey(datasource.replace("TransactionManager", ""))) {
                continue;
            }
            
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
}