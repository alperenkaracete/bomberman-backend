package com.bomberman.handler;

import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameSocketHandler extends TextWebSocketHandler {

    private final CopyOnWriteArrayList<WebSocketSession> activeSessions = new CopyOnWriteArrayList<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        activeSessions.add(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        
        // Broadcast message to all other sessions except the sender
        for (WebSocketSession activeSession : activeSessions) {
            if (!activeSession.equals(session)) {
                try {
                    activeSession.sendMessage(new TextMessage(payload));
                } catch (IOException e) {
                    // Handle send error
                    activeSessions.remove(activeSession);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        activeSessions.remove(session);
    }
}
