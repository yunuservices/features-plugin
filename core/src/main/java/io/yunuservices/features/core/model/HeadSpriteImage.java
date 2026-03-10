package io.yunuservices.features.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class HeadSpriteImage {
    private int widthSymbols;
    private int heightSymbols;
    private List<PlayerHeadSymbol> symbols = new ArrayList<>();

    public HeadSpriteImage() {
    }

    public HeadSpriteImage(int widthSymbols, int heightSymbols, List<PlayerHeadSymbol> symbols) {
        this.widthSymbols = widthSymbols;
        this.heightSymbols = heightSymbols;
        this.symbols = new ArrayList<>(symbols);
    }

    public int getWidthSymbols() {
        return widthSymbols;
    }

    public void setWidthSymbols(int widthSymbols) {
        this.widthSymbols = widthSymbols;
    }

    public int getHeightSymbols() {
        return heightSymbols;
    }

    public void setHeightSymbols(int heightSymbols) {
        this.heightSymbols = heightSymbols;
    }

    public List<PlayerHeadSymbol> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<PlayerHeadSymbol> symbols) {
        this.symbols = new ArrayList<>(symbols);
    }

    public int symbolCount() {
        return symbols.size();
    }

    public PlayerHeadSymbol symbolAt(int x, int y) {
        int index = y * widthSymbols + x;
        return symbols.get(index);
    }

    public boolean isValidGrid() {
        return widthSymbols > 0
            && heightSymbols > 0
            && symbols.size() == widthSymbols * heightSymbols;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HeadSpriteImage that)) {
            return false;
        }
        return widthSymbols == that.widthSymbols
            && heightSymbols == that.heightSymbols
            && Objects.equals(symbols, that.symbols);
    }

    @Override
    public int hashCode() {
        return Objects.hash(widthSymbols, heightSymbols, symbols);
    }
}
