package io.yunuservices.features.core.mineskin;

import org.mineskin.data.Visibility;

public class MineSkinSettings {
    private boolean enabled = true;
    private String apiKey = "";
    private Visibility visibility = Visibility.UNLISTED;
    private Limits limits = new Limits();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Visibility getVisibility() {
        if (visibility == null) {
            visibility = Visibility.UNLISTED;
        }
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility == null ? Visibility.UNLISTED : visibility;
    }

    public Limits getLimits() {
        if (limits == null) {
            limits = new Limits();
        }
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits == null ? new Limits() : limits;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isAvailable() {
        return enabled && hasApiKey();
    }

    public static class Limits {
        private static final int MIN_INTERVAL_MILLIS = 50;
        private static final int MAX_INTERVAL_MILLIS = 10_000;
        private static final int MAX_CONCURRENCY = 4;
        private static final int MIN_TIMEOUT_SECONDS = 30;
        private static final int MAX_TIMEOUT_SECONDS = 600;

        private Mode mode = Mode.AUTO;
        private int intervalMillis = 200;
        private int concurrency = 1;
        private int timeoutSeconds = 300;

        public Mode getMode() {
            if (mode == null) {
                mode = Mode.AUTO;
            }
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode == null ? Mode.AUTO : mode;
        }

        public int getIntervalMillis() {
            return Math.max(MIN_INTERVAL_MILLIS, Math.min(MAX_INTERVAL_MILLIS, intervalMillis));
        }

        public void setIntervalMillis(int intervalMillis) {
            this.intervalMillis = intervalMillis;
        }

        public int getConcurrency() {
            return Math.max(1, Math.min(MAX_CONCURRENCY, concurrency));
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getTimeoutSeconds() {
            return Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds));
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public enum Mode {
            AUTO,
            CUSTOM
        }
    }
}
