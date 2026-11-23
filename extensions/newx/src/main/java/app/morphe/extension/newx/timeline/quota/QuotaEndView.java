/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.newx.timeline.quota;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.newx.theme.TwitterTheme;
import app.morphe.extension.newx.ui.Theme;

/**
 * The end-of-quota row shown at the bottom of the For You and Following timelines: an
 * animated checkmark that draws itself in, followed by a fading "You've reached the end"
 * message. The animation plays once per day; afterwards the same view renders statically.
 *
 * <p>Compose's lazy list composes items beyond the viewport, so the animation is started from
 * a pre-draw listener that waits until the row is actually visible on screen.
 */
final class QuotaEndView extends LinearLayout {
    private static final int ANIMATION_DURATION_MS = 950;
    /** Fraction of the animation spent drawing the circle before the check starts. */
    private static final float CIRCLE_END = 0.62f;
    private static final float CHECK_START = 0.58f;
    private static final int TEXT_DELAY_MS = 600;
    private static final int TEXT_DURATION_MS = 350;

    private final CheckmarkView checkmark;
    private final TextView message;
    private final Rect visibleRect = new Rect();
    private final ViewTreeObserver.OnPreDrawListener visibleListener =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (getWidth() == 0 || getHeight() == 0) return true;
                    if (!getGlobalVisibleRect(visibleRect)) return true;
                    begin();
                    return true;
                }
            };

    private ValueAnimator endAnimator;
    private boolean listenerAdded;
    private boolean begun;

    QuotaEndView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Theme.dpToPx(context, 24);
        setPadding(pad, pad, pad, pad);

        checkmark = new CheckmarkView(context);
        message = new TextView(context);
        message.setText(QuotaEndMarkers.END_MESSAGE);
        message.setTextColor(Theme.isDark(context) ? 0xFFE7E9EA : 0xFF0F1419);
        message.setTextSize(17f);
        message.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        LinearLayout.LayoutParams messageParams =
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = Theme.dpToPx(context, 14);

        addView(checkmark, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(message, messageParams);

        // Hidden until the row is on screen; the pre-draw callback reveals it, animated or not.
        message.setAlpha(0f);
        message.setTranslationY(Theme.dpToPx(context, 10));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (begun || listenerAdded) return;
        ViewTreeObserver observer = getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.addOnPreDrawListener(visibleListener);
        listenerAdded = true;
    }

    private void begin() {
        if (begun) return;
        begun = true;
        removeVisibleListener();
        if (DailyQuotaStore.consumeAnimationForToday()) {
            playEndAnimation();
        } else {
            showFinalState();
        }
    }

    private void removeVisibleListener() {
        if (!listenerAdded) return;
        listenerAdded = false;
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(visibleListener);
    }

    private void playEndAnimation() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIMATION_DURATION_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation ->
                checkmark.setProgress((Float) animation.getAnimatedValue()));
        animator.start();
        endAnimator = animator;

        message.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(TEXT_DURATION_MS)
                .setStartDelay(TEXT_DELAY_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void showFinalState() {
        checkmark.setProgress(1f);
        message.setAlpha(1f);
        message.setTranslationY(0f);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeVisibleListener();
        if (endAnimator != null) endAnimator.cancel();
        super.onDetachedFromWindow();
    }

    /** A circle that sweeps in, then a check mark that draws itself across it. */
    private static final class CheckmarkView extends View {
        private static final float SIZE_DP = 64f;
        private static final float STROKE_DP = 3.5f;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath = new Path();
        private final Path drawnCheck = new Path();
        private final PathMeasure measure = new PathMeasure();
        private final int accentColor;
        private float progress;

        CheckmarkView(Context context) {
            super(context);
            accentColor = TwitterTheme.accent(context).primaryColor();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Theme.dpToPx(context, STROKE_DP));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            int size = Theme.dpToPx(context, SIZE_DP);
            setLayoutParams(new LayoutParams(size, size));
        }

        void setProgress(float progress) {
            this.progress = Math.max(0f, Math.min(1f, progress));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0f || height <= 0f) return;

            // The whole mark eases in slightly while the circle sweeps.
            float circle = clamp01(progress / CIRCLE_END);
            float scale = 0.85f + 0.15f * circle;
            canvas.save();
            canvas.scale(scale, scale, width / 2f, height / 2f);

            float inset = paint.getStrokeWidth();
            float radius = Math.min(width, height) / 2f - inset;
            paint.setColor(accentColor);
            paint.setAlpha(255);
            if (circle >= 1f) {
                canvas.drawCircle(width / 2f, height / 2f, radius, paint);
            } else if (circle > 0f) {
                float sweep = 360f * circle;
                canvas.drawArc(
                        width / 2f - radius,
                        height / 2f - radius,
                        width / 2f + radius,
                        height / 2f + radius,
                        -90f,
                        sweep,
                        false,
                        paint);
            }

            float check = clamp01((progress - CHECK_START) / (1f - CHECK_START));
            if (check > 0f) {
                if (checkPath.isEmpty()) {
                    checkPath.moveTo(0.28f * width, 0.53f * height);
                    checkPath.lineTo(0.45f * width, 0.70f * height);
                    checkPath.lineTo(0.74f * width, 0.34f * height);
                }
                drawnCheck.reset();
                measure.setPath(checkPath, false);
                measure.getSegment(0f, measure.getLength() * check, drawnCheck, true);
                canvas.drawPath(drawnCheck, paint);
            }
            canvas.restore();
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }
}
