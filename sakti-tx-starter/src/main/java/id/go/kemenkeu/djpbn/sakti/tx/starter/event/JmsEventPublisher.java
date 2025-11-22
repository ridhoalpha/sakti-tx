package id.go.kemenkeu.djpbn.sakti.tx.starter.event;

import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import jakarta.jms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * JMS Event Publisher for publishing messages to ActiveMQ Artemis
 * Supports both Queue and Topic messaging patterns
 */
public class JmsEventPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(JmsEventPublisher.class);
    
    private final ConnectionFactory connectionFactory;
    private final long defaultTtlMs;
    private final boolean enabled;
    
    public JmsEventPublisher(ConnectionFactory connectionFactory, SaktiTxProperties properties) {
        this.connectionFactory = connectionFactory;
        this.defaultTtlMs = properties.getJms().getDefaultTtlMs();
        this.enabled = properties.getJms().isEnabled();
    }
    
    /**
     * Send message to queue (simple)
     */
    public void sendToQueue(String queueName, String payload) {
        sendToQueue(queueName, payload, null, defaultTtlMs, null);
    }
    
    /**
     * Send message to queue with correlation ID
     */
    public void sendToQueue(String queueName, String payload, String correlationId) {
        sendToQueue(queueName, payload, correlationId, defaultTtlMs, null);
    }
    
    /**
     * Send message to queue with correlation ID and TTL
     */
    public void sendToQueue(String queueName, String payload, String correlationId, long ttlMs) {
        sendToQueue(queueName, payload, correlationId, ttlMs, null);
    }
    
    /**
     * Send message to queue with full control
     */
    public void sendToQueue(String queueName, String payload, String correlationId, 
                           long ttlMs, Map<String, Object> properties) {
        if (!enabled) {
            log.debug("JMS disabled - message not sent to queue: {}", queueName);
            return;
        }
        
        Connection connection = null;
        Session session = null;
        MessageProducer producer = null;
        
        try {
            connection = connectionFactory.createConnection();
            connection.start();
            
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(queueName);
            producer = session.createProducer(queue);
            
            TextMessage message = session.createTextMessage(payload);
            
            // Set correlation ID if provided
            if (correlationId != null && !correlationId.trim().isEmpty()) {
                message.setJMSCorrelationID(correlationId);
            }
            
            // Set custom properties if provided
            if (properties != null && !properties.isEmpty()) {
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    message.setObjectProperty(entry.getKey(), entry.getValue());
                }
            }
            
            // Set TTL
            producer.setTimeToLive(ttlMs);
            
            // Send message
            producer.send(message, DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, ttlMs);
            
            log.debug("Message sent to queue: {} (correlationId: {}, ttl: {}ms)", 
                     queueName, correlationId, ttlMs);
            
        } catch (JMSException e) {
            log.error("Failed to send message to queue: {}", queueName, e);
            throw new RuntimeException("JMS send failed: " + e.getMessage(), e);
        } finally {
            closeResources(producer, session, connection);
        }
    }
    
    /**
     * Send message to topic (simple)
     */
    public void sendToTopic(String topicName, String payload) {
        sendToTopic(topicName, payload, null, defaultTtlMs, null);
    }
    
    /**
     * Send message to topic with correlation ID
     */
    public void sendToTopic(String topicName, String payload, String correlationId) {
        sendToTopic(topicName, payload, correlationId, defaultTtlMs, null);
    }
    
    /**
     * Send message to topic with full control
     */
    public void sendToTopic(String topicName, String payload, String correlationId,
                           long ttlMs, Map<String, Object> properties) {
        if (!enabled) {
            log.debug("JMS disabled - message not sent to topic: {}", topicName);
            return;
        }
        
        Connection connection = null;
        Session session = null;
        MessageProducer producer = null;
        
        try {
            connection = connectionFactory.createConnection();
            connection.start();
            
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(topicName);
            producer = session.createProducer(topic);
            
            TextMessage message = session.createTextMessage(payload);
            
            if (correlationId != null && !correlationId.trim().isEmpty()) {
                message.setJMSCorrelationID(correlationId);
            }
            
            if (properties != null && !properties.isEmpty()) {
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    message.setObjectProperty(entry.getKey(), entry.getValue());
                }
            }
            
            producer.setTimeToLive(ttlMs);
            producer.send(message, DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, ttlMs);
            
            log.debug("Message sent to topic: {} (correlationId: {}, ttl: {}ms)", 
                     topicName, correlationId, ttlMs);
            
        } catch (JMSException e) {
            log.error("Failed to send message to topic: {}", topicName, e);
            throw new RuntimeException("JMS send failed: " + e.getMessage(), e);
        } finally {
            closeResources(producer, session, connection);
        }
    }
    
    /**
     * Send message with reply-to queue
     */
    public void sendWithReplyTo(String queueName, String replyToQueue, String payload, 
                                String correlationId) {
        if (!enabled) {
            log.debug("JMS disabled - message not sent");
            return;
        }
        
        Connection connection = null;
        Session session = null;
        MessageProducer producer = null;
        
        try {
            connection = connectionFactory.createConnection();
            connection.start();
            
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(queueName);
            Queue replyQueue = session.createQueue(replyToQueue);
            
            producer = session.createProducer(queue);
            
            TextMessage message = session.createTextMessage(payload);
            message.setJMSReplyTo(replyQueue);
            
            if (correlationId != null && !correlationId.trim().isEmpty()) {
                message.setJMSCorrelationID(correlationId);
            }
            
            producer.setTimeToLive(defaultTtlMs);
            producer.send(message);
            
            log.debug("Message sent to queue: {} with replyTo: {}", queueName, replyToQueue);
            
        } catch (JMSException e) {
            log.error("Failed to send message with reply-to", e);
            throw new RuntimeException("JMS send failed: " + e.getMessage(), e);
        } finally {
            closeResources(producer, session, connection);
        }
    }
    
    /**
     * Publish event (convenience method for state updates)
     */
    public void publishEvent(String eventType, String payload) {
        Map<String, Object> props = new HashMap<>();
        props.put("eventType", eventType);
        props.put("timestamp", System.currentTimeMillis());
        
        sendToQueue("sakti.events", payload, null, defaultTtlMs, props);
    }
    
    /**
     * Publish state change event
     */
    public void publishStateChange(String entityId, String fromState, String toState, 
                                   String payload) {
        Map<String, Object> props = new HashMap<>();
        props.put("eventType", "STATE_CHANGE");
        props.put("entityId", entityId);
        props.put("fromState", fromState);
        props.put("toState", toState);
        props.put("timestamp", System.currentTimeMillis());
        
        sendToQueue("sakti.state.changes", payload, entityId, defaultTtlMs, props);
    }
    
    /**
     * Check if JMS is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Close JMS resources safely
     */
    private void closeResources(MessageProducer producer, Session session, Connection connection) {
        try {
            if (producer != null) producer.close();
        } catch (Exception e) {
            log.warn("Error closing producer: {}", e.getMessage());
        }
        
        try {
            if (session != null) session.close();
        } catch (Exception e) {
            log.warn("Error closing session: {}", e.getMessage());
        }
        
        try {
            if (connection != null) connection.close();
        } catch (Exception e) {
            log.warn("Error closing connection: {}", e.getMessage());
        }
    }
}