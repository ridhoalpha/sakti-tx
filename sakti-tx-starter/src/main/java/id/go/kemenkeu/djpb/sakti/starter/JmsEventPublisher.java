package id.go.kemenkeu.djpbn.sakti.starter;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.jms.Destination;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

public class JmsEventPublisher {
    private final String brokerUrl;
    private final String user;
    private final String password;
    private static final Logger log = LoggerFactory.getLogger(JmsEventPublisher.class);
    private final long defaultTtl;

    public JmsEventPublisher(String brokerUrl, String user, String password, long defaultTtl) {
        this.brokerUrl = brokerUrl; this.user = user; this.password = password; this.defaultTtl = defaultTtl;
    }

    public void sendMessageQ(String payload, String replyTo, String correlationId) {
        sendMessageQInternal(payload, replyTo, correlationId, defaultTtl);
    }

    public void sendMessageQ(String payload, Destination replyTo, String correlationId) {
        sendMessageQInternal(payload, replyTo, correlationId, defaultTtl);
    }

    public void sendMessageQ(String payload, String replyTo, long ttlMs) {
        sendMessageQInternal(payload, replyTo, null, ttlMs);
    }

    private void sendMessageQInternal(String payload, Object replyTo, String correlationId, long ttlMs) {
        // 1. Initialize ActiveMQConnectionFactory outside try-with-resources
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(brokerUrl);
        cf.setUserName(user);
        cf.setPassword(password);

        // 2. Start the try-with-resources block with the AutoCloseable Connection
        try (ActiveMQConnection conn = (ActiveMQConnection) cf.createConnection()) {
            conn.start();
            
            // The rest of your existing nested try-with-resources blocks (Session, Producer) are fine
            try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Destination dest = (replyTo instanceof String) ? session.createQueue((String) replyTo) : (Destination) replyTo;
                try (MessageProducer producer = session.createProducer(dest)) {
                    TextMessage msg = session.createTextMessage(payload);
                    if (correlationId != null) msg.setJMSCorrelationID(correlationId);
                    producer.send(msg, jakarta.jms.Message.DEFAULT_DELIVERY_MODE, jakarta.jms.Message.DEFAULT_PRIORITY, ttlMs);
                }
            }
        } catch (Exception e) { 
            // Note: The 'cannot find symbol' for 'log' is likely due to 'log' not being defined/imported.
            // If 'log' is a valid slf4j/log4j instance in the class, this line is correct.
            log.error("sendMessageQ failed", e); 
        }
    }
}
