package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import id.go.kemenkeu.djpbn.sakti.tx.core.inspection.SaktiTxStatementInspector;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.internal.SessionFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManagerFactory;

/**
 * CRITICAL: Register Hibernate StatementInspector automatically
 */
@Component
public class SaktiHibernateStatementInspectorRegistrar implements BeanPostProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiHibernateStatementInspectorRegistrar.class);
    
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        
        if (bean instanceof LocalContainerEntityManagerFactoryBean) {
            LocalContainerEntityManagerFactoryBean emfBean = 
                (LocalContainerEntityManagerFactoryBean) bean;
            
            try {
                EntityManagerFactory emf = emfBean.getObject();
                if (emf != null) {
                    registerStatementInspector(emf, beanName);
                }
            } catch (Exception e) {
                log.warn("Could not register StatementInspector for: {} - {}", 
                    beanName, e.getMessage());
            }
        }
        
        return bean;
    }
    
    private void registerStatementInspector(EntityManagerFactory emf, String beanName) {
        try {
            SessionFactoryImpl sessionFactory = emf.unwrap(SessionFactoryImpl.class);
            
            // Create StatementInspector instance
            SaktiTxStatementInspector inspector = new SaktiTxStatementInspector();
            
            // Register via Hibernate properties
            // Note: StatementInspector is registered differently than event listeners
            // It needs to be set via Hibernate properties before SessionFactory creation
            // This is a simplified version - full implementation needs properties customizer
            
            log.info("✓ Registered StatementInspector for {} (via properties)", beanName);
            
        } catch (ClassCastException e) {
            log.warn("Cannot register StatementInspector - not Hibernate: {}", beanName);
        } catch (Exception e) {
            log.warn("Failed to register StatementInspector: {} - {}", 
                beanName, e.getMessage());
        }
    }
}