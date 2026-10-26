package com.zen.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Icônes vectorielles fines (style trait) pour les boutons du dock. */
final class Icons extends Drawable {

    static final int GRID = 0, INPUT = 1, GEAR = 2;

    private final int kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    Icons(int kind, int color) {
        this.kind = kind;
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(color);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        stroke.setStrokeWidth(s * 0.07f);
        switch (kind) {
            case GRID: {
                float cell = s * 0.2f, gap = s * 0.11f, start = -(cell * 1.5f + gap);
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        float x = cx + start + i * (cell + gap), y = cy + start + j * (cell + gap);
                        c.drawRoundRect(new RectF(x, y, x + cell, y + cell), cell * 0.3f, cell * 0.3f, fill);
                    }
                }
                break;
            }
            case INPUT: {
                RectF screen = new RectF(cx - s * 0.36f, cy - s * 0.28f, cx + s * 0.36f, cy + s * 0.2f);
                c.drawRoundRect(screen, s * 0.06f, s * 0.06f, stroke);
                c.drawLine(cx - s * 0.14f, cy + s * 0.34f, cx + s * 0.14f, cy + s * 0.34f, stroke);
                Path arrow = new Path();
                arrow.moveTo(cx - s * 0.16f, cy - s * 0.04f);
                arrow.lineTo(cx + s * 0.1f, cy - s * 0.04f);
                arrow.moveTo(cx + s * 0.01f, cy - s * 0.13f);
                arrow.lineTo(cx + s * 0.1f, cy - s * 0.04f);
                arrow.lineTo(cx + s * 0.01f, cy + s * 0.05f);
                c.drawPath(arrow, stroke);
                break;
            }
            default: {
                int teeth = 8;
                float rOut = s * 0.38f, rIn = s * 0.29f;
                Path p = new Path();
                for (int i = 0; i < teeth * 4; i++) {
                    double a = Math.PI * 2 * i / (teeth * 4);
                    float r = (i % 4 == 0 || i % 4 == 1) ? rOut : rIn;
                    float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.close();
                c.drawPath(p, stroke);
                c.drawCircle(cx, cy, s * 0.11f, stroke);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        stroke.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
