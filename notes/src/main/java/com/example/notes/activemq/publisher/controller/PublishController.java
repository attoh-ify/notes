//package com.example.notes.activemq.publisher.controller;
//
//import com.example.notes.activemq.model.SystemMessage;
//import jakarta.jms.ConnectionFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.context.annotation.Bean;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.jms.core.JmsTemplate;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//public class PublishController {
//    private final JmsTemplate jmsTemplate;
//
//    public PublishController(JmsTemplate jmsTemplate) {
//        this.jmsTemplate = jmsTemplate;
//    }
//
//    @PostMapping("/sendEmail")
//    public ResponseEntity<String> sendEmail(@RequestBody SystemMessage message){
//        try {
//            jmsTemplate.convertAndSend("email-queue", message);
//            return new ResponseEntity<>("Sent", HttpStatus.OK);
//        } catch (Exception e) {
//            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
//        }
//    }
//}
