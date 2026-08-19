package io.github.kchanis1223.subauth;

/**
 * Controls how SubAuth handles portable Spring AI generation options that the
 * selected subscription runtime cannot apply.
 */
public enum SubAuthUnsupportedOptionsPolicy {
    IGNORE,
    WARN,
    REJECT
}
