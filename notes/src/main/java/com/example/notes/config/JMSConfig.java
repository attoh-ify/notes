package com.example.notes.config;

import jakarta.jms.ConnectionFactory;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;

@Configuration
@EnableJms
public class JMSConfig {
    @Value("${spring.activemq.broker-url:vm://localhost?broker.persistent=false}")
    private String brokerUrl;

    @Bean
    public ConnectionFactory activeMQConnectionFactory() {
        if (brokerUrl.startsWith("amqp")) {
            return new JmsConnectionFactory(brokerUrl);
        } else {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory();
            factory.setBrokerURL(brokerUrl);
            factory.setTrustAllPackages(true);
            return factory;
        }
    }

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setPubSubDomain(false);
        factory.setConcurrency("5-20");
        return factory;
    }

    @Bean
    public JmsTemplate jmsTemplate(ActiveMQConnectionFactory connectionFactory) {
        return new JmsTemplate(connectionFactory);
    }
}
