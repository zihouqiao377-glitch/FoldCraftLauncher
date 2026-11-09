package com.tungsten.fcl.lumen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.constraintlayout.widget.ConstraintLayout;

/**
 * 侧边栏容器：拖动即选 + 水滴形变。
 * 1) 触摸：接管整列事件，按纵坐标命中子项
 * 2) 玻璃：容器自绘圆角底板（子控件 background 会被 FCLMenuView 覆盖）
 * 3) 水滴：对命中项套 Matrix，朝手指方向拉伸、另一端收窄，松手回弹
 */
public class DragMenuLayout extends ConstraintLayout {

    private final int slop;
    private int activeIndex = -1;
    private float downY = 0f;
    private float touchY = 0f;
    private boolean dragging = false;

    private final Paint glassFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Matrix deform = new Matrix();
    private boolean inited = false;

    private float stretch = 0f;
    private ValueAnimator spring;
    private ValueAnimator hueAnim;

    public DragMenuLayout(Context c) {
        this(c, null);
    }

    public DragMenuLayout(Context c, AttributeSet a) {
        this(c, a, 0);
    }

    public DragMenuLayout(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                touchY = downY;
                dragging = false;
                setActive(indexAt(ev.getY()));
                return true;
            case MotionEvent.ACTION_MOVE:
                touchY = ev.getY();
                if (!dragging && Math.abs(touchY - downY) > slop) {
                    dragging = true;
                }
                if (dragging) {
                    setActive(indexAt(touchY));
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                int i = indexAt(ev.getY());
                releaseActive();
                if (i >= 0) {
                    getChildAt(i).performClick();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseActive();
                dragging = false;
                return true;
        }
        return super.onTouchEvent(ev);
    }

    private int indexAt(float y) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() != VISIBLE) {
                continue;
            }
            if (y >= c.getTop() - c.getHeight() * 0.15f && y <= c.getBottom() + c.getHeight() * 0.15f) {
                return i;
            }
        }
        return -1;
    }

    private void setActive(int index) {
        if (index == activeIndex) {
            return;
        }
        activeIndex = index;
        animateStretch(index >= 0 ? 1f : 0f);
        invalidate();
    }

    private void releaseActive() {
        activeIndex = -1;
        animateStretch(0f);
        invalidate();
    }

    private void animateStretch(float target) {
        if (spring != null) {
            spring.cancel();
        }
        spring = ValueAnimator.ofFloat(stretch, target);
        spring.setDuration(target > stretch ? 120 : 320);
        spring.setInterpolator(target > stretch
                ? new android.view.animation.DecelerateInterpolator()
                : new android.view.animation.OvershootInterpolator(2.4f));
        spring.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                stretch = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        spring.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!inited) {
            glassFill.setColor(0x1FFFFFFF);
            glassEdge.setColor(0x55FFFFFF);
            glassEdge.setStyle(Paint.Style.STROKE);
            glassEdge.setStrokeWidth(getResources().getDisplayMetrics().density * 1.1f);
            inited = true;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() != VISIBLE) {
                continue;
            }
            drawPlate(canvas, c, i == activeIndex);
        }
        super.onDraw(canvas);
    }

    private void drawPlate(Canvas canvas, View c, boolean active) {
        float pad = getResources().getDisplayMetrics().density * 4f;
        float r = getResources().getDisplayMetrics().density * 16f;
        rect.set(c.getLeft() - pad, c.getTop() - pad, c.getRight() + pad, c.getBottom() + pad);

        path.reset();
        path.addRoundRect(rect, r, r, Path.Direction.CW);

        if (active && stretch > 0.01f) {
            float cy = rect.centerY();
            float pull = stretch * Math.min(1f, Math.abs(touchY - cy) / (rect.height() * 1.1f));
            float bulge = rect.width() * 0.18f * pull;
            RectF blob = new RectF(rect.left - bulge, rect.top, rect.right + bulge, rect.bottom);
            Path p2 = new Path();
            p2.addRoundRect(blob, r + bulge * 0.4f, r + bulge * 0.4f, Path.Direction.CW);
            path.op(p2, Path.Op.UNION);
        }

        canvas.drawPath(path, glassFill);
        canvas.drawPath(path, glassEdge);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        int idx = indexOfChild(child);
        if (idx != activeIndex || stretch <= 0.01f) {
            return super.drawChild(canvas, child, drawingTime);
        }
        float cx = child.getLeft() + child.getWidth() / 2f;
        float cy = child.getTop() + child.getHeight() / 2f;
        float near = Math.min(1f, Math.abs(touchY - cy) / (child.getHeight() * 1.4f));
        float k = stretch * (1f - near);

        deform.reset();
        deform.postScale(1f + 0.38f * k, 1f - 0.14f * k, cx, cy);
        deform.postSkew(0f, 0.10f * k * (touchY > cy ? 1f : -1f), cx, cy);

        int save = canvas.save();
        canvas.concat(deform);
        boolean r = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(save);
        return r;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startHueShift();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopHueShift();
        if (spring != null) {
            spring.cancel();
            spring = null;
        }
        super.onDetachedFromWindow();
    }

    private void startHueShift() {
        if (hueAnim != null) {
            return;
        }
        final View bg = getRootView().findViewById(com.tungsten.fcl.R.id.background);
        if (!(bg instanceof android.widget.ImageView)) {
            return;
        }
        final android.widget.ImageView iv = (android.widget.ImageView) bg;
        hueAnim = ValueAnimator.ofFloat(0f, 360f);
        hueAnim.setDuration(26000);
        hueAnim.setRepeatCount(ValueAnimator.INFINITE);
        hueAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        hueAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float h = (Float) a.getAnimatedValue();
                ColorMatrix cm = new ColorMatrix();
                cm.setRotate(0, h * 0.28f);
                iv.setColorFilter(new ColorMatrixColorFilter(cm));
            }
        });
        hueAnim.start();
    }

    private void stopHueShift() {
        if (hueAnim != null) {
            hueAnim.cancel();
            hueAnim = null;
        }
    }
}
