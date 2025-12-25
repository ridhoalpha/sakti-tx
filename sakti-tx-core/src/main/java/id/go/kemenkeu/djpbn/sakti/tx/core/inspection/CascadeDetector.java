package id.go.kemenkeu.djpbn.sakti.tx.core.inspection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects JPA cascade operations that may have side effects
 */
public class CascadeDetector {
    
    private static final Logger log = LoggerFactory.getLogger(CascadeDetector.class);
    
    // Cache: entityClass -> hasCascade
    private final Map<Class<?>, CascadeInfo> cascadeCache = new ConcurrentHashMap<>();
    private final Map<String, EntityManagerFactory> entityManagerFactories;
    
    public CascadeDetector(Map<String, EntityManagerFactory> entityManagerFactories) {
        this.entityManagerFactories = entityManagerFactories;
    }
    
    /**
     * Check if entity has cascade operations
     */
    public CascadeInfo detectCascade(Class<?> entityClass) {
        return cascadeCache.computeIfAbsent(entityClass, this::analyzeCascade);
    }
    
    private CascadeInfo analyzeCascade(Class<?> entityClass) {
        CascadeInfo info = new CascadeInfo(entityClass);

        EntityManagerFactory emf = findEmfForEntity(entityClass);
        if (emf == null) {
            log.warn("No EntityManagerFactory found for {}", entityClass.getName());
            return info;
        }
        
        try {
            Metamodel metamodel = emf.getMetamodel();
            EntityType<?> entityType = metamodel.entity(entityClass);
            
            // Check all attributes
            for (Attribute<?, ?> attribute : entityType.getAttributes()) {
                
                if (attribute instanceof PluralAttribute) {
                    PluralAttribute<?, ?, ?> pluralAttr = (PluralAttribute<?, ?, ?>) attribute;
                    
                    // Check for cascade annotations
                    jakarta.persistence.OneToMany oneToMany = 
                        getAnnotation(entityClass, attribute.getName(), 
                            jakarta.persistence.OneToMany.class);
                    
                    if (oneToMany != null) {
                        Set<jakarta.persistence.CascadeType> cascadeTypes = 
                            Set.of(oneToMany.cascade());
                        
                        if (cascadeTypes.contains(jakarta.persistence.CascadeType.ALL) ||
                            cascadeTypes.contains(jakarta.persistence.CascadeType.REMOVE)) {
                            
                            info.addCascadeDelete(attribute.getName());
                            log.debug("Detected cascade DELETE on {}.{}", 
                                entityClass.getSimpleName(), attribute.getName());
                        }
                        
                        if (cascadeTypes.contains(jakarta.persistence.CascadeType.ALL) ||
                            cascadeTypes.contains(jakarta.persistence.CascadeType.PERSIST)) {
                            
                            info.addCascadePersist(attribute.getName());
                        }
                    }
                    
                    // Check ManyToMany
                    jakarta.persistence.ManyToMany manyToMany = 
                        getAnnotation(entityClass, attribute.getName(), 
                            jakarta.persistence.ManyToMany.class);
                    
                    if (manyToMany != null) {
                        Set<jakarta.persistence.CascadeType> cascadeTypes = 
                            Set.of(manyToMany.cascade());
                        
                        if (!cascadeTypes.isEmpty()) {
                            info.addCascadeDelete(attribute.getName());
                            log.debug("Detected cascade on ManyToMany {}.{}", 
                                entityClass.getSimpleName(), attribute.getName());
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            log.warn("Failed to analyze cascade for {}: {}", 
                entityClass.getSimpleName(), e.getMessage());
        }
        
        return info;
    }

    private EntityManagerFactory findEmfForEntity(Class<?> entityClass) {
        for (EntityManagerFactory emf : entityManagerFactories.values()) {
            try {
                emf.getMetamodel().entity(entityClass);
                return emf;
            } catch (IllegalArgumentException ignored) {
                // Not managed by this EMF
            }
        }
        return null;
    }
    
    private <T extends java.lang.annotation.Annotation> T getAnnotation(
            Class<?> entityClass, String fieldName, Class<T> annotationClass) {
        
        try {
            java.lang.reflect.Field field = entityClass.getDeclaredField(fieldName);
            return field.getAnnotation(annotationClass);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
    
    public static class CascadeInfo {
        private final Class<?> entityClass;
        private final Set<String> cascadeDeleteFields = new HashSet<>();
        private final Set<String> cascadePersistFields = new HashSet<>();
        
        public CascadeInfo(Class<?> entityClass) {
            this.entityClass = entityClass;
        }
        
        public void addCascadeDelete(String fieldName) {
            cascadeDeleteFields.add(fieldName);
        }
        
        public void addCascadePersist(String fieldName) {
            cascadePersistFields.add(fieldName);
        }
        
        public boolean hasCascadeDelete() {
            return !cascadeDeleteFields.isEmpty();
        }
        
        public boolean hasCascadePersist() {
            return !cascadePersistFields.isEmpty();
        }
        
        public Set<String> getCascadeDeleteFields() {
            return Collections.unmodifiableSet(cascadeDeleteFields);
        }
        
        public Set<String> getCascadePersistFields() {
            return Collections.unmodifiableSet(cascadePersistFields);
        }
        
        @Override
        public String toString() {
            return String.format("CascadeInfo{entity=%s, cascadeDelete=%s, cascadePersist=%s}",
                entityClass.getSimpleName(), cascadeDeleteFields, cascadePersistFields);
        }
    }
}