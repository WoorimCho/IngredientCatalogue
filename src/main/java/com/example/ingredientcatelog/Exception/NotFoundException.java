package com.example.ingredientcatelog.Exception;

/** Thrown when a lookup by id finds nothing; mapped to HTTP 404 by the advice. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String what, Object id) {
        return new NotFoundException(what + " " + id + " not found");
    }
}
