package com.bomberman.handler;

import com.bomberman.room.RoomManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin WebSocket adapter: the first message must create or join a room, after that
 * every message is relayed untouched to the other players of the same room.
 */
public class GameSocketHandler extends TextWebSocketHandler {

    static final int SEND_TIME_LIMIT_MS = 10_000;
    static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final RoomManager roomManager;
    private final ObjectMapper mapper = new ObjectMapper();
    // Concurrent-safe wrappers, so every write to one session is serialized.
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public GameSocketHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        WebSocketSession session = sessions.get(raw.getId());
        if (session == null) {
            return;
        }
        String payload = message.getPayload();
        if (roomManager.isInRoom(session)) {
            roomManager.relay(session, payload);
            return;
        }

        JsonNode json;
        try {
            json = mapper.readTree(payload);
        } catch (Exception e) {
            roomManager.sendError(session, RoomManager.BAD_REQUEST);
            return;
        }
        if (json == null || !json.isObject()) {
            roomManager.sendError(session, RoomManager.BAD_REQUEST);
            return;
        }
        switch (text(json, "type") == null ? "" : text(json, "type")) {
            case "create_room" -> {
                JsonNode max = json.get("maxPlayers");
                Integer maxPlayers = null;
                if (max != null && !max.isNull()) {
                    if (!max.isInt()) {
                        roomManager.sendError(session, RoomManager.BAD_REQUEST);
                        return;
                    }
                    maxPlayers = max.asInt();
                }
                roomManager.createRoom(session, text(json, "id"), text(json, "name"), text(json, "game"), maxPlayers);
            }
            case "join_room" -> roomManager.joinRoom(session, text(json, "room"), text(json, "id"), text(json, "name"));
            default -> roomManager.sendError(session, RoomManager.NOT_IN_ROOM);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        cleanup(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session);
    }

    private void cleanup(WebSocketSession raw) {
        WebSocketSession session = sessions.remove(raw.getId());
        if (session != null) {
            roomManager.leave(session);
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode n = json.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }
}
