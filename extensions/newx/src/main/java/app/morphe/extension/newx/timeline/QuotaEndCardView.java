package app.morphe.extension.newx.timeline;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.StringRef;

/**
 * The end-of-feed card: an animated circular checkmark above "You've reached the end".
 *
 * <p>The checkmark draws a circle and a check whose stroke is revealed with a {@link PathMeasure}
 * trim driven by a {@link ValueAnimator}. When {@code animate} is false the check is shown fully
 * drawn with no animation (used for Search, and for the home surface after the first play of the
 * day). Suggested accounts are rendered separately by the native "Who to follow" module that
 * {@link DailyQuotaFilter} keeps appended; this card only owns the checkmark + message.
 *
 * <p>Attaching this card into the timeline's view hierarchy is performed by a timeline-view hook
 * (see DAILY_QUOTA_DESIGN.md); {@link #attachTo} is the entry point that hook calls.
 */
public final class QuotaEndCardView extends LinearLayout {

    private static final String TAG = "piko_quota_end_card";

    public QuotaEndCardView(Context context, boolean animate) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setTag(TAG);
        int pad = dp(24);
        setPadding(pad, pad, pad, pad);

        CheckmarkView check = new CheckmarkView(context, animate);
        LayoutParams checkParams = new LayoutParams(dp(72), dp(72));
        checkParams.gravity = Gravity.CENTER_HORIZONTAL;
        addView(check, checkParams);

        TextView message = new TextView(context);
        message.setText(StringRef.str("piko_newx_daily_quota_end"));
        message.setTextSize(18);
        message.setTextColor(Color.parseColor("#FF0F1419"));
        message.setGravity(Gravity.CENTER);
        LayoutParams textParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(16);
        textParams.gravity = Gravity.CENTER_HORIZONTAL;
        addView(message, textParams);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Removes any previous card and appends a fresh one to {@code parent}. */
    public static QuotaEndCardView attachTo(android.view.ViewGroup parent, boolean animate) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (TAG.equals(child.getTag())) parent.removeViewAt(i);
        }
        QuotaEndCardView card = new QuotaEndCardView(parent.getContext(), animate);
        parent.addView(card, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** Circular checkmark with an optional one-shot reveal animation. */
    private static final class CheckmarkView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path circle = new Path();
        private final Path check = new Path();
        private final Path drawn = new Path();
        private final PathMeasure measure = new PathMeasure();
        private float progress;

        CheckmarkView(Context context, boolean animate) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.parseColor("#FF00BA7C"));
            progress = animate ? 0f : 1f;
            if (animate) {
                ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(650L);
                animator.setInterpolator(new DecelerateInterpolator());
                animator.addUpdateListener(a -> {
                    progress = (float) a.getAnimatedValue();
                    invalidate();
                });
                animator.start();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float stroke = Math.max(2f, w * 0.06f);
            paint.setStrokeWidth(stroke);
            float r = Math.min(w, h) / 2f - stroke;
            circle.reset();
            circle.addCircle(w / 2f, h / 2f, r, Path.Direction.CW);
            check.reset();
            check.moveTo(w * 0.30f, h * 0.52f);
            check.lineTo(w * 0.45f, h * 0.66f);
            check.lineTo(w * 0.72f, h * 0.36f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float circlePhase = Math.min(1f, progress / 0.6f);
            float checkPhase = Math.max(0f, (progress - 0.6f) / 0.4f);
            measure.setPath(circle, false);
            drawn.reset();
            measure.getSegment(0f, measure.getLength() * circlePhase, drawn, true);
            canvas.drawPath(drawn, paint);
            if (checkPhase > 0f) {
                measure.setPath(check, false);
                drawn.reset();
                measure.getSegment(0f, measure.getLength() * checkPhase, drawn, true);
                canvas.drawPath(drawn, paint);
            }
        }
    }
}
