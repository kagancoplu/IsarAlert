package com.isaralert.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for {@code www.wg-gesucht.de} serving search and detail pages built with
 * {@link WgPages}. Every request path is recorded, so tests can check what the scraper fetched.
 *
 * <p>Search result pages are served by page number (0, 1, 2 ...); unconfigured search pages
 * are empty, and unconfigured detail pages return 404.</p>
 */
public class FakeWgGesucht implements AutoCloseable {

    private static final String SEARCH_PATH_PREFIX = "/wohnungen-in-Muenchen.90.2.1.";

    private record Page(int status, String html) {
    }

    private final HttpServer server;
    private final Map<Integer, Page> searchPages = new ConcurrentHashMap<>();
    private final Map<String, Page> detailPages = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    public FakeWgGesucht() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start fake WG-Gesucht", e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Serves {@code html} as search results page {@code page} (0-based). */
    public FakeWgGesucht searchPage(int page, String html) {
        searchPages.put(page, new Page(200, html));
        return this;
    }

    /** Makes search results page {@code page} fail with the given HTTP status. */
    public FakeWgGesucht searchPageFails(int page, int status) {
        searchPages.put(page, new Page(status, "error"));
        return this;
    }

    /** Serves {@code html} at a detail page path like {@code /wohnungen-in-Muenchen-Sendling.123.html}. */
    public FakeWgGesucht detailPage(String path, String html) {
        detailPages.put(path, new Page(200, html));
        return this;
    }

    /** Makes a detail page fail with the given HTTP status. */
    public FakeWgGesucht detailPageFails(String path, int status) {
        detailPages.put(path, new Page(status, "error"));
        return this;
    }

    /** All request paths (without query string), in order. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** Full request URIs (with query string) of search page requests. */
    public List<String> searchRequests() {
        return requests.stream().filter(p -> p.startsWith(SEARCH_PATH_PREFIX)).toList();
    }

    /** How many times a given path was requested. */
    public long requestCount(String path) {
        return requests.stream().filter(p -> p.equals(path) || p.startsWith(path + "?")).count();
    }

    public void reset() {
        searchPages.clear();
        detailPages.clear();
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(query == null ? path : path + "?" + query);

            Page page;
            if (path.startsWith(SEARCH_PATH_PREFIX)) {
                int pageNumber = Integer.parseInt(path.substring(SEARCH_PATH_PREFIX.length()).replace(".html", ""));
                page = searchPages.getOrDefault(pageNumber, new Page(200, WgPages.searchPage()));
            } else {
                page = detailPages.getOrDefault(path, new Page(404, "not found"));
            }

            byte[] bytes = page.html().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(page.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
