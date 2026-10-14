package dtm.pdfloom.resource.impl;

import dtm.pdfloom.resource.*;

import java.io.*;
import java.net.URI;
import java.util.*;

public final class MemoryResourceResolver implements ResourceResolver {
    private final Map<String, byte[]> entries;

    public MemoryResourceResolver(Map<String, byte[]> entries) {
        Map<String, byte[]> copy = new HashMap<>();
        entries.forEach((k, v) -> copy.put(k.startsWith("/") ? k : "/" + k, v.clone()));
        this.entries = Map.copyOf(copy);
    }

    @Override
    public ResolvedResource resolve(String ref, URI origin) throws IOException {
        URI u =
                (origin == null ? URI.create("memory:/") : origin)
                        .resolve(ref.replace(" ", "%20"))
                        .normalize();
        if (!"memory".equals(u.getScheme())
                || u.getAuthority() != null
                || Arrays.asList(u.getPath().split("/")).contains("..")) {
            throw new IOException("Memory resource escape: " + ref);
        }
        byte[] b = entries.get(u.getPath());
        if (b == null) {
            throw new FileNotFoundException(ref);
        }
        return new ResolvedResource(u, null, new ByteArrayInputStream(b));
    }
}
