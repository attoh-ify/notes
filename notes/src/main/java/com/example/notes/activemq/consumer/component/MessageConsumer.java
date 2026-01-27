//package com.example.notes.activemq.consumer.component;
//
//import com.example.notes.activemq.model.SystemMessage;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.jms.annotation.JmsListener;
//import org.springframework.stereotype.Component;
//
//@Component
//public class MessageConsumer {
//    private static final Logger LOGGER = LoggerFactory.getLogger(MessageConsumer.class);
//
//    @JmsListener(destination = "email-queue")
//    public void messageListener(SystemMessage message) {
//        LOGGER.info("Message received: {}",  message);
//    }
//}
