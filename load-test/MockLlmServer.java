import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简 Mock LLM 上游服务，专门用于网关压测。
 *
 * <p>为什么要 Mock：真实 LLM 服务单次调用耗时数秒且有速率限制，
 * 压测目标是"网关自身的吞吐"，所以需要本地毫秒级返回的假上游。
 *
 * <p>用法：
 * <pre>
 *   javac -encoding UTF-8 -d out MockLlmServer.java
 *   MOCK_PORT=9999 MOCK_DELAY_MS=5 java -cp out MockLlmServer
 * </pre>
 *
 * <p>环境变量：
 * <ul>
 *   <li>MOCK_PORT      监听端口，默认 9999</li>
 *   <li>MOCK_DELAY_MS  模拟上游处理耗时（毫秒），默认 5</li>
 * </ul>
 *
 * <p>接口：
 * <ul>
 *   <li>POST /v1/chat/completions  返回 OpenAI 风格的 JSON 响应</li>
 *   <li>GET  /health               返回 200 OK</li>
 * </ul>
 */
public class MockLlmServer {

    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong TOTAL_LATENCY = new AtomicLong();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("MOCK_PORT", "9999"));
        long delayMs = Long.parseLong(System.getenv().getOrDefault("MOCK_DELAY_MS", "5"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            long start = System.nanoTime();
            sleepQuietly(delayMs);
            byte[] body = responseJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            REQUESTS.incrementAndGet();
            TOTAL_LATENCY.addAndGet((System.nanoTime() - start) / 1_000_000);
        });
        server.createContext("/health", exchange -> {
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(200));
        server.start();
        System.out.println("[MockLlmServer] listening on port " + port + ", delay=" + delayMs + "ms");

        // 每 5 秒打印一次吞吐统计，方便观察
        new Thread(() -> {
            long lastCount = 0;
            long lastTime = System.currentTimeMillis();
            while (true) {
                sleepQuietly(5000);
                long now = System.currentTimeMillis();
                long count = REQUESTS.get();
                long qps = (count - lastCount) * 1000 / Math.max(1, now - lastTime);
                long avgLatency = count == 0 ? 0 : TOTAL_LATENCY.get() / count;
                System.out.printf("[MockLlmServer] total=%d, recentQPS=%d, avgLatencyMs=%d%n",
                        count, qps, avgLatency);
                lastCount = count;
                lastTime = now;
            }
        }, "mock-stats").start();
    }

    private static String responseJson() {
        return "{\"id\":\"chatcmpl-mock0001\",\"object\":\"chat.completion\","
                + "\"created\":1750000000,\"model\":\"mock-model\","
                + "\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"This is a mock response for load testing.\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}