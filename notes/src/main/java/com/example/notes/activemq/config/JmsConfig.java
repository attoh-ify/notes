//package com.example.notes.activemq.config;
//
//import jakarta.jms.ConnectionFactory;
//import org.apache.activemq.ActiveMQConnectionFactory;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.jms.annotation.EnableJms;
//import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
//import org.springframework.jms.core.JmsTemplate;
//
//@Configuration
//@EnableJms
//public class JmsConfig {
//    @Bean
//    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(ConnectionFactory connectionFactory) {
//        DefaultJmsListenerContainerFactory jmsListenerContainerFactory = new DefaultJmsListenerContainerFactory();
//
//        jmsListenerContainerFactory.setConnectionFactory(connectionFactory);
//        jmsListenerContainerFactory.setConcurrency("5-10");
//
//        return jmsListenerContainerFactory;
//    }
//
//    @Bean
//    public ConnectionFactory connectionFactory() {
//        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://localhost:61616");
//        factory.setUserName("admin");
//        factory.setPassword("admin");
//        return factory;
//    };
//
//    @Bean
//    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
//        return new JmsTemplate(connectionFactory);
//    }
//}
