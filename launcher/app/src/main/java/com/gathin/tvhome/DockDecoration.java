package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean wallpaperTranslucent;
    private boolean cinematic;

    DockDecoration(int dockSize, float density) {
        this(dockSize, density, false);
    }

    DockDecoration(int dockSize, float density, boolean hasWallpaper) {
        this.dockSize = dockSize;
        this.density = density;
        setTranslucent(hasWallpaper);
    }

    /** Local photo wallpapers: a light glass band so the picture shows through. */
    void setTranslucent(boolean translucent) {
        wallpaperTranslucent = translucent;
        applyBand();
    }

    /** Upstream cinematic shelf: a darker translucent band over the artwork. Takes priority. */
    void setCinematic(boolean cinematic) {
        this.cinematic = cinematic;
        applyBand();
    }

    private void applyBand() {
        if (cinematic) {
            band.setColor(0x8C121014);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(density);
            edge.setColor(0x26FFFFFF);
        } else if (wallpaperTranslucent) {
            band.setColor(0x44000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(density);
            edge.setColor(0x33FFFFFF);
        } else {
            band.setColor(0xFF141416);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(density);
            edge.setColor(0x1FFFFFFF);
        }
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        float radius = 26 * density;
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
