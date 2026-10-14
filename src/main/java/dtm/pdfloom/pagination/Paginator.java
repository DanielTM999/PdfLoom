package dtm.pdfloom.pagination;

import dtm.pdfloom.layout.LayoutResult;
import dtm.pdfloom.page.RenderPage;

import java.util.List;

public interface Paginator {
    List<RenderPage> paginate(LayoutResult layout);
}
