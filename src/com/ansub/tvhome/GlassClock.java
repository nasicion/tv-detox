package com.ansub.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.widget.TextClock;

// The big clock drawn as glass: frosted see-through digits, brighter at the top where light would
// catch them, with a thin bright rim and a soft shadow so they still lift off any wallpaper.
// Each frame draws the text twice through TextView's own drawing: once filled, once as an outline.
final class GlassClock extends TextClock {
    private LinearGradient fill, rim;

    GlassClock(Context c) {
        super(c);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fill = new LinearGradient(0, 0, 0, h, 0x9EFFFFFF, 0x24FFFFFF, Shader.TileMode.CLAMP);
        rim = new LinearGradient(0, 0, 0, h, 0xF2FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (fill == null) { super.onDraw(c); return; }
        Paint p = getPaint();
        float size = getTextSize();

        p.setStyle(Paint.Style.FILL);
        p.setShader(fill);
        p.setShadowLayer(size * 0.05f, 0, size * 0.02f, 0x40000000);
        super.onDraw(c);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, size * 0.012f));
        p.setShader(rim);
        p.clearShadowLayer();
        super.onDraw(c);

        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
    }
}
