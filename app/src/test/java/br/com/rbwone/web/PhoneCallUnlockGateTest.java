package br.com.rbwone.web;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real unlock-ticket regression tests; no fake Android callback implementation. */
public final class PhoneCallUnlockGateTest {
    private static final String OWNER_A = "owner-session-00000001";
    private static final String OWNER_B = "owner-session-00000002";
    private static final String NONCE_A = "nonce-original-a";
    private static final String NONCE_B = "nonce-original-b";

    @Test public void beginCapturesTargetAndActionWithoutDispatchingOrAllowingAnotherGesture() {
        PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
        PhoneCallUnlockGate.Attempt answer = gate.begin(OWNER_A, NONCE_A, "answer");
        assertNotNull(answer); assertTrue(gate.hasAttempt());
        assertEquals(OWNER_A, answer.owner); assertEquals(NONCE_A, answer.nonce); assertEquals("answer", answer.action);
        assertTrue(answer.requestCode >= 501 && answer.requestCode <= 0xffff);
        assertTrue(gate.isCurrent(answer, OWNER_A, NONCE_A));
        assertSame(answer, gate.forRequestCode(answer.requestCode, OWNER_A, NONCE_A));
        assertNull(gate.begin(OWNER_A, NONCE_A, "reject"));
        assertNull(gate.begin(OWNER_B, NONCE_B, "answer"));
        assertSame(answer, gate.forRequestCode(answer.requestCode, OWNER_A, NONCE_A));
        assertEquals("answer", answer.action);
    }

    @Test public void successOrCancelTicketFromACannotActOnOrCancelB() {
        PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
        PhoneCallUnlockGate.Attempt old = gate.begin(OWNER_A, NONCE_A, "answer");
        // This is the onNewIntent boundary: invalidate before accepting any replacement target.
        gate.invalidate();
        PhoneCallUnlockGate.Attempt replacement = gate.begin(OWNER_B, NONCE_B, "reject");
        assertFalse(gate.isCurrent(old, OWNER_B, NONCE_B));
        assertFalse(gate.isCurrent(old, OWNER_A, NONCE_A));
        assertNull(gate.forRequestCode(old.requestCode, OWNER_B, NONCE_B));
        assertTrue(gate.hasAttempt());
        assertTrue(gate.isCurrent(replacement, OWNER_B, NONCE_B));
        assertSame(replacement, gate.forRequestCode(replacement.requestCode, OWNER_B, NONCE_B));
        assertEquals("answer", old.action); assertEquals("reject", replacement.action);
        assertEquals(OWNER_A, old.owner); assertEquals(NONCE_A, old.nonce);
    }

    @Test public void returningFromBToTheSameOwnerAndNonceADoesNotReviveTheOldCallback() {
        PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
        PhoneCallUnlockGate.Attempt firstA = gate.begin(OWNER_A, NONCE_A, "answer");
        gate.invalidate();
        PhoneCallUnlockGate.Attempt b = gate.begin(OWNER_B, NONCE_B, "reject");
        gate.invalidate();
        PhoneCallUnlockGate.Attempt secondA = gate.begin(OWNER_A, NONCE_A, "reject");
        assertTrue(secondA.generation > firstA.generation);
        assertFalse(gate.isCurrent(firstA, OWNER_A, NONCE_A));
        assertFalse(gate.isCurrent(b, OWNER_A, NONCE_A));
        assertNull(gate.forRequestCode(firstA.requestCode, OWNER_A, NONCE_A));
        assertTrue(gate.isCurrent(secondA, OWNER_A, NONCE_A));
        assertNotEquals(firstA.requestCode, secondA.requestCode);
        assertEquals("reject", secondA.action);
    }

    @Test public void cancelledAttemptAndItsCredentialResultCannotReviveAfterRetry() {
        PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
        PhoneCallUnlockGate.Attempt cancelled = gate.begin(OWNER_A, NONCE_A, "reject");
        gate.invalidate();
        assertFalse(gate.hasAttempt());
        assertFalse(gate.isCurrent(cancelled, OWNER_A, NONCE_A));
        assertNull(gate.forRequestCode(cancelled.requestCode, OWNER_A, NONCE_A));
        PhoneCallUnlockGate.Attempt retry = gate.begin(OWNER_A, NONCE_A, "answer");
        assertNotEquals(cancelled.requestCode, retry.requestCode);
        assertNull(gate.forRequestCode(cancelled.requestCode, OWNER_A, NONCE_A));
        assertSame(retry, gate.forRequestCode(retry.requestCode, OWNER_A, NONCE_A));
        assertEquals("reject", cancelled.action); assertEquals("answer", retry.action);
    }

    @Test public void activityRecreationCannotConsumePriorCredentialResultOrTicket() {
        PhoneCallUnlockGate before = new PhoneCallUnlockGate();
        PhoneCallUnlockGate.Attempt old = before.begin(OWNER_A, NONCE_A, "answer");
        before.invalidate();
        PhoneCallUnlockGate after = new PhoneCallUnlockGate();
        assertNull(after.forRequestCode(old.requestCode, OWNER_A, NONCE_A));
        assertFalse(after.isCurrent(old, OWNER_A, NONCE_A));
        PhoneCallUnlockGate.Attempt current = after.begin(OWNER_A, NONCE_A, "answer");
        assertNotEquals(old.requestCode, current.requestCode);
        assertFalse(after.isCurrent(old, OWNER_A, NONCE_A));
        assertNull(after.forRequestCode(old.requestCode, OWNER_A, NONCE_A));
        assertTrue(after.isCurrent(current, OWNER_A, NONCE_A));
    }

