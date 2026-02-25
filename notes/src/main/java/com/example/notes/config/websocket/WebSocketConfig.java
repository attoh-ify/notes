package com.example.notes.config.websocket;

import com.example.notes.interceptors.StompAuthInterceptor;
import com.example.notes.services.JwtService;
import com.example.notes.services.impl.NotePolicyService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final NotePolicyService notePolicyService;
    private final JwtService jwtService;
    private final ApplicationContext context;

    public WebSocketConfig(NotePolicyService notePolicyService, JwtService jwtService, ApplicationContext context) {
        this.notePolicyService = notePolicyService;
        this.jwtService = jwtService;
        this.context = context;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/relay")
                .setAllowedOrigins("http://localhost:3000", "https://notes-ui-production-2d98.up.railway.app")
                .addInterceptors(new HandshakeInterceptorImpl())
                .setHandshakeHandler(new HandshakeHandler())
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor(notePolicyService, jwtService, context));
    }
}
