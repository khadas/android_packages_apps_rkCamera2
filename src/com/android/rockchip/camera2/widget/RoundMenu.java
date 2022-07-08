package com.android.rockchip.camera2.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;

import com.android.rockchip.camera2.R;
import com.android.rockchip.camera2.util.DataUtils;

public class RoundMenu extends ViewGroup {
    public static final int STATE_COLLAPSE = 0;//展开状态
    public static final int STATE_EXPAND = 1;//收缩状态

    private int collapsedRadius;//收缩时的半径
    private int expandedRadius;//展开时的半径
    private int mRoundColor;//收缩状态时的颜色 / 展开时外圈的颜色
    private int mCenterColor;//展开时中心圆圈的颜色
    private Drawable mCenterDrawable;//中心图标
    private int mItemWidth;//子项的宽高
    private float expandProgress = 0;//当前展开的进度（0-1）
    private int state; //当前状态 （展开 / 收缩）
    private int mDuration; //展开或收缩的动画时长
    private int mItemAnimIntervalTime;//子View之间的动画间隔
    private Point center;
    private Paint mRoundPaint;
    private Paint mCenterPaint;
    private OvalOutline outlineProvider;
    private ValueAnimator mExpandAnimator;
    private ValueAnimator mColorAnimator;

    private onStateListener mStateListener;
    private boolean mCanListenerAnim;
    private boolean mNeedExtend;
    private long mLastClickTime;

    private int mTempTransX = 10;
    private int mCenterIconPadding = 0;//中心图标的pandding

    public RoundMenu(Context context) {
        this(context, null);
    }

    public RoundMenu(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RoundMenu(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RoundMenu(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.RoundelMenu);
        collapsedRadius = ta.getDimensionPixelSize(R.styleable.RoundelMenu_round_menu_collapsedRadius, dp2px(32));
        expandedRadius = ta.getDimensionPixelSize(R.styleable.RoundelMenu_round_menu_expandedRadius, dp2px(120));
        mRoundColor = ta.getColor(R.styleable.RoundelMenu_round_menu_roundColor, Color.GRAY);
        mCenterColor = ta.getColor(R.styleable.RoundelMenu_round_menu_centerColor, Color.parseColor("#ffff8800"));
        mDuration = ta.getInteger(R.styleable.RoundelMenu_round_menu_duration, 400);
        mItemAnimIntervalTime = ta.getInteger(R.styleable.RoundelMenu_round_menu_item_anim_delay, 50);
        mItemWidth = ta.getDimensionPixelSize(R.styleable.RoundelMenu_round_menu_item_width, dp2px(22));
        ta.recycle();

        if (collapsedRadius > expandedRadius) {
            throw new IllegalArgumentException("expandedRadius must bigger than collapsedRadius");
        }

        mRoundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRoundPaint.setColor(mRoundColor);
        mRoundPaint.setStyle(Paint.Style.FILL);
        mCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mCenterPaint.setColor(mRoundColor);
        mCenterPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);

        outlineProvider = new OvalOutline();
        setElevation(dp2px(5));
        center = new Point();
        mCenterDrawable = getResources().getDrawable(R.drawable.ic_close);
        state = STATE_COLLAPSE;

        initAnim();
    }

