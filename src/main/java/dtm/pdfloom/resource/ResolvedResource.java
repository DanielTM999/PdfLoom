package dtm.pdfloom.resource;

import java.io.*;
import java.net.URI;
import java.util.Objects;

public final class ResolvedResource implements AutoCloseable {
    private final URI uri;
    private final String mediaType;
    private final InputStream stream;

    public ResolvedResource(URI uri, String mediaType, InputStream stream) {
        this.uri = Objects.requireNonNull(uri);
        this.mediaType = mediaType;
        this.stream = Objects.requireNonNull(stream);
    }

    public URI uri() {
        return uri;
    }

    public String mediaType() {
        return mediaType;
    }

    public InputStream stream() {
        return stream;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
