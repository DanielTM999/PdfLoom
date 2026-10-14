package dtm.pdfloom.resource.impl;

import dtm.pdfloom.resource.*;

import java.io.*;
import java.net.URI;
import java.util.*;

public final class ClasspathResourceResolver implements ResourceResolver {
    private final String root;
    private final ClassLoader loader;

    public ClasspathResourceResolver(String root) {
        this(root, ClasspathResourceResolver.class.getClassLoader());
    }

    public ClasspathResourceResolver(String root, ClassLoader loader) {
        String r = root.replace('\\', '/').replaceAll("^/+|/+$", "");
        if (Arrays.asList(r.split("/")).contains("..")) {
            throw new IllegalArgumentException("Invalid classpath root");
        }
        this.root = r.isEmpty() ? "" : r + "/";
        this.loader = Objects.requireNonNull(loader);
    }

    @Override
    public ResolvedResource resolve(String ref, URI origin) throws IOException {
        if (ref.contains("\\") || ref.startsWith("//")) {
            throw new IOException("Invalid classpath resource");
        }
        URI u =
                (origin == null ? URI.create("classpath:/") : origin)
                        .resolve(ref.replace(" ", "%20"))
                        .normalize();
        if (!"classpath".equals(u.getScheme())
                || u.getAuthority() != null
                || Arrays.asList(u.getPath().split("/")).contains("..")) {
            throw new IOException("Classpath escape: " + ref);
        }
        InputStream s = loader.getResourceAsStream(root + u.getPath().replaceAll("^/+", ""));
        if (s == null) {
            throw new FileNotFoundException(ref);
        }
        return new ResolvedResource(u, null, s);
    }
}
