package io.yunuservices.features.core.model;

import java.util.Objects;

public class PlayerHeadSymbol {
    private String value;
    private String signature;

    public PlayerHeadSymbol() {
    }

    public PlayerHeadSymbol(String value, String signature) {
        this.value = value;
        this.signature = signature;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public boolean hasSignature() {
        return signature != null && !signature.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlayerHeadSymbol that)) {
            return false;
        }
        return Objects.equals(value, that.value) && Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, signature);
    }
}
