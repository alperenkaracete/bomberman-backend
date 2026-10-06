package com.bomberman.handler;

import com.bomberman.room.RoomManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GameSocketHandlerTest {

    private static class FakeClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static class Client {
        final WebSocketSession session = mock(WebSocketSession.class);
        final List<WebSocketMessage<?>> received = new ArrayList<>();

        Client(String id) throws Exception {
            when(session.getId()).thenReturn(id);
            when(session.isOpen()).thenReturn(true);
            doAnswer(inv -> {
                received.add(inv.getArgument(0));
                return null;
            }).when(session).sendMessage(any());
        }

        long count(Class<?> type) { return received.stream().filter(type::isInstance).count(); }

        boolean sawText(String fragment) {
            return received.stream().anyMatch(m -> m instanceof TextMessage t && t.getPayload().contains(fragment));
        }
    }

    private FakeClock clock;
    private GameSocketHandler handler;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        handler = new GameSocketHandler(new RoomManager(Duration.ofSeconds(120), clock), Duration.ofSeconds(45), clock);
    }

    private void text(Client c, String json) throws Exception {
        handler.handleMessage(c.session, new TextMessage(json));
    }

    @Test
    void tickSendsPingToEveryConnection() throws Exception {
        Client a = new Client("a");
        Client b = new Client("b");
        handler.afterConnectionEstablished(a.session);
        handler.afterConnectionEstablished(b.session);

        handler.heartbeatTick();

        assertEquals(1, a.count(PingMessage.class));
        assertEquals(1, b.count(PingMessage.class));
    }

    @Test
    void silentSessionIsClosedAndPeersGetPlayerDisconnect() throws Exception {
        Client host = new Client("a");
        Client guest = new Client("b");
        handler.afterConnectionEstablished(host.session);
        handler.afterConnectionEstablished(guest.session);
        text(host, "{\"type\":\"create_room\",\"id\":\"p-a\",\"game\":\"parti\"}");
        String code = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(((TextMessage) host.received.get(0)).getPayload()).get("room").asText();
        text(guest, "{\"type\":\"join_room\",\"room\":\"" + code + "\",\"id\":\"p-b\"}");

        // host keeps answering with pongs, guest goes silent
        clock.advance(Duration.ofSeconds(30));
        handler.heartbeatTick();
        handler.handleMessage(host.session, new PongMessage());
        verify(guest.session, never()).close(any(CloseStatus.class));

        clock.advance(Duration.ofSeconds(15)); // guest silent for 45s
        handler.heartbeatTick();

        verify(guest.session).close(any(CloseStatus.class));
        verify(host.session, never()).close(any(CloseStatus.class));
        assertTrue(host.sawText("\"player_disconnect\""));
        assertTrue(host.sawText("p-b"));
    }

    @Test
    void anyMessageKeepsSessionAlive() throws Exception {
        Client c = new Client("a");
        handler.afterConnectionEstablished(c.session);

        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(40));
            text(c, "{\"type\":\"nope\"}"); // answered with an error, still counts as activity
            handler.heartbeatTick();
        }

        verify(c.session, never()).close(any(CloseStatus.class));
    }

    @Test
    void closedSessionIsNotPingedAgain() throws Exception {
        Client c = new Client("a");
        handler.afterConnectionEstablished(c.session);
        handler.afterConnectionClosed(c.session, CloseStatus.NORMAL);

        handler.heartbeatTick();

        assertEquals(0, c.count(PingMessage.class));
    }
}
