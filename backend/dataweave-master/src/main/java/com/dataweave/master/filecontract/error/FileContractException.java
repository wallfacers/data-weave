package com.dataweave.master.filecontract.error;

/**
 * File contract error with file location and message for AI-friendly error reporting (FR-015).
 * Extends RuntimeException so it can be thrown directly — no checked-exception boilerplate.
 */
public class FileContractException extends RuntimeException {

    private final String file;
    private final String locus;

    public FileContractException(String file, String locus, String message) {
        super(message);
        if (file == null || file.isBlank()) throw new IllegalArgumentException("file must not be blank");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message must not be blank");
        this.file = file;
        this.locus = locus;
    }

    public String file() { return file; }
    public String locus() { return locus; }

    @Override
    public String toString() {
        var sb = new StringBuilder(file);
        if (locus != null && !locus.isBlank()) sb.append(" @ ").append(locus);
        sb.append(": ").append(getMessage());
        return sb.toString();
    }
}
