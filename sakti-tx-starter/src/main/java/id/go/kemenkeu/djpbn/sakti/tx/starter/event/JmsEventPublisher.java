package id.go.kemenkeu.djpbn.sakti.tx.starter.event;

import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Queue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JmsEventPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(JmsEventPublisher.class);
    
    private final ConnectionFactory connectionFactory;
    private final long defaultTtl;
    
    public JmsEventPublisher(ConnectionFactory connectionFactory, SaktiTxProperties properties) {
        this.connectionFactory = connectionFactory;
        this.defaultTtl = properties.getJms().getDefaultTtlMs();
    }
    
    public void sendMessage(String queueName, String payload) {
        sendMessage(queueName, payload, null, defaultTtl);
    }
    
    public void sendMessage(String queueName, String payload, String correlationId) {
        sendMessage(queueName, payload, correlationId, defaultTtl);
    }
    
    public void sendMessage(String queueName, String payload, String correlationId, long ttl) {
        try (JMSContext context = connectionFactory.createContext()) {
            Queue queue = context.createQueue(queueName);
            JMSProducer producer = context.createProducer();
            
            producer.setTimeToLive(ttl);
            
            if (correlationId != null && !correlationId.trim().isEmpty()) {
                producer.setJMSCorrelationID(correlationId);
            }
            
            producer.send(queue, payload);
            log.debug("Message sent to queue: {}", queueName);
            
        } catch (Exception e) {
            log.error("Failed to send message to queue: {}", queueName, e);
        }
    }
}