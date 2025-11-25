package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Alternative: Configure Hibernate properties for auto-registration
 * This is a backup method if BeanPostProcessor doesn't work
 */
@Component
public class SaktiHibernatePropertiesCustomizer implements HibernatePropertiesCustomizer {
    
    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        // This will be picked up by Spring Boot's auto-configuration
        // But we prefer the BeanPostProcessor approach for more control
    }
}