package dtm.pdfloom.resource.impl;

import dtm.pdfloom.resource.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class HttpResourceResolver implements ResourceResolver, AutoCloseable {
    private final ResourceResolver local;
    private final Set<String> hosts;
    private final Duration timeout;
    private final long maxBytes;
    private final HttpClient client;

    public HttpResourceResolver(
            ResourceResolver local, Set<String> authorizedHosts, Duration timeout, long maxBytes) {
        this.local = Objects.requireNonNull(local);
        this.hosts =
                Set.copyOf(authorizedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList());
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero() || maxBytes < 1) {
            throw new IllegalArgumentException("Positive limits required");
        }
        this.maxBytes = maxBytes;
        client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(timeout)
                        .build();
    }

    @Override
    public ResolvedResource resolve(String reference, URI origin) throws IOException {
        URI uri;
        try {
            URI ref = URI.create(reference.replace(" ", "%20"));
            uri = origin == null ? ref : origin.resolve(ref);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid remote URI", e);
        }
        if (!Set.of("http", "https")
                .contains(
                        uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT))) {
            return local.resolve(reference, origin);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int redirects = 0; redirects <= 5; redirects++) {
            validate(uri);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("HTTP deadline exceeded");
            }
            HttpRequest request =
                    HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining)).GET().build();
            HttpResponse<byte[]> response;
            BoundedBody body = new BoundedBody(maxBytes);
            CompletableFuture<HttpResponse<byte[]>> pending =
                    client.sendAsync(request, info -> body);
            try {
                response = pending.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                body.cancel();
                pending.cancel(true);
                throw new IOException("HTTP deadline exceeded", e);
            } catch (InterruptedException e) {
                body.cancel();
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("HTTP interrupted", e);
            } catch (ExecutionException e) {
                throw new IOException("HTTP request failed: " + uri, e.getCause());
            }
            int status = response.statusCode();
            if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                String location =
                        response.headers()
                                .firstValue("Location")
                                .orElseThrow(() -> new IOException("Redirect missing location"));
                URI next;
                try {
                    next = uri.resolve(location);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid redirect", e);
                }
                if (uri.getScheme().equals("https") && next.getScheme().equals("http")) {
                    throw new IOException("HTTPS downgrade denied");
                }
                uri = next;
                continue;
            }
            if (status == 404) {
                throw new FileNotFoundException(uri.toString());
            }
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP status " + status + " for " + uri);
            }
            return new ResolvedResource(
                    uri,
                    response.headers().firstValue("Content-Type").orElse(null),
                    new ByteArrayInputStream(response.body()));
        }
        throw new IOException("HTTP redirect limit exceeded");
    }

    private void validate(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null
                || !Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null
                || uri.getHost() == null
                || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IOException("Remote destination denied: " + uri);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private long bytes;
        private Flow.Subscription subscription;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();

        BoundedBody(long limit) {
            this.limit = limit;
        }

        public CompletionStage<byte[]> getBody() {
            return future;
        }

        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (future.isDone()) {
                s.cancel();
            } else {
                s.request(1);
            }
        }

        void cancel() {
            future.completeExceptionally(new IOException("HTTP cancelled"));
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer b : buffers) {
                bytes += b.remaining();
                if (bytes > limit) {
                    subscription.cancel();
                    future.completeExceptionally(
                            new IOException("HTTP resource byte limit exceeded"));
                    return;
                }
                byte[] data = new byte[b.remaining()];
                b.get(data);
                out.writeBytes(data);
            }
            subscription.request(1);
        }

        public void onError(Throwable t) {
            future.completeExceptionally(t);
        }

        public void onComplete() {
            future.complete(out.toByteArray());
        }
    }
}
