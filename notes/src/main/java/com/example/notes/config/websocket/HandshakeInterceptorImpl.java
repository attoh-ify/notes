package com.example.notes.config.websocket;

import com.example.notes.shared.document_store.NoteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@Component
public class HandshakeInterceptorImpl implements HandshakeInterceptor {
    private final NoteStore documentStore;

    private static final Logger log = LoggerFactory.getLogger(HandshakeInterceptorImpl.class);

    public HandshakeInterceptorImpl(NoteStore documentStore) {
        this.documentStore = documentStore;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
//        var uri = request.getURI().toString();
//        var noteId = extractNoteId(uri);
//        var hasDoc = documentStore.hasDocument(noteId);
//        System.out.println("Document: " + documentStore.getNoteFromNoteId(noteId));
//        if (!hasDoc) {
//            response.setStatusCode(HttpStatus.NOT_FOUND);
//            response.close();
//            return false;
//        } else {
//            return true;
//        }
        if (request instanceof ServletServerHttpRequest servletRequest) {
            var httpServletRequest = servletRequest.getServletRequest();

            if (httpServletRequest.getCookies() != null) {
                for (var cookie : httpServletRequest.getCookies()) {
                    attributes.put(cookie.getName(), cookie.getValue());
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {

    }

    private UUID extractNoteId(String destination) {
        try {
            URI uri = new URI(destination);
            String query = uri.getQuery();

            if (query == null) return null;

            String noteId = Arrays.stream(query.split("&"))
                    .map(param -> param.split("=", 2))
                    .filter(pair -> pair.length == 2 && pair[0].equals("noteId"))
                    .map(pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
                    .findFirst()
                    .orElse(null);

            if (noteId == null) return null;

            return UUID.fromString(noteId);
        } catch (Exception e) {
            log.error("Invalid or missing noteId");
            throw new IllegalArgumentException("Invalid or missing noteId");
        }
    }
}
