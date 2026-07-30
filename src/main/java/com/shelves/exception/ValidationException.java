package com.shelves.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thrown when user input fails validation before it ever reaches the database.
 * <p>
 * Carries every problem found rather than only the first, so a form can show
 * all of its errors at once instead of making the user fix them one at a time.
 */
public class ValidationException extends ShelvesException {

    private static final long serialVersionUID = 1L;

    private final List<String> errors;

    public ValidationException(List<String> errors) {
        super(String.join("\n", errors));
        this.errors = new ArrayList<>(errors);
    }

    public ValidationException(String error) {
        this(List.of(error));
    }

    /** The individual problems found, in the order they were checked. */
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
