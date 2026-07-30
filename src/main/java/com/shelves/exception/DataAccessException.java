package com.shelves.exception;

/**
 * Thrown when the database cannot complete an operation.
 * <p>
 * Every {@link java.sql.SQLException} is wrapped in one of these before it
 * leaves the persistence layer, so the rest of the program never has to import
 * {@code java.sql}. The original exception is always kept as the cause, so no
 * diagnostic detail is lost.
 */
public class DataAccessException extends ShelvesException {

    private static final long serialVersionUID = 1L;

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataAccessException(String message) {
        super(message);
    }
}
