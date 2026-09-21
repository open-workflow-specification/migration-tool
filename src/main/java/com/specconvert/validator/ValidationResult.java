package com.specconvert.validator;

/**
 * A single finding produced by {@link OutputValidator}.
 *
 * <p>Each result records:
 * <ul>
 *   <li><b>severity</b> — ERROR or WARNING</li>
 *   <li><b>path</b> — JSON-pointer-style location in the 1.0 document (e.g. {@code do[0].HelloState.switch[1].default})</li>
 *   <li><b>rule</b> — short identifier for the violated constraint (e.g. {@code missing_field})</li>
 *   <li><b>message</b> — human-readable description of the finding</li>
 * </ul>
 */
public final class ValidationResult {

    public enum Severity { ERROR, WARNING }

    public final Severity severity;
    public final String   path;
    public final String   rule;
    public final String   message;

    public ValidationResult(Severity severity, String path, String rule, String message) {
        this.severity = severity;
        this.path     = path;
        this.rule     = rule;
        this.message  = message;
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + path + " — " + rule + ": " + message;
    }
}
