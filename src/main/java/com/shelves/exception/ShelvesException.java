package com.shelves.exception;

/**
 * Base class for every exception Shelves raises on purpose.
 * <p>
 * Catching this one type lets the UI layer handle any expected failure in a
 * single place, while genuine programming errors (NullPointerException and
 * friends) stay unchecked and surface loudly during development.
 */
public class ShelvesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ShelvesException(String message) {
        super(message);
    }

    public ShelvesException(String message, Throwable cause) {
        super(message, cause);
    }
}
