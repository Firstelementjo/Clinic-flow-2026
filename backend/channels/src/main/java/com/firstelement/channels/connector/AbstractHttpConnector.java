package com.firstelement.channels.connector;

import com.firstelement.channels.api.OutgoingMessage;
import com.firstelement.channels.api.SendResult;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.worker.queue.RetryClassifier;
import com.firstelement.worker.queue.RetryDecision;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The shared HTTP behaviour of the real providers.
 *
 * <h2>Why the classification lives here</h2>
 *
 * <p>Both the WhatsApp connector and the Telegram connector had the same bug-shaped assumption:
 * "4xx means the message or credentials are wrong; retrying sends the same broken request and burns
 * the rate limit." So every provider status is turned into a {@link RetryDecision} at the edge of
 * this module, and the dispatcher never sees a raw integer.
 *
 * <h2>The timeout is configuration, and it is short</h2>
 *
 * <p>The legacy adapters used {@code AbortSignal.timeout(20_000)}. Twenty seconds is longer than
 * the dispatcher lease of sixty seconds divided by three attempts, so keeping the number explicit
 * here is what lets the lease stay meaningful: a handler must never outlive its lease.
 *
 * <h2>No credential is ever logged</h2>
 *
 * <p>The token appears in the {@code Authorization} header and in the URL path. Neither is logged,
 * and {@link #summary} truncates and flattens the provider's response because a provider may echo
 * the request back.
 */
public abstract class AbstractHttpConnector {

    private final HttpClient http;
    private final Duration requestTimeout;

    protected AbstractHttpConnector(Duration requestTimeout) {
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(20) : requestTimeout;
        this.http = HttpClient.newBuilder()
            .connectTimeout(this.requestTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public abstract ChannelType channel();

    /**
     * Performs a JSON POST and classifies the outcome.
     *
     * @param url        the provider endpoint, including any credential in its path
     * @param bearerToken may be null for a credential-free endpoint
     */
    protected SendResult postJson(URI url, String bearerToken, String jsonBody) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (bearerToken != null && !bearerToken.isBlank()) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // A transport failure is the one case where retrying is unambiguously right: the
            // request may or may not have reached the provider, which is exactly why the outbound
            // path checks the message's delivery status before sending again.
            return SendResult.failed(RetryClassifier.classify(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.failed(RetryDecision.retryable(RetryDecision.TIMEOUT, "INTERRUPTED",
                "The send was interrupted before a response arrived"));
        }
        return interpret(response.statusCode(), response.body());
    }

    /**
     * Turns an HTTP status into a decision, then lets the subclass read the provider id out of a
     * successful body.
     */
    protected SendResult interpret(int status, String body) {
        if (status >= 200 && status < 300) {
            String providerMessageId = extractProviderMessageId(body);
            return SendResult.sent(providerMessageId);
        }
        return SendResult.failed(RetryClassifier.classifyHttpStatus(status, summary(body)));
    }

    /** Reads the platform's message id out of a 2xx body; null when the shape is unfamiliar. */
    protected abstract String extractProviderMessageId(String body);

    /** The provider's error text, truncated and flattened for an operator-facing message. */
    protected static String summary(String body) {
        if (body == null || body.isBlank()) {
            return "empty response";
        }
        String flattened = body.replaceAll("[\\r\\n\\t]", " ");
        return flattened.length() > 200 ? flattened.substring(0, 200) + "…" : flattened;
    }

    protected Duration requestTimeout() {
        return requestTimeout;
    }
}