package com.bomberman.room;

import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single room. Not thread-safe on its own: all access goes through {@link RoomManager}.
 */
class Room {

    record Player(String id, String name, WebSocketSession session) {}

    private final String code;
    private final String game;
    private final int maxPlayers;
    private final Map<String, Player> players = new LinkedHashMap<>(); // keyed by session id

    Room(String code, String game, int maxPlayers) {
        this.code = code;
        this.game = game;
        this.maxPlayers = maxPlayers;
    }

    String code() { return code; }

    String game() { return game; }

    boolean isFull() { return players.size() >= maxPlayers; }

    boolean isEmpty() { return players.isEmpty(); }

    boolean hasPlayerId(String playerId) {
        return players.values().stream().anyMatch(p -> p.id().equals(playerId));
    }

    void add(Player player) { players.put(player.session().getId(), player); }

    Player remove(WebSocketSession session) { return players.remove(session.getId()); }

    List<Player> players() { return new ArrayList<>(players.values()); }

    List<Player> playersExcept(WebSocketSession session) {
        return players.values().stream()
                .filter(p -> !p.session().getId().equals(session.getId()))
                .toList();
    }
}
