package io.github.hoanglv1106.flowdock.core.payment;

/**
 * Bounded identifier length proposed in contracts.md section 3.1. It is an implementation
 * proposal, not an approved system-wide constraint, so keep the single source of truth here.
 */
public final class IdentityLimits {
    public static final int MAX_IDENTIFIER_LENGTH = 64;

    private IdentityLimits() {
    }
}
