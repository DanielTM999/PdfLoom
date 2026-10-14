package dtm.pdfloom.font;

public record FontData(String family, int weight, boolean italic, byte[] sfnt) {
    public FontData {
        sfnt = sfnt.clone();
    }

    @Override
    public byte[] sfnt() {
        return sfnt.clone();
    }
}
