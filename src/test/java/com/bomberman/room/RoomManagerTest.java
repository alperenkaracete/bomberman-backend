package com.bomberman.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mock session that records every payload sent to it. */
    private static class Client {
        final WebSocketSession session = mock(WebSocketSession.class);
        final List<String> received = new ArrayList<>();

        Client(String id) throws Exception {
            when(session.getId()).thenReturn(id);
            when(session.isOpen()).thenReturn(true);
            org.mockito.Mockito.doAnswer(inv -> {
                received.add(inv.<TextMessage>getArgument(0).getPayload());
                return null;
            }).when(session).sendMessage(any(TextMessage.class));
        }

        JsonNode last() throws Exception {
            return MAPPER.readTree(received.get(received.size() - 1));
        }
    }

    /** Clock the tests can move forward. */
    private static class FakeClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private FakeClock clock;
    private RoomManager manager;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        manager = new RoomManager(Duration.ofSeconds(120), clock);
    }

    private String createRoom(Client host) throws Exception {
        manager.createRoom(host.session, "p-" + host.session.getId(), "Host", "bomberman", 4);
        return host.last().get("room").asText();
    }

    @Test
    void createRoomReturnsValidUniqueCode() throws Exception {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Client c = new Client("s" + i);
            String code = createRoom(c);
            assertEquals("room_created", c.last().get("type").asText());
            assertTrue(code.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}"), code);
            assertTrue(codes.add(code), "duplicate code " + code);
        }
        assertEquals(200, manager.roomCount());
    }

    @Test
    void joinRoomSendsRoomJoinedAndNotifiesOthers() throws Exception {
        Client host = new Client("a");
        Client guest = new Client("b");
        String code = createRoom(host);

        manager.joinRoom(guest.session, code, "p-b", "Guest");

        JsonNode joined = guest.last();
        assertEquals("room_joined", joined.get("type").asText());
        assertEquals(code, joined.get("room").asText());
        assertEquals("bomberman", joined.get("game").asText());
        assertEquals(2, joined.get("players").size());
        assertEquals("p-a", joined.get("players").get(0).get("id").asText());
        assertEquals("Host", joined.get("players").get(0).get("name").asText());

        JsonNode announce = host.last();
        assertEquals("player_joined", announce.get("type").asText());
        assertEquals("p-b", announce.get("id").asText());
        assertEquals("Guest", announce.get("name").asText());
    }

    @Test
    void joinAcceptsLowercaseCode() throws Exception {
        Client host = new Client("a");
        Client guest = new Client("b");
        String code = createRoom(host);
        manager.joinRoom(guest.session, code.toLowerCase(), "p-b", "Guest");
        assertEquals("room_joined", guest.last().get("type").asText());
    }

    @Test
    void fullRoomIsRejected() throws Exception {
        Client host = new Client("a");
        manager.createRoom(host.session, "p-a", "Host", "bomberman", 2);
        String code = host.last().get("room").asText();
        Client b = new Client("b");
        Client c = new Client("c");

        manager.joinRoom(b.session, code, "p-b", "B");
        manager.joinRoom(c.session, code, "p-c", "C");

        assertEquals("error", c.last().get("type").asText());
        assertEquals("ROOM_FULL", c.last().get("code").asText());
        assertFalse(manager.isInRoom(c.session));
    }

    @Test
    void unknownRoomIsRejected() throws Exception {
        Client c = new Client("c");
        manager.joinRoom(c.session, "ZZZZZZ", "p-c", "C");
        assertEquals("ROOM_NOT_FOUND", c.last().get("code").asText());
    }

    @Test
    void invalidRequestsAreBadRequest() throws Exception {
        Client c = new Client("c");
        manager.createRoom(c.session, null, "n", "bomberman", 4);
        assertEquals("BAD_REQUEST", c.last().get("code").asText());
        manager.createRoom(c.session, "id", "n", "bomberman", 99);
        assertEquals("BAD_REQUEST", c.last().get("code").asText());
        manager.joinRoom(c.session, null, "id", "n");
        assertEquals("BAD_REQUEST", c.last().get("code").asText());
        assertEquals(0, manager.roomCount());
    }

    @Test
    void leavingNotifiesRemainingAndEmptyRoomIsDeletedAfterGracePeriod() throws Exception {
        Client host = new Client("a");
        Client guest = new Client("b");
        String code = createRoom(host);
        manager.joinRoom(guest.session, code, "p-b", "Guest");

        manager.leave(guest.session);
        JsonNode msg = host.last();
        assertEquals("player_disconnect", msg.get("type").asText());
        assertEquals("p-b", msg.get("id").asText());
        assertTrue(manager.roomExists(code));

        manager.leave(host.session);
        assertTrue(manager.roomExists(code), "kept during the grace period");
        clock.advance(Duration.ofSeconds(120));
        manager.sweepExpired();
        assertFalse(manager.roomExists(code));
        assertEquals(0, manager.roomCount());

        Client late = new Client("c");
        manager.joinRoom(late.session, code, "p-c", "C");
        assertEquals("ROOM_NOT_FOUND", late.last().get("code").asText());
    }

    @Test
    void relayGoesUnchangedToSameRoomOnly() throws Exception {
        Client a1 = new Client("a1");
        Client a2 = new Client("a2");
        Client b1 = new Client("b1");
        Client b2 = new Client("b2");
        String codeA = createRoom(a1);
        String codeB = createRoom(b1);
        manager.joinRoom(a2.session, codeA, "p-a2", "A2");
        manager.joinRoom(b2.session, codeB, "p-b2", "B2");
        int a1Before = a1.received.size();
        int b1Before = b1.received.size();
        int b2Before = b2.received.size();

        String payload = "{\"type\":\"move\",\"id\":\"p-a1\",\"x\":3,  \"y\":4}";
        manager.relay(a1.session, payload);

        assertEquals(payload, a2.received.get(a2.received.size() - 1));
        assertEquals(a1Before, a1.received.size(), "sender must not get its own message");
        assertEquals(b1Before, b1.received.size());
        assertEquals(b2Before, b2.received.size());
    }

    @Test
    void relayWithoutRoomIsNotInRoom() throws Exception {
        Client c = new Client("c");
        manager.relay(c.session, "{\"type\":\"move\"}");
        assertEquals("NOT_IN_ROOM", c.last().get("code").asText());
    }

    @Test
    void sameIdTakesOverSlotKeepingOrderWithoutDisconnect() throws Exception {
        Client host = new Client("a");
        Client mid = new Client("b");
        Client last = new Client("c");
        String code = createRoom(host);
        manager.joinRoom(mid.session, code, "p-b", "B");
        manager.joinRoom(last.session, code, "p-c", "C");
        int hostBefore = host.received.size();
        int lastBefore = last.received.size();

        Client fresh = new Client("b2");
        manager.joinRoom(fresh.session, code, "p-b", "B");

        JsonNode joined = fresh.last();
        assertEquals("room_joined", joined.get("type").asText());
        assertEquals(3, joined.get("players").size());
        assertEquals("p-a", joined.get("players").get(0).get("id").asText());
        assertEquals("p-b", joined.get("players").get(1).get("id").asText());
        assertEquals("p-c", joined.get("players").get(2).get("id").asText());

        for (Client other : List.of(host, last)) {
            int before = other == host ? hostBefore : lastBefore;
            assertEquals(before + 1, other.received.size());
            assertEquals("player_joined", other.last().get("type").asText());
            assertEquals("p-b", other.last().get("id").asText());
        }
        verify(mid.session).close(RoomManager.REPLACED);
        assertTrue(mid.received.stream().noneMatch(m -> m.contains("player_disconnect")));
        assertFalse(manager.isInRoom(mid.session));
        assertTrue(manager.isInRoom(fresh.session));
    }

    @Test
    void oldSessionCloseDoesNotRemoveTakeoverSession() throws Exception {
        Client host = new Client("a");
        Client old = new Client("b");
        String code = createRoom(host);
        manager.joinRoom(old.session, code, "p-b", "B");
        Client fresh = new Client("b2");
        manager.joinRoom(fresh.session, code, "p-b", "B");
        int hostBefore = host.received.size();

        manager.leave(old.session); // what afterConnectionClosed of the old socket does

        assertEquals(hostBefore, host.received.size(), "no player_disconnect expected");
        assertTrue(manager.isInRoom(fresh.session));
        manager.relay(host.session, "{\"type\":\"move\"}");
        assertEquals("{\"type\":\"move\"}", fresh.last().toString());
    }

    @Test
    void sameIdInAnotherRoomIsUntouched() throws Exception {
        Client a = new Client("a");
        Client b = new Client("b");
        String codeA = createRoom(a);
        String codeB = createRoom(b);
        Client fresh = new Client("f");
        manager.joinRoom(fresh.session, codeB, "p-a", "Other");

        assertEquals("room_joined", fresh.last().get("type").asText());
        assertEquals(2, fresh.last().get("players").size());
        assertTrue(manager.isInRoom(a.session));
        assertNotEquals(codeA, codeB);
    }

    @Test
    void emptyRoomSurvivesGracePeriodAndRejoinKeepsCodeAndCapacity() throws Exception {
        Client host = new Client("a");
        manager.createRoom(host.session, "p-a", "Host", "bomberman", 2);
        String code = host.last().get("room").asText();
        manager.leave(host.session);

        clock.advance(Duration.ofSeconds(119));
        manager.sweepExpired();
        assertTrue(manager.roomExists(code));

        Client back = new Client("a2");
        manager.joinRoom(back.session, code, "p-a", "Host");
        assertEquals("room_joined", back.last().get("type").asText());
        assertEquals(code, back.last().get("room").asText());
        assertEquals(1, back.last().get("players").size());

        // returning player cancels the deletion timer
        clock.advance(Duration.ofSeconds(500));
        manager.sweepExpired();
        assertTrue(manager.roomExists(code));

        Client b = new Client("b");
        Client c = new Client("c");
        manager.joinRoom(b.session, code, "p-b", "B");
        manager.joinRoom(c.session, code, "p-c", "C");
        assertEquals("ROOM_FULL", c.last().get("code").asText()); // capacity 2 unchanged
    }

    @Test
    void emptyRoomIsDeletedWhenGracePeriodEnds() throws Exception {
        Client host = new Client("a");
        String code = createRoom(host);
        manager.leave(host.session);

        clock.advance(Duration.ofSeconds(120));
        Client late = new Client("late");
        manager.joinRoom(late.session, code, "p-a", "Host"); // lazy expiry even before a sweep
        assertEquals("ROOM_NOT_FOUND", late.last().get("code").asText());
        assertEquals(0, manager.roomCount());
    }
}
