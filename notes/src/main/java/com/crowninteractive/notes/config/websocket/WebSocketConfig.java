package com.crowninteractive.notes.config.websocket;

import com.crowninteractive.notes.interceptors.StompAuthInterceptor;
import com.crowninteractive.notes.services.JwtService;
import com.crowninteractive.notes.services.RedisService;
import com.crowninteractive.notes.services.impl.NotePolicyService;
import org.springframework.beans.factory.annotation.Value;
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
    private final RedisService redisService;

    public WebSocketConfig(
            NotePolicyService notePolicyService,
            JwtService jwtService,
            ApplicationContext context,
            RedisService redisService
    ) {
        this.notePolicyService = notePolicyService;
        this.jwtService = jwtService;
        this.context = context;
        this.redisService = redisService;
    }

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/relay")
                .setAllowedOrigins(frontendUrl)
                .addInterceptors(new HandshakeInterceptorImpl())
                .setHandshakeHandler(new HandshakeHandler())
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        /*
         * Development / single-instance mode:
         * This simple broker is in-memory and only works reliably when all websocket
         * clients for collaboration are connected to this same backend instance.
         *
         * Production / multi-instance mode:
         * Replace this with enableStompBrokerRelay(...) backed by an external broker
         * such as Artemis or RabbitMQ.
         */
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor(notePolicyService, jwtService, context, redisService));
    }
}