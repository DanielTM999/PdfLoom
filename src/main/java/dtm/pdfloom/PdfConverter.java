package dtm.pdfloom;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;

public interface PdfConverter {
    byte[] convert(String content);

    byte[] convert(File source);

    byte[] convert(InputStream source);

    void convert(String content, Path destination);

    void convert(File source, Path destination);

    void convert(InputStream source, Path destination);
}
