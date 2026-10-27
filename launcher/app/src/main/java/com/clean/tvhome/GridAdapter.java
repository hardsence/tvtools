package com.clean.tvhome;

import android.graphics.Typeface;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class GridAdapter extends RecyclerView.Adapter<GridAdapter.Holder> {

    static final class Holder extends RecyclerView.ViewHolder {
        final Tile tile;

        Holder(Tile t) {
            super(t);
            tile = t;
        }
    }

    private final int cardW, cardH, rowH, gap;
    private final Typeface font;
    private List<Apps.Entry> apps = new ArrayList<>();
    int focused = -1;
    boolean editing;

    GridAdapter(int cardW, int cardH, int rowH, int gap, Typeface font) {
        this.cardW = cardW;
        this.cardH = cardH;
        this.rowH = rowH;
        this.gap = gap;
        this.font = font;
    }

    void setApps(List<Apps.Entry> list) {
        apps = list;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Tile t = new Tile(parent.getContext(), cardW, cardH, false, font);
        t.setPadding(gap / 2, 0, gap / 2, 0);
        t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        return new Holder(t);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        h.tile.bind(apps.get(position));
        h.tile.setFocused(position == focused, false);
        h.tile.setDimmed(editing && position != focused);
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }
}
