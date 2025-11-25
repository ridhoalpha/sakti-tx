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
        log.info("Registered EntityManager: {} -> {}", datasourceName, em);
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
        if (persistenceUnitName.contains("db1") || persistenceUnitName.contains("primary")) {
            return "db1";
        } else if (persistenceUnitName.contains("db2") || persistenceUnitName.contains("secondary")) {
            return "db2";
        } else if (persistenceUnitName.contains("db3")) {
            return "db3";
        }
        return persistenceUnitName;
    }
}