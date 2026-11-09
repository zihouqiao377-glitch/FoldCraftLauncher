package com.tungsten.fcl.lumen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 极光流动层：整屏绘制，用 AGSL（RuntimeShader）在 GPU 上跑噪声，
 * 随时间平移坐标，形成缓慢流动的紫光。
 *
 * Android 13 (API 33) 以下没有 RuntimeShader，自动退化为静态渐变，
 * 不会崩，只是不动。
 */
public class LumenFlowView extends View {

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;
    private float time = 0f;
    private boolean shaderReady = false;

    public LumenFlowView(Context c) {
        this(c, null);
    }

    public LumenFlowView(Context c, AttributeSet a) {
        this(c, a, 0);
    }

    public LumenFlowView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) {
            shaderReady = Api33.setup(paint, w, h);
            if (!shaderReady) {
                // 低版本兜底：静态对角渐变
                paint.setShader(new LinearGradient(0, 0, w, h,
                        new int[]{0xFF2A1A5E, 0xFF120C2B, 0xFF05040C},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            }
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (paint.getShader() == null) {
            canvas.drawColor(0xFF120C2B);
            return;
        }
        if (shaderReady) {
            Api33.setTime(paint, time);
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (anim != null) {
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(12000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                time = (Float) a.getAnimatedValue() * 24f;
                if (shaderReady) {
                    invalidate();
                }
            }
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        super.onDetachedFromWindow();
    }

    // ---------------- AGSL 部分单独隔离 ----------------
    // 放在内部类里，避免低版本设备加载本类时就解析 RuntimeShader
    private static final class Api33 {

        private static final String SOURCE =
                "uniform float2 uSize;\n" +
                "uniform float  uTime;\n" +
                "uniform float3 uA;\n" +
                "uniform float3 uB;\n" +
                "uniform float3 uC;\n" +
                "float hash(float2 p){ return fract(sin(dot(p, float2(127.1,311.7)))*43758.5453); }\n" +
                "float noise(float2 p){\n" +
                "  float2 i = floor(p), f = fract(p);\n" +
                "  float2 u = f*f*(3.0-2.0*f);\n" +
                "  return mix(mix(hash(i), hash(i+float2(1,0)), u.x),\n" +
                "             mix(hash(i+float2(0,1)), hash(i+float2(1,1)), u.x), u.y);\n" +
                "}\n" +
                "float fbm(float2 p){\n" +
                "  float v = 0.0, a = 0.5;\n" +
                "  for (int i = 0; i < 4; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; }\n" +
                "  return v;\n" +
                "}\n" +
                "half4 main(float2 fc){\n" +
                "  float2 uv = fc / uSize;\n" +
                "  float2 p = uv * float2(3.0, 5.0);\n" +
                "  float t = uTime * 0.22;\n" +
                // 域扭曲：先用两层噪声算出偏移向量，再拿它去采样主体噪声
                "  float2 q = float2(fbm(p + float2(0.0, t)), fbm(p + float2(3.7, -t*0.8)));\n" +
                "  float f = fbm(p + q * 1.8 + float2(t*0.3, -t*0.2));\n" +
                "  float m = smoothstep(0.15, 0.95, f);\n" +
                "  float3 col = mix(uA, uB, m);\n" +
                "  col = mix(col, uC, smoothstep(0.40, 1.0, uv.y) * 0.55);\n" +
                "  return half4(col, 1.0);\n" +
                "}\n";

        private static android.graphics.RuntimeShader shader;

        static boolean setup(Paint paint, int w, int h) {
            if (android.os.Build.VERSION.SDK_INT < 33) {
                return false;
            }
            try {
                if (shader == null) {
                    shader = new android.graphics.RuntimeShader(SOURCE);
                }
                shader.setFloatUniform("uSize", (float) w, (float) h);
                shader.setColorUniform("uA", Color.valueOf(0.16f, 0.10f, 0.37f));
                shader.setColorUniform("uB", Color.valueOf(0.36f, 0.24f, 0.78f));
                shader.setColorUniform("uC", Color.valueOf(0.03f, 0.02f, 0.07f));
                shader.setFloatUniform("uTime", 0f);
                paint.setShader(shader);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        static void setTime(Paint paint, float t) {
            if (shader == null) {
                return;
            }
            try {
                shader.setFloatUniform("uTime", t);
            } catch (Throwable ignored) {
            }
        }
    }
}
