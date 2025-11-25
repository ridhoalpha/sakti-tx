package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import id.go.kemenkeu.djpbn.sakti.tx.core.listener.EntityOperationListener;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.internal.SessionFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManagerFactory;

/**
 * Automatically register EntityOperationListener via Hibernate Event System
 * NO persistence.xml needed in client projects!
 */
@Component
public class EntityListenerRegistrar implements BeanPostProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(EntityListenerRegistrar.class);
    
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        
        if (bean instanceof LocalContainerEntityManagerFactoryBean) {
            LocalContainerEntityManagerFactoryBean emfBean = 
                (LocalContainerEntityManagerFactoryBean) bean;
            
            try {
                EntityManagerFactory emf = emfBean.getObject();
                if (emf != null) {
                    registerHibernateListeners(emf, beanName);
                }
            } catch (Exception e) {
                log.warn("Could not register entity listeners for: {}", beanName, e);
            }
        }
        
        return bean;
    }
    
    private void registerHibernateListeners(EntityManagerFactory emf, String beanName) {
        try {
            // Unwrap to Hibernate SessionFactory
            SessionFactoryImpl sessionFactory = emf.unwrap(SessionFactoryImpl.class);
            EventListenerRegistry registry = sessionFactory
                .getServiceRegistry()
                .getService(EventListenerRegistry.class);
            
            // Create single listener instance
            EntityOperationListener listener = new EntityOperationListener();
            
            // Register for all entity events
            registry.appendListeners(EventType.PRE_INSERT, listener);
            registry.appendListeners(EventType.POST_INSERT, listener);
            registry.appendListeners(EventType.PRE_UPDATE, listener);
            registry.appendListeners(EventType.POST_UPDATE, listener);
            registry.appendListeners(EventType.PRE_DELETE, listener);
            registry.appendListeners(EventType.POST_DELETE, listener);
            
            int entityCount = emf.getMetamodel().getEntities().size();
            
            log.info("✓ Registered EntityOperationListener for {} entities in {} (via Hibernate Event System)", 
                entityCount, beanName);
            
        } catch (Exception e) {
            log.error("✗ Failed to register Hibernate listeners for: {}", beanName, e);
            log.error("  Automatic tracking will NOT work for this EntityManager!");
            log.error("  Ensure Hibernate is used as JPA provider.");
        }
    }
}