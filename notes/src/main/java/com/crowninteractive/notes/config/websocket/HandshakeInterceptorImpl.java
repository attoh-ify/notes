package com.crowninteractive.notes.config.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@Component
public class HandshakeInterceptorImpl implements HandshakeInterceptor {
    private static final Logger log = LoggerFactory.getLogger(HandshakeInterceptorImpl.class);

    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) {
            return true;
        }

        HttpServletRequest httpServletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        Cookie[] cookies = httpServletRequest.getCookies();

        if (cookies == null) {
            return true;
        }

        for (Cookie cookie : cookies) {
            if (ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                attributes.put(ACCESS_TOKEN_COOKIE, cookie.getValue());
                return true;
            }
        }

        log.debug("No access_token cookie found during websocket handshake");

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {

    }
}
