package br.com.rbwone.web;

import java.util.concurrent.atomic.AtomicInteger;

/** Process-local tickets: delayed unlock callbacks can never target a replacement call or Activity attempt. */
public final class PhoneCallUnlockGate {
    private static final AtomicInteger NEXT_REQUEST_CODE = new AtomicInteger(501);
    public static final class Attempt {
        public final String owner, nonce, action;
        public final long generation;
        public final int requestCode;
        private Attempt(String owner, String nonce, String action, long generation, int requestCode) {
            this.owner = owner; this.nonce = nonce; this.action = action; this.generation = generation; this.requestCode = requestCode;
        }
    }
    private long generation;
    private Attempt active;
    public Attempt begin(String owner, String nonce, String action) {
        if (active != null || owner == null || nonce == null || owner.isEmpty() || nonce.isEmpty()
                || !("answer".equals(action) || "reject".equals(action))) return null;
        int code;
        do {
            code = NEXT_REQUEST_CODE.get();
            // Freeze at the exhausted sentinel: never wrap and reuse an old Activity result code.
            if (code < 501 || code > 0xffff) return null;
        } while (!NEXT_REQUEST_CODE.compareAndSet(code, code + 1));
        active = new Attempt(owner, nonce, action, ++generation, code);
        return active;
    }
    public boolean isCurrent(Attempt attempt, String owner, String nonce) {
        return attempt != null && active == attempt && generation == attempt.generation
                && attempt.owner.equals(owner) && attempt.nonce.equals(nonce);
    }
    public Attempt forRequestCode(int requestCode, String owner, String nonce) {
        return isCurrent(active, owner, nonce) && active.requestCode == requestCode ? active : null;
    }
    public boolean hasAttempt() { return active != null; }
    public void invalidate() { active = null; generation++; }
}
