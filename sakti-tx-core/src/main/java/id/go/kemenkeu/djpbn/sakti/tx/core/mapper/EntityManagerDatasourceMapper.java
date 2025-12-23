package id.go.kemenkeu.djpbn.sakti.tx.core.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps EntityManager instances to datasource names
 * Auto-detects from persistence unit name or bean name
 */
public class EntityManagerDatasourceMapper {
    
    private static final Logger log = LoggerFactory.getLogger(EntityManagerDatasourceMapper.class);
    
    private final Map<EntityManager, String> emToDatasource = new ConcurrentHashMap<>();
    private final Map<String, EntityManager> datasourceToEm = new ConcurrentHashMap<>();
    
    public void registerEntityManager(String datasourceName, EntityManager em) {
        emToDatasource.put(em, datasourceName);
        datasourceToEm.put(datasourceName, em);
        
        if (!datasourceName.endsWith("TransactionManager")) {
            String txManagerName = datasourceName + "TransactionManager";
            datasourceToEm.put(txManagerName, em);
            log.info("Registered EntityManager: {} -> {} (+ alias: {})", 
                datasourceName, em, txManagerName);
        } else {
            log.info("Registered EntityManager: {} -> {}", datasourceName, em);
        }
    }
    
    public String getDatasourceName(EntityManager em) {
        String name = emToDatasource.get(em);
        if (name == null) {
            try {
                String unitName = em.getEntityManagerFactory()
                    .getProperties()
                    .getOrDefault("hibernate.ejb.persistenceUnitName", "default")
                    .toString();
                
                name = extractDatasourceName(unitName);
                emToDatasource.put(em, name);
                
                log.debug("Auto-detected datasource name: {} for persistence unit: {}", 
                    name, unitName);
                
            } catch (Exception e) {
                log.warn("Cannot determine datasource name for EntityManager, using 'default'");
                name = "default";
            }
        }
        return name;
    }
    
    public EntityManager getEntityManager(String datasourceName) {
        EntityManager em = datasourceToEm.get(datasourceName);
        
        if (em == null) {
            if (datasourceName.endsWith("TransactionManager")) {
                String shortName = datasourceName.replace("TransactionManager", "");
                em = datasourceToEm.get(shortName);
                
                if (em != null) {
                    log.debug("Found EntityManager using short name: {} -> {}", 
                        datasourceName, shortName);
                    // Cache this lookup for next time
                    datasourceToEm.put(datasourceName, em);
                    return em;
                }
            }
            
            // Try adding "TransactionManager" suffix
            if (!datasourceName.endsWith("TransactionManager")) {
                String longName = datasourceName + "TransactionManager";
                em = datasourceToEm.get(longName);
                
                if (em != null) {
                    log.debug("Found EntityManager using long name: {} -> {}", 
                        datasourceName, longName);
                    // Cache this lookup for next time
                    datasourceToEm.put(datasourceName, em);
                    return em;
                }
            }
            
            throw new IllegalStateException(
                "No EntityManager registered for datasource: " + datasourceName + 
                ". Available: " + datasourceToEm.keySet()
            );
        }
        
        return em;
    }
    
    public Map<String, EntityManager> getAllEntityManagers() {
        return new ConcurrentHashMap<>(datasourceToEm);
    }
    
    private String extractDatasourceName(String persistenceUnitName) {
        String lower = persistenceUnitName.toLowerCase();
        
        if (lower.contains("db1") || lower.contains("primary") || lower.contains("first")) {
            return "db1";
        } else if (lower.contains("db2") || lower.contains("secondary") || lower.contains("second")) {
            return "db2";
        } else if (lower.contains("db3") || lower.contains("third")) {
            return "db3";
        }
        
        return persistenceUnitName;
    }
}