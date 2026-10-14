package dtm.pdfloom.resource;

import java.io.IOException;
import java.net.URI;

public interface ResourceResolver {
    ResolvedResource resolve(String reference, URI origin) throws IOException;
}
