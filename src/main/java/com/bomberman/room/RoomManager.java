package com.bomberman.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Game-agnostic room registry. It only manages rooms and forwards messages verbatim;
 * it knows nothing about game rules.
 */
public class RoomManager {

    public static final String ROOM_NOT_FOUND = "ROOM_NOT_FOUND";
    public static final String ROOM_FULL = "ROOM_FULL";
    public static final String NOT_IN_ROOM = "NOT_IN_ROOM";
    public static final String BAD_REQUEST = "BAD_REQUEST";

    /** Close status sent to a session whose player id was taken over by a newer session. */
    public static final CloseStatus REPLACED = new CloseStatus(4000, "replaced");

    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I
    static final int CODE_LENGTH = 6;
    static final int MIN_PLAYERS = 2;
    static final int MAX_PLAYERS = 8;
    static final int DEFAULT_MAX_PLAYERS = 4;
    static final int MAX_FIELD_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Room> rooms = new HashMap<>();           // code -> room
    private final Map<String, Room> roomBySession = new HashMap<>();   // session id -> room

    /** @param maxPlayers requested capacity, or null for the default */
    public void createRoom(WebSocketSession session, String playerId, String name, String game, Integer maxPlayers) {
        int capacity = maxPlayers == null ? DEFAULT_MAX_PLAYERS : maxPlayers;
        if (!validText(playerId) || !validText(game) || capacity < MIN_PLAYERS || capacity > MAX_PLAYERS) {
            sendError(session, BAD_REQUEST);
            return;
        }
        String code;
        synchronized (this) {
            if (roomBySession.containsKey(session.getId())) {
                code = null;
            } else {
                code = newCode();
                Room room = new Room(code, game, capacity);
                room.add(new Room.Player(playerId, safeName(name), session));
                rooms.put(code, room);
                roomBySession.put(session.getId(), room);
            }
        }
        if (code == null) {
            sendError(session, BAD_REQUEST);
            return;
        }
        ObjectNode msg = mapper.createObjectNode().put("type", "room_created").put("room", code);
        send(session, msg.toString());
    }

    public void joinRoom(WebSocketSession session, String code, String playerId, String name) {
        if (!validText(playerId) || code == null) {
            sendError(session, BAD_REQUEST);
            return;
        }
        String normalized = code.trim().toUpperCase();
        String error = null;
        Room room;
        String displayName = safeName(name);
        List<Room.Player> others = List.of();
        List<Room.Player> all = List.of();
        WebSocketSession replaced = null;
        synchronized (this) {
            room = rooms.get(normalized);
            if (roomBySession.containsKey(session.getId())) {
                error = BAD_REQUEST;
            } else if (room == null) {
                error = ROOM_NOT_FOUND;
            } else if (room.hasPlayerId(playerId)) {
                // Same id as an existing player: the new session takes over its slot.
                Room.Player old = room.findById(playerId);
                room.replace(old, new Room.Player(playerId, displayName, session));
                roomBySession.remove(old.session().getId());
                roomBySession.put(session.getId(), room);
                replaced = old.session();
                others = room.playersExcept(session);
                all = room.players();
            } else if (room.isFull()) {
                error = ROOM_FULL;
            } else {
                others = room.players();
                room.add(new Room.Player(playerId, displayName, session));
                roomBySession.put(session.getId(), room);
                all = room.players();
            }
        }
        if (error != null) {
            sendError(session, error);
            return;
        }
        ObjectNode joined = mapper.createObjectNode()
                .put("type", "room_joined").put("room", room.code()).put("game", room.game());
        ArrayNode list = joined.putArray("players");
        for (Room.Player p : all) {
            list.addObject().put("id", p.id()).put("name", p.name());
        }
        send(session, joined.toString());
        if (replaced != null) {
            closeQuietly(replaced);
        }

        String announce = mapper.createObjectNode()
                .put("type", "player_joined").put("id", playerId).put("name", displayName).toString();
        for (Room.Player p : others) {
            send(p.session(), announce);
        }
    }

    /** Forwards the payload unchanged to the other players in the sender's room. */
    public void relay(WebSocketSession session, String payload) {
        List<Room.Player> targets;
        synchronized (this) {
            Room room = roomBySession.get(session.getId());
            if (room == null) {
                targets = null;
            } else {
                targets = room.playersExcept(session);
            }
        }
        if (targets == null) {
            sendError(session, NOT_IN_ROOM);
            return;
        }
        for (Room.Player p : targets) {
            send(p.session(), payload);
        }
    }

    public void leave(WebSocketSession session) {
        Room.Player left;
        List<Room.Player> remaining = List.of();
        synchronized (this) {
            Room room = roomBySession.remove(session.getId());
            if (room == null) {
                return;
            }
            left = room.remove(session);
            if (room.isEmpty()) {
                rooms.remove(room.code());
            } else {
                remaining = room.players();
            }
        }
        if (left == null) {
            return;
        }
        String msg = mapper.createObjectNode().put("type", "player_disconnect").put("id", left.id()).toString();
        for (Room.Player p : remaining) {
            send(p.session(), msg);
        }
    }

    public synchronized boolean isInRoom(WebSocketSession session) {
        return roomBySession.containsKey(session.getId());
    }

    synchronized int roomCount() { return rooms.size(); }

    synchronized boolean roomExists(String code) { return rooms.containsKey(code); }

    public void sendError(WebSocketSession session, String code) {
        send(session, mapper.createObjectNode().put("type", "error").put("code", code).toString());
    }

    // Caller must hold the lock.
    private String newCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    private static boolean validText(String s) {
        return s != null && !s.isBlank() && s.length() <= MAX_FIELD_LENGTH;
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "Player";
        }
        return name.length() > MAX_FIELD_LENGTH ? name.substring(0, MAX_FIELD_LENGTH) : name;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(REPLACED);
        } catch (IOException | RuntimeException e) {
            log.debug("Closing replaced session {} failed: {}", session.getId(), e.toString());
        }
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException | RuntimeException e) {
            // A broken peer is cleaned up by its own close/transport-error callback.
            log.debug("Send to session {} failed: {}", session.getId(), e.toString());
        }
    }
}
