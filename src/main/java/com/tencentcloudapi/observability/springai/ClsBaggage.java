package com.tencentcloudapi.observability.springai;

/**
 * Thread-local baggage for CLS GenAI Trace identity fields that Spring AI itself
 * cannot know — {@code session.id} and {@code user.id}. Users set these in the
 * HTTP controller / message consumer layer before invoking any Spring AI
 * {@code ChatClient}, and {@link ClsObservationHandler} reads them when
 * building the trace's {@code entry}/{@code agent} spans.
 *
 * <p><b>Why not use OpenTelemetry Baggage directly?</b> This SDK ships a shaded
 * OTel SDK, so {@code io.opentelemetry.*} inside the jar is actually
 * {@code com.tencentcloudapi.observability.shaded.io.opentelemetry.*}. Users
 * cannot reasonably import that shaded class from their own code. A plain
 * {@link ThreadLocal} keeps the user API ergonomic and shade-free.
 *
 * <p><b>Reactor / async flows.</b> For streaming {@code Flux<ChatResponse>}, the
 * baggage is captured at {@link ClsObservationHandler#onStart} time (which runs
 * on the subscribing thread, before Reactor hops threads), so the trace's
 * identity fields are frozen correctly at span creation. The user does
 * <i>not</i> need to propagate this ThreadLocal across Reactor operators.
 *
 * <pre>{@code
 * @RestController
 * public class ChatController {
 *     @PostMapping("/chat")
 *     public String chat(@RequestHeader("X-Session-Id") String sessionId,
 *                        @RequestHeader("X-User-Id")    String userId,
 *                        @RequestBody   String          prompt) {
 *         ClsBaggage.set(sessionId, userId);
 *         try {
 *             return chatClient.prompt().user(prompt).call().content();
 *         } finally {
 *             ClsBaggage.clear();
 *         }
 *     }
 * }
 * }</pre>
 */
public final class ClsBaggage {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_NAME = new ThreadLocal<>();

    private ClsBaggage() { /* utility */ }

    /** Set both {@code session.id} and {@code user.id} for the current thread. */
    public static void set(String sessionId, String userId) {
        if (sessionId != null && !sessionId.isEmpty()) SESSION_ID.set(sessionId);
        if (userId != null && !userId.isEmpty())       USER_ID.set(userId);
    }

    /** Set {@code session.id}, {@code user.id} and {@code user.name} in one call.
     *  Any argument may be null/empty — only non-empty values are stored. */
    public static void set(String sessionId, String userId, String userName) {
        if (sessionId != null && !sessionId.isEmpty()) SESSION_ID.set(sessionId);
        if (userId != null && !userId.isEmpty())       USER_ID.set(userId);
        if (userName != null && !userName.isEmpty())   USER_NAME.set(userName);
    }

    /** Set only {@code session.id}. */
    public static void setSession(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) SESSION_ID.set(sessionId);
    }

    /** Set only {@code user.id}. */
    public static void setUser(String userId) {
        if (userId != null && !userId.isEmpty()) USER_ID.set(userId);
    }

    /** Set only {@code user.name} \u2014 human-readable display name for the user.
     *  Distinct from {@link #setUser} (which carries the stable identifier). */
    public static void setUserName(String userName) {
        if (userName != null && !userName.isEmpty()) USER_NAME.set(userName);
    }

    /** Clear all three fields. Call from a {@code finally} block. */
    public static void clear() {
        SESSION_ID.remove();
        USER_ID.remove();
        USER_NAME.remove();
    }

    /** @return the current thread's session.id, or {@code null} if unset. */
    public static String session() {
        return SESSION_ID.get();
    }

    /** @return the current thread's user.id, or {@code null} if unset. */
    public static String user() {
        return USER_ID.get();
    }

    /** @return the current thread's user.name, or {@code null} if unset. */
    public static String userName() {
        return USER_NAME.get();
    }
}