    private void initAnim() {
        mExpandAnimator = ValueAnimator.ofFloat(0, 0);
        mExpandAnimator.setInterpolator(new OvershootInterpolator());
        mExpandAnimator.setDuration(mDuration);
        mExpandAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                expandProgress = (float) animation.getAnimatedValue();
                mRoundPaint.setAlpha(Math.min(255, (int) (expandProgress * 255)));

                invalidateOutline();
                invalidate();
            }
        });

        mColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), mRoundColor, mCenterColor);
        mColorAnimator.setDuration(mDuration);
        mColorAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mCenterPaint.setColor((Integer) animation.getAnimatedValue());
            }

        });
        mColorAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                if (mCanListenerAnim && null != mStateListener) {
                    mStateListener.collapseEnd();
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (mCanListenerAnim && null != mStateListener) {
                    mStateListener.collapseEnd();
                }
            }
        });
    }

    public float getExpandProgress() {
        return expandProgress;
    }

    public void collapse(boolean animate) {
        state = STATE_COLLAPSE;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setVisibility(View.GONE);
        }
        invalidate();
        if (animate) {
            startCollapseAnimation();
        }
    }


    public void expand(boolean animate) {
        state = STATE_EXPAND;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setVisibility(View.VISIBLE);
        }
        invalidate();
        if (animate) {
            startExpandAnimation();
        } else {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).setAlpha(1);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        measureChildren(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() == 0) {
            return;
        }
        calculateMenuItemPosition();
        for (int i = 0; i < getChildCount(); i++) {
            View item = getChildAt(i);
            item.layout(l + (int) item.getX(),
                    t + (int) item.getY(),
                    l + (int) item.getX() + item.getMeasuredWidth(),
                    t + (int) item.getY() + item.getMeasuredHeight());
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            View item = getChildAt(i);
            item.setVisibility(View.GONE);
            item.setAlpha(0);
            item.setScaleX(1);
            item.setScaleY(1);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mNeedExtend = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mNeedExtend = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Point touchPoint = new Point();
        touchPoint.set((int) event.getX(), (int) event.getY());
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                long clickTime = System.currentTimeMillis();
                if (clickTime - mLastClickTime < DataUtils.LIMIT_DOUBLE_CLICK_TIME) {
                    return super.onTouchEvent(event);
                }
                mLastClickTime = clickTime;
                //计算触摸点与中心点的距离
                double distance = getPointsDistance(touchPoint, center);
                if (state == STATE_EXPAND) {
                    //展开状态下，如果点击区域与中心点的距离不处于子菜单区域
                    if (distance > (collapsedRadius + (expandedRadius - collapsedRadius) * expandProgress)) {
                        collapse(true);//收起菜单
                        return true;
                    } else if (distance < collapsedRadius) {
                        if (!mCanListenerAnim && null != mStateListener) {
                            mStateListener.centerClick();
                        }
                        return true;
                    }
                    //展开状态下，如果点击区域处于子菜单区域，则不消费事件
                    return false;
                } else {
                    //收缩状态下，如果点击区域处于中心圆圈范围内
                    if (distance < collapsedRadius) {
                        expand(true);//展开菜单
                        return true;
                    }
                    //收缩状态下，如果点击区域不在中心圆圈范围内，则不消费事件
                    return false;
                }
            }
        }
        return super.onTouchEvent(event);
    }


    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        setOutlineProvider(outlineProvider);
        int x, y;
        x = w / 2;
        y = h / 2;
        center.set(x, y);
        //中心图标padding设为mCenterIconPadding dp
        mCenterDrawable.setBounds(center.x + mTempTransX - (collapsedRadius - dp2px(mCenterIconPadding)),
                center.y - (collapsedRadius - dp2px(mCenterIconPadding)),
                center.x + mTempTransX + (collapsedRadius - dp2px(mCenterIconPadding)),
                center.y + (collapsedRadius - dp2px(mCenterIconPadding))
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        //绘制放大的圆
        if (expandProgress > 0f) {
            canvas.drawCircle(center.x + mTempTransX, center.y, collapsedRadius + (expandedRadius - collapsedRadius) * expandProgress, mRoundPaint);
        }
        //绘制中间圆
        canvas.drawCircle(center.x + mTempTransX, center.y, collapsedRadius + (collapsedRadius * .2f * expandProgress), mCenterPaint);
        int count = canvas.saveLayer(0, 0, getWidth(), getHeight(), null, Canvas.ALL_SAVE_FLAG);
        //绘制中间的图标
        canvas.rotate(90 * expandProgress, center.x + mTempTransX, center.y);
        mCenterDrawable.draw(canvas);
        canvas.restoreToCount(count);

        if (mNeedExtend) {
            mNeedExtend = false;
            expand(true);
        }
    }

    /**
     * 展开动画
     */
    void startExpandAnimation() {
        mExpandAnimator.setFloatValues(getExpandProgress(), 1f);
        mExpandAnimator.start();

        mColorAnimator.setObjectValues(mColorAnimator.getAnimatedValue() == null ? mRoundColor : mColorAnimator.getAnimatedValue(), mCenterColor);
        mCanListenerAnim = false;
        mColorAnimator.start();

        int delay = mItemAnimIntervalTime;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).animate()
                    .setStartDelay(delay)
                    .setDuration(mDuration)
                    .alphaBy(0f)
                    .scaleXBy(0f)
                    .scaleYBy(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .start();
            delay += mItemAnimIntervalTime;
        }
    }

    /**
     * 收缩动画
     */
    void startCollapseAnimation() {
        mExpandAnimator.setFloatValues(getExpandProgress(), 0f);
        mExpandAnimator.start();

        mColorAnimator.setObjectValues(mColorAnimator.getAnimatedValue() == null ? mCenterColor : mColorAnimator.getAnimatedValue(), mRoundColor);
        mCanListenerAnim = true;
        mColorAnimator.start();

        int delay = mItemAnimIntervalTime;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            getChildAt(i).animate()
                    .setStartDelay(delay)
                    .setDuration(mDuration)
                    .alpha(0)
                    .scaleX(0)
                    .scaleY(0)
                    .start();
            delay += mItemAnimIntervalTime;
        }
    }


    /**
     * 计算每个子菜单的坐标
     */
    private void calculateMenuItemPosition() {
        float itemRadius = (expandedRadius + collapsedRadius) / 2f;
        RectF area = new RectF(
                center.x - itemRadius,
                center.y - itemRadius,
                center.x + itemRadius,
                center.y + itemRadius);
        Path path = new Path();
        path.addArc(area, 90, 360);
        PathMeasure measure = new PathMeasure(path, false);
        float len = measure.getLength();
        int divisor = getChildCount();
        float divider = len / divisor;

        for (int i = 0; i < getChildCount(); i++) {
            float[] itemPoints = new float[2];
            measure.getPosTan(i * divider + divider * 0.5f, itemPoints, null);
            View item = getChildAt(i);
            item.setX((int) itemPoints[0] - mItemWidth / 2);
            item.setY((int) itemPoints[1] - mItemWidth / 2);
        }
    }

    public int getState() {
        return state;
    }

    public void setExpandedRadius(int expandedRadius) {
        this.expandedRadius = expandedRadius;
        requestLayout();
    }


    public void setCollapsedRadius(int collapsedRadius) {
        this.collapsedRadius = collapsedRadius;
        requestLayout();
    }

    public void setRoundColor(int color) {
        this.mRoundColor = color;
        mRoundPaint.setColor(mRoundColor);
        invalidate();
    }

    public void setCenterColor(int color) {
        this.mCenterColor = color;
        mCenterPaint.setColor(color);
        invalidate();
    }

    public void setCenterDrawable(int resId) {
        mCenterDrawable = getResources().getDrawable(resId);
        mCenterDrawable.setBounds(center.x + mTempTransX - (collapsedRadius - dp2px(mCenterIconPadding)),
                center.y - (collapsedRadius - dp2px(mCenterIconPadding)),
                center.x + mTempTransX + (collapsedRadius - dp2px(mCenterIconPadding)),
                center.y + (collapsedRadius - dp2px(mCenterIconPadding))
        );
        invalidate();
    }

    public class OvalOutline extends ViewOutlineProvider {

        public OvalOutline() {
            super();
        }

        @Override
        public void getOutline(View view, Outline outline) {
            int radius = (int) (collapsedRadius + (expandedRadius - collapsedRadius) * expandProgress);
            Rect area = new Rect(
                    center.x - radius,
                    center.y - radius,
                    center.x + radius,
                    center.y + radius);
            outline.setRoundRect(area, radius);
        }
    }


    public static double getPointsDistance(Point a, Point b) {
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public int dp2px(float dpVal) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpVal,
                getContext().getResources().getDisplayMetrics());
    }

    public void setOnStateListener(onStateListener listener) {
        mStateListener = listener;
    }

    public interface onStateListener {
        public void collapseEnd();

        public void centerClick();
    }
}
