package dtm.pdfloom.resource.impl;

import dtm.pdfloom.resource.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;

public final class FileSystemResourceResolver implements ResourceResolver {
    private final Path root;

    public FileSystemResourceResolver(Path root) {
        try {
            this.root = root.toRealPath();
            if (!Files.isDirectory(this.root)) {
                throw new IllegalArgumentException("Root must be a directory");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot open resource root: " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public ResolvedResource resolve(String reference, URI origin) throws IOException {
        if (reference == null || reference.isBlank()) {
            throw new IOException("Empty resource reference");
        }
        if (reference.indexOf('\\') >= 0
                || reference.indexOf('\0') >= 0
                || reference.startsWith("//")) {
            throw new IOException("Forbidden resource path: " + reference);
        }
        URI ref;
        try {
            ref = new URI(reference.replace(" ", "%20"));
        } catch (Exception e) {
            throw new IOException("Invalid resource URI", e);
        }
        Path candidate;
        if (ref.isAbsolute()) {
            if (!"file".equalsIgnoreCase(ref.getScheme()) || ref.getAuthority() != null) {
                throw new IOException("Protocol denied: " + ref.getScheme());
            }
            try {
                candidate = Path.of(new URI("file", null, ref.getPath(), null));
            } catch (Exception e) {
                throw new IOException("Invalid file URI", e);
            }
        } else if (ref.getPath().startsWith("/")) {
            candidate = root.resolve(ref.getPath().substring(1));
        } else {
            Path directory = root;
            if (origin != null) {
                if (!"file".equalsIgnoreCase(origin.getScheme())) {
                    throw new IOException("Unsupported origin: " + origin);
                }
                Path op = Path.of(origin);
                directory = origin.getPath().endsWith("/") ? op : op.getParent();
            }
            candidate = directory.resolve(ref.getPath());
        }
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("Resource escapes root: " + reference);
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real)) {
            throw new IOException("Resource escapes root or is not a file: " + reference);
        }
        return new ResolvedResource(
                real.toUri(), Files.probeContentType(real), Files.newInputStream(real));
    }
}
