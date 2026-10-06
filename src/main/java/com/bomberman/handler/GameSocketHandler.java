package com.bomberman.handler;

import com.bomberman.room.RoomManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin WebSocket adapter: the first message must create or join a room, after that
 * every message is relayed untouched to the other players of the same room.
 */
public class GameSocketHandler extends TextWebSocketHandler {

    static final int SEND_TIME_LIMIT_MS = 10_000;
    static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    public static final Duration DEFAULT_HEARTBEAT_TIMEOUT = Duration.ofSeconds(45);

    private static final Logger log = LoggerFactory.getLogger(GameSocketHandler.class);

    private final RoomManager roomManager;
    private final Duration heartbeatTimeout;
    private final Clock clock;
    // Last time anything (message or pong) arrived from each session.
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    // Concurrent-safe wrappers, so every write to one session is serialized.
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public GameSocketHandler(RoomManager roomManager) {
        this(roomManager, DEFAULT_HEARTBEAT_TIMEOUT, Clock.systemUTC());
    }

    /** @param heartbeatTimeout sessions silent for this long are closed by {@link #heartbeatTick()} */
    public GameSocketHandler(RoomManager roomManager, Duration heartbeatTimeout, Clock clock) {
        this.roomManager = roomManager;
        this.heartbeatTimeout = heartbeatTimeout;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
        lastSeen.put(session.getId(), clock.instant());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        // Any inbound frame (text, binary or pong) proves the peer is alive.
        lastSeen.computeIfPresent(session.getId(), (id, t) -> clock.instant());
        super.handleMessage(session, message);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        // Liveness already recorded in handleMessage.
    }

    /** Pings every session and closes the ones that have been silent for the heartbeat timeout. */
    public void heartbeatTick() {
        Instant now = clock.instant();
        for (WebSocketSession session : sessions.values()) {
            Instant seen = lastSeen.get(session.getId());
            if (seen != null && !seen.plus(heartbeatTimeout).isAfter(now)) {
                log.debug("Closing silent session {}", session.getId());
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (Exception e) {
                    log.debug("Close of silent session {} failed: {}", session.getId(), e.toString());
                }
                cleanup(session); // a dead peer may never finish the close handshake
                continue;
            }
            try {
                session.sendMessage(new PingMessage());
            } catch (Exception e) {
                log.debug("Ping to session {} failed: {}", session.getId(), e.toString());
            }
        }
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
        lastSeen.remove(raw.getId());
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
