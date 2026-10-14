package dtm.pdfloom.config;

public record Diagnostic(Severity severity, String code, String message) {
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
