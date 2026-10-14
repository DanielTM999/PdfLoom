package dtm.pdfloom.render;

import dtm.pdfloom.page.RenderPage;

import java.io.*;
import java.util.*;

public interface PdfRenderer {
    void render(List<RenderPage> pages, OutputStream destination) throws IOException;
}