    @Test public void ownerNonceAndRequestCodeAllMustMatchAndInvalidChecksDoNotConsumeB() {
        PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
        PhoneCallUnlockGate.Attempt current = gate.begin(OWNER_B, NONCE_B, "reject");
        assertFalse(gate.isCurrent(current, OWNER_A, NONCE_B));
        assertFalse(gate.isCurrent(current, OWNER_B, NONCE_A));
        assertFalse(gate.isCurrent(null, OWNER_B, NONCE_B));
        assertFalse(gate.isCurrent(current, null, null));
        assertNull(gate.forRequestCode(current.requestCode + 1, OWNER_B, NONCE_B));
        assertNull(gate.forRequestCode(current.requestCode, OWNER_A, NONCE_B));
        assertNull(gate.forRequestCode(current.requestCode, OWNER_B, NONCE_A));
        assertTrue(gate.hasAttempt());
        assertSame(current, gate.forRequestCode(current.requestCode, OWNER_B, NONCE_B));
    }

    @Test public void invalidActionsOrMissingIdentityNeverProduceAnAttempt() {
        for (String action : new String[]{null, "open", "dial", "video", "Ligar", "Answer", ""}) {
            PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
            assertNull(gate.begin(OWNER_A, NONCE_A, action));
            assertFalse(gate.hasAttempt());
        }
        PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
        assertNull(gate.begin(null, NONCE_A, "answer"));
        assertNull(gate.begin(OWNER_A, null, "answer"));
        assertNull(gate.begin("", NONCE_A, "reject"));
        assertNull(gate.begin(OWNER_A, "", "reject"));
        assertFalse(gate.hasAttempt());
    }

    @Test public void requestCodesStayDistinctAcrossMultipleActivitiesAndAttempts() {
        Set<Integer> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
            PhoneCallUnlockGate.Attempt first = gate.begin(OWNER_A, NONCE_A, "answer");
            assertTrue(codes.add(first.requestCode));
            gate.invalidate();
            PhoneCallUnlockGate.Attempt next = gate.begin(OWNER_A, NONCE_A, "reject");
            assertTrue(codes.add(next.requestCode));
            assertTrue(next.requestCode <= 0xffff);
            assertNull(gate.forRequestCode(first.requestCode, OWNER_A, NONCE_A));
        }
    }

    private static PhoneCallRequest event(String owner, String state) throws Exception {
        return PhoneCallRequest.parse("{\"requestId\":\"unlock-integration\",\"action\":\"sync\",\"payload\":{\"ownerKey\":\""
                + owner + "\",\"callId\":\"call-a\",\"state\":\"" + state + "\"}}");
    }

    @Test public void originalCallExpiringDuringUnlockCannotProduceAnAction() throws Exception {
        PhoneCallState state = new PhoneCallState(); state.sync(event(OWNER_A, "ringing"), 0);
        PhoneCallState.Call call = state.current(0);
        PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
        PhoneCallUnlockGate.Attempt attempt = gate.begin(call.ownerKey, call.nonce, "answer");
        assertSame(call, state.peek(attempt.owner, attempt.nonce, call.expiresAt - 1));
        assertTrue(gate.isCurrent(attempt, OWNER_A, call.nonce));
        assertNull(state.peek(attempt.owner, attempt.nonce, call.expiresAt));
        assertNull(state.respond(attempt.nonce, attempt.action, call.expiresAt));
    }

    @Test public void callEndingBeforeUnlockCannotBeAnsweredEvenIfTheTicketHasNotBeenDestroyedYet() throws Exception {
        PhoneCallState state = new PhoneCallState(); state.sync(event(OWNER_A, "ringing"), 0);
        PhoneCallState.Call call = state.current(0);
        PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
        PhoneCallUnlockGate.Attempt attempt = gate.begin(call.ownerKey, call.nonce, "answer");
        state.sync(event(OWNER_A, "ended"), 1);
        assertTrue(gate.isCurrent(attempt, OWNER_A, call.nonce));
        assertNull(state.peek(attempt.owner, attempt.nonce, 2));
        assertNull(state.respond(attempt.nonce, attempt.action, 2));
    }

    @Test public void newOwnerOfTheSameCallIdCannotReceiveTheActionCapturedBeforeUnlock() throws Exception {
        PhoneCallState state = new PhoneCallState(); state.sync(event(OWNER_A, "ringing"), 0);
        PhoneCallState.Call old = state.current(0);
        PhoneCallUnlockGate gate = new PhoneCallUnlockGate();
        PhoneCallUnlockGate.Attempt attempt = gate.begin(old.ownerKey, old.nonce, "reject");
        state.sync(event(OWNER_B, "ringing"), 1);
        PhoneCallState.Call current = state.current(1);
        assertFalse(gate.isCurrent(attempt, current.ownerKey, current.nonce));
        assertNull(state.peek(attempt.owner, attempt.nonce, 2));
        assertNull(state.respond(attempt.nonce, attempt.action, 2));
        assertSame(current, state.current(2));
    }
}
