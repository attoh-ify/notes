package com.example.notes;

import com.example.notes.activemq.services.EmailService;
import com.example.notes.feat_document.collaborator_count_notifier.CollaboratorCountNotifier;
import com.example.notes.feat_document.formatter.impl.CharSequenceDocumentFormatter;
import com.example.notes.feat_relay_operation.operation_relayer.OperationRelayer;
import com.example.notes.shared.document_store.DocumentStore;
import com.example.notes.shared.document_store.impl.SimpleHashMapDocumentStore;
import com.example.notes.shared.operation_queue.OperationQueue;
import com.example.notes.shared.operation_queue.impl.OperationQueueImpl;
import com.example.notes.shared.operation_transformations.OperationTransformations;
import com.example.notes.shared.operation_transformations.impl.CharSequenceOperationTransformations;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.DeliveryMode;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.jms.pool.PooledConnectionFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class NotesApplication implements CommandLineRunner {
    private final static String ACTIVEMQ_URL = "tcp://localhost:61616";
    private final static String ACTIVEMQ_USERNAME = "admin";
    private final static String ACTIVEMQ_PASSWORD = "admin";

    private final EmailService emailService;

    public NotesApplication(EmailService emailService) {
        this.emailService = emailService;
    }

    public static void main(String[] args) {
        SpringApplication.run(NotesApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        final ActiveMQConnectionFactory connectionFactory = createActiveMQConnectionFactory();
        final PooledConnectionFactory pooledConnectionFactory = createPooledConnectionFactory(connectionFactory);

        sendMessage(pooledConnectionFactory);
        receiveMessage(connectionFactory);

        pooledConnectionFactory.stop();
	}

    private void sendMessage(PooledConnectionFactory pooledConnectionFactory) throws JMSException {
        // establish a connection for the producer
        final Connection producerConnection;
        try {
            producerConnection = pooledConnectionFactory.createConnection();
        } catch (jakarta.jms.JMSException e) {
            throw new RuntimeException(e);
        }
        producerConnection.start();

        // create a session
        final Session producerSession = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        // create a queue
        final Destination producerDestination = producerSession.createQueue("email-queue");

        // create a producer from the session to the queue
        final MessageProducer producer = producerSession.createProducer(producerDestination);
        producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

        // create a message
        final String text = "Welcome to notes app!";
        final TextMessage producerMessage = producerSession.createTextMessage(text);

        // send the message
        producer.send(producerMessage);
        System.out.println("Message sent");

        // clean up the producer
        producer.close();
        producerSession.close();
        producerConnection.close();
    }

    private void receiveMessage(ActiveMQConnectionFactory connectionFactory) throws JMSException {
        // establish a connection for the consumer
        // note: Consumers should not use PooledConnectionFactory.
        final Connection consumerConnection = connectionFactory.createConnection();
        consumerConnection.start();

        // create a session
        final Session consumerSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        // create a queue
        final Destination consumerDestination = consumerSession.createQueue("email-queue");

        // create a message consumer from the session to the queue
        final MessageConsumer consumer = consumerSession.createConsumer(consumerDestination);

        // begin to wait for messages
        final Message consumerMessage = consumer.receive(1000);

        // receive the message when it arrives
        final TextMessage consumerTextMessage = (TextMessage) consumerMessage;
        System.out.println("Message received: " + consumerTextMessage.getText());
        emailService.sendEmail(
                "alexander.attoh22@gmail.com",
                "Welcome to notes!",
                consumerTextMessage.getText());

        // clean up the consumer
        consumer.close();
        consumerSession.close();
        consumerConnection.close();
    }

    private static ActiveMQConnectionFactory createActiveMQConnectionFactory() {
        final ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(ACTIVEMQ_URL);
        connectionFactory.setUserName(ACTIVEMQ_USERNAME);
        connectionFactory.setPassword(ACTIVEMQ_PASSWORD);
        return connectionFactory;
    }

    private static PooledConnectionFactory createPooledConnectionFactory(ActiveMQConnectionFactory connectionFactory) {
        final PooledConnectionFactory pooledConnectionFactory = new PooledConnectionFactory();
        pooledConnectionFactory.setConnectionFactory(connectionFactory);
        pooledConnectionFactory.setMaxConnections(10);
        return pooledConnectionFactory;
    }

    @Bean
    public OperationQueue getOperationQueue() {
        return new OperationQueueImpl();
    }

    @Bean
    public OperationRelayer getOperationRelayer() {
        return new OperationRelayer();
    }

    @Bean
    public OperationTransformations getOperationTransformations() {
        return new CharSequenceOperationTransformations();
    }

    @Bean
    public DocumentStore getDocumentStore() {
        return new SimpleHashMapDocumentStore(CharSequenceDocumentFormatter::new);
    }

    @Bean
    public CollaboratorCountNotifier getCollaboratorCountNotifier() {
        return new CollaboratorCountNotifier();
    }
}
