package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import id.go.kemenkeu.djpbn.sakti.tx.core.inspection.SaktiTxStatementInspector;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CRITICAL: Register StatementInspector via Hibernate properties
 */
@Component
public class SaktiHibernatePropertiesCustomizer implements HibernatePropertiesCustomizer {
    
    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        // Register StatementInspector
        hibernateProperties.put(
            "hibernate.session_factory.statement_inspector",
            SaktiTxStatementInspector.class.getName()
        );
    }
}