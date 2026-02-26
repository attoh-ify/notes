package com.example.notes;

import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.notifier.CursorNotifier;
import com.example.notes.notifier.OperationRelayer;
//import jakarta.jms.Connection;
//import jakarta.jms.Destination;
//import jakarta.jms.JMSException;
//import jakarta.jms.Message;
//import jakarta.jms.MessageConsumer;
//import jakarta.jms.MessageProducer;
//import jakarta.jms.Session;
//import jakarta.jms.TextMessage;
//import jakarta.jms.DeliveryMode;
//
//import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class NotesApplication {
//    private final static String ACTIVEMQ_URL = "tcp://localhost:61616";
//    private final static String ACTIVEMQ_USERNAME = "admin";
//    private final static String ACTIVEMQ_PASSWORD = "admin";

    public static void main(String[] args) {
        SpringApplication.run(NotesApplication.class, args);
    }

//    @Override
//    public void run(String... args) throws Exception {
//        final ActiveMQConnectionFactory connectionFactory = createActiveMQConnectionFactory();
//        final JmsPoolConnectionFactory pooledConnectionFactory = createPooledConnectionFactory(connectionFactory);
//
////        sendMessage(pooledConnectionFactory);
////        receiveMessage(connectionFactory);
//
//        pooledConnectionFactory.stop();
//	}

//    private void sendMessage(JmsPoolConnectionFactory pooledConnectionFactory) throws JMSException {
//        // establish a connection for the producer
//        final Connection producerConnection;
//        try {
//            producerConnection = pooledConnectionFactory.createConnection();
//        } catch (jakarta.jms.JMSException e) {
//            throw new RuntimeException(e);
//        }
//        producerConnection.start();
//
//        // create a session
//        final Session producerSession = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
//
//        // create a queue
//        final Destination producerDestination = producerSession.createQueue("email-queue");
//
//        // create a producer from the session to the queue
//        final MessageProducer producer = producerSession.createProducer(producerDestination);
//        producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
//
//        // create a message
//        final String text = "Welcome to notes app! Hope you have a productive session.";
//        final TextMessage producerMessage = producerSession.createTextMessage(text);
//
//        // send the message
//        producer.send(producerMessage);
//        System.out.println("Message sent");
//
//        // clean up the producer
//        producer.close();
//        producerSession.close();
//        producerConnection.close();
//    }

//    private void receiveMessage(ActiveMQConnectionFactory connectionFactory) throws JMSException {
//        // establish a connection for the consumer
//        // note: Consumers should not use PooledConnectionFactory.
//        final Connection consumerConnection = connectionFactory.createConnection();
//        consumerConnection.start();
//
//        // create a session
//        final Session consumerSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
//
//        // create a queue
//        final Destination consumerDestination = consumerSession.createQueue("email-queue");
//
//        // create a message consumer from the session to the queue
//        final MessageConsumer consumer = consumerSession.createConsumer(consumerDestination);
//
//        // begin to wait for messages
//        final Message consumerMessage = consumer.receive(1000);
//
//        // receive the message when it arrives
//        final TextMessage consumerTextMessage = (TextMessage) consumerMessage;
//        System.out.println("Message received: " + consumerTextMessage.getText());
//
//        // clean up the consumer
//        consumer.close();
//        consumerSession.close();
//        consumerConnection.close();
//    }

//    private static ActiveMQConnectionFactory createActiveMQConnectionFactory() {
//        final ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(ACTIVEMQ_URL);
//        connectionFactory.setUserName(ACTIVEMQ_USERNAME);
//        connectionFactory.setPassword(ACTIVEMQ_PASSWORD);
//        return connectionFactory;
//    }

//    private static JmsPoolConnectionFactory createPooledConnectionFactory(ActiveMQConnectionFactory connectionFactory) {
//        final JmsPoolConnectionFactory pooledConnectionFactory = new JmsPoolConnectionFactory();
//        pooledConnectionFactory.setConnectionFactory(connectionFactory);
//        pooledConnectionFactory.setMaxConnections(10);
//        return pooledConnectionFactory;
//    }

    @Bean
    public OperationRelayer getOperationRelayer() {
        return new OperationRelayer();
    }

    @Bean
    public CollaboratorCountNotifier getCollaboratorCountNotifier() {
        return new CollaboratorCountNotifier();
    }

    @Bean
    public CursorNotifier getCursorNotifier() {
        return new CursorNotifier();
    }
}
