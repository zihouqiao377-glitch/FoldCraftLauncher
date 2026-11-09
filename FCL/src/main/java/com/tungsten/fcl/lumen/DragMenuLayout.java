package com.tungsten.fcl.lumen;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import androidx.constraintlayout.widget.ConstraintLayout;

/**
 * 侧边栏容器：把「点选」扩展成「拖动即选」。
 *
 * 做法是接管这一列的全部触摸事件（onInterceptTouchEvent 恒为 true），
 * 自己做命中测试，因此不会和子项原有的点击逻辑打架。
 *
 * 手指滑过某一项时，该项做一次轻微的横向拉伸（scaleX>1、scaleY<1），
 * 松手后回弹 —— 这就是「被手指拽出去再弹回来」的近似手感。
 */
public class DragMenuLayout extends ConstraintLayout {

    private final int slop;
    private int activeIndex = -1;
    private float downY;
    private boolean dragging;

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
        // 全部事件由本容器统一处理
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                dragging = false;
                setActive(indexAt(ev.getY()));
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(ev.getY() - downY) > slop) {
                    dragging = true;
                }
                if (dragging) {
                    setActive(indexAt(ev.getY()));
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (dragging) {
                    int i = indexAt(ev.getY());
                    clearActive();
                    if (i >= 0) {
                        getChildAt(i).performClick();
                    }
                } else {
                    clearActive();
                    // 未拖动：交给被按下的那一项处理点击
                    int i = indexAt(ev.getY());
                    if (i >= 0) {
                        getChildAt(i).performClick();
                    }
                }
                dragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                clearActive();
                dragging = false;
                return true;
        }
        return super.onTouchEvent(ev);
    }

    /** 按纵坐标找落在哪一项；找不到返回 -1 */
    private int indexAt(float y) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() != VISIBLE) {
                continue;
            }
            if (y >= c.getTop() && y <= c.getBottom()) {
                return i;
            }
        }
        return -1;
    }

    private void setActive(int index) {
        if (index == activeIndex) {
            return;
        }
        if (activeIndex >= 0 && activeIndex < getChildCount()) {
            bounce(getChildAt(activeIndex), 1f, 1f, 220);
        }
        activeIndex = index;
        if (index >= 0 && index < getChildCount()) {
            // 横向拉伸、纵向压扁，制造“被拽住”的形变
            bounce(getChildAt(index), 1.28f, 0.82f, 110);
        }
    }

    private void clearActive() {
        if (activeIndex >= 0 && activeIndex < getChildCount()) {
            bounce(getChildAt(activeIndex), 1f, 1f, 240);
        }
        activeIndex = -1;
    }

    private void bounce(View v, float sx, float sy, long ms) {
        if (v == null) {
            return;
        }
        v.animate()
                .scaleX(sx)
                .scaleY(sy)
                .setDuration(ms)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                .start();
    }

    // ---------------- 背景色相缓慢流转 ----------------
    private android.animation.ValueAnimator hueAnim;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startHueShift();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopHueShift();
        super.onDetachedFromWindow();
    }

    private void startHueShift() {
        if (hueAnim != null) {
            return;
        }
        final View root = getRootView();
        final View bg = root.findViewById(com.tungsten.fcl.R.id.background);
        if (!(bg instanceof android.widget.ImageView)) {
            return;
        }
        final android.widget.ImageView iv = (android.widget.ImageView) bg;
        hueAnim = android.animation.ValueAnimator.ofFloat(0f, 360f);
        hueAnim.setDuration(26000);
        hueAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        hueAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        hueAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float h = (Float) a.getAnimatedValue();
                // 只做小幅色相偏移，避免颜色跳到刺眼的区间
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setRotate(0, h * 0.28f);
                iv.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
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
