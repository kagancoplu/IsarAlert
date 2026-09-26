package com.isaralert.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A local stand-in for {@code api.telegram.org} that records every {@code sendMessage} call.
 *
 * <p>Like the real API, it rejects messages that aren't valid MarkdownV2 or are longer than
 * 4096 characters with a 400 error, so formatting bugs fail tests instead of failing in the chat.
 * It can also simulate outages ({@link #failWith}).</p>
 */
public class FakeTelegramApi implements AutoCloseable {

    /** Telegram's maximum text length for a single message. */
    public static final int MAX_MESSAGE_LENGTH = 4096;

    public record SentMessage(long chatId, String text, String parseMode) {
    }

    public record Rejection(long chatId, String text, String reason) {
    }

    private record Failure(int errorCode, String description) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final List<SentMessage> sent = new CopyOnWriteArrayList<>();
    private final List<Rejection> rejected = new CopyOnWriteArrayList<>();
    private final AtomicReference<Failure> failure = new AtomicReference<>();
    private final AtomicInteger messageIds = new AtomicInteger();

    public FakeTelegramApi() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start fake Telegram API", e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /** Base URL to use as {@code telegram.bot.api-url} (the bot token is appended to it). */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/bot";
    }

    /** Messages Telegram accepted, in order. */
    public List<SentMessage> sent() {
        return List.copyOf(sent);
    }

    /** Messages accepted for one chat, in order. */
    public List<String> textsTo(long chatId) {
        return sent.stream().filter(m -> m.chatId() == chatId).map(SentMessage::text).toList();
    }

    /** The last message accepted for a chat. */
    public String lastTextTo(long chatId) {
        List<String> texts = textsTo(chatId);
        if (texts.isEmpty()) throw new AssertionError("No message was sent to chat " + chatId);
        return texts.get(texts.size() - 1);
    }

    /** Messages rejected because of invalid formatting or length. Should normally stay empty. */
    public List<Rejection> rejected() {
        return List.copyOf(rejected);
    }

    /** Makes every following request fail like the real API would, e.g. {@code failWith(502, "Bad Gateway")}. */
    public void failWith(int errorCode, String description) {
        failure.set(new Failure(errorCode, description));
    }

    /** Lets requests succeed again after {@link #failWith}. */
    public void recover() {
        failure.set(null);
    }

    public void reset() {
        sent.clear();
        rejected.clear();
        failure.set(null);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ==================== HTTP handling ====================

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath().toLowerCase();
            Failure currentFailure = failure.get();

            if (currentFailure != null) {
                respond(exchange, currentFailure.errorCode(), errorBody(currentFailure.errorCode(), currentFailure.description()));
                return;
            }

            if (!path.endsWith("/sendmessage")) {
                respond(exchange, 200, "{\"ok\":true,\"result\":true}");
                return;
            }

            JsonNode request = json.readTree(exchange.getRequestBody());
            long chatId = request.path("chat_id").asLong();
            String text = request.path("text").asText();
            String parseMode = request.path("parse_mode").isMissingNode() ? null : request.path("parse_mode").asText();

            Optional<String> problem = validate(text, parseMode);
            if (problem.isPresent()) {
                rejected.add(new Rejection(chatId, text, problem.get()));
                respond(exchange, 400, errorBody(400, "Bad Request: " + problem.get()));
                return;
            }

            sent.add(new SentMessage(chatId, text, parseMode));
            respond(exchange, 200, """
                    {"ok":true,"result":{"message_id":%d,"date":%d,"chat":{"id":%d,"type":"private"},"text":%s}}"""
                    .formatted(messageIds.incrementAndGet(), System.currentTimeMillis() / 1000, chatId,
                            json.writeValueAsString(text)));
        }
    }

    private static Optional<String> validate(String text, String parseMode) {
        if (text.isEmpty()) {
            return Optional.of("message text is empty");
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            return Optional.of("message is too long");
        }
        if ("MarkdownV2".equals(parseMode)) {
            return MarkdownV2.validate(text).map(reason -> "can't parse entities: " + reason);
        }
        return Optional.empty();
    }

    private String errorBody(int code, String description) throws IOException {
        return "{\"ok\":false,\"error_code\":" + code + ",\"description\":" + json.writeValueAsString(description) + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
