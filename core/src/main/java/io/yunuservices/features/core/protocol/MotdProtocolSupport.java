package io.yunuservices.features.core.protocol;

public final class MotdProtocolSupport {
    public static final int DEFAULT_MIN_SPRITE_PROTOCOL = 773;
    public static final int DEFAULT_MAX_SPRITE_PROTOCOL = 774;

    private MotdProtocolSupport() {
    }

    public static boolean supportsSpriteMotd(int protocolVersion, int minProtocol, int maxProtocol) {
        int normalizedMin = normalizeMinProtocol(minProtocol, maxProtocol);
        int normalizedMax = normalizeMaxProtocol(minProtocol, maxProtocol);
        return protocolVersion >= normalizedMin && protocolVersion <= normalizedMax;
    }

    public static int normalizeMinProtocol(int minProtocol, int maxProtocol) {
        int normalizedMin = minProtocol > 0 ? minProtocol : DEFAULT_MIN_SPRITE_PROTOCOL;
        int normalizedMax = maxProtocol > 0 ? maxProtocol : DEFAULT_MAX_SPRITE_PROTOCOL;
        return Math.min(normalizedMin, normalizedMax);
    }

    public static int normalizeMaxProtocol(int minProtocol, int maxProtocol) {
        int normalizedMin = minProtocol > 0 ? minProtocol : DEFAULT_MIN_SPRITE_PROTOCOL;
        int normalizedMax = maxProtocol > 0 ? maxProtocol : DEFAULT_MAX_SPRITE_PROTOCOL;
        return Math.max(normalizedMin, normalizedMax);
    }
}
