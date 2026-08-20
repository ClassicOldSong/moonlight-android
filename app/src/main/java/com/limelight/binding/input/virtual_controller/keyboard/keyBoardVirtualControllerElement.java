/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.ui.AppDialog;
import com.limelight.binding.input.virtual_controller.VirtualController;

import org.json.JSONException;
import org.json.JSONObject;

public abstract class keyBoardVirtualControllerElement extends View {
    protected static boolean _PRINT_DEBUG_INFORMATION = false;

    public static final int EID_DPAD = 1;
    public static final int EID_LT = 2;
    public static final int EID_RT = 3;
    public static final int EID_LB = 4;
    public static final int EID_RB = 5;
    public static final int EID_A = 6;
    public static final int EID_B = 7;
    public static final int EID_X = 8;
    public static final int EID_Y = 9;
    public static final int EID_BACK = 10;
    public static final int EID_START = 11;
    public static final int EID_LS = 12;
    public static final int EID_RS = 13;
    public static final int EID_LSB = 14;
    public static final int EID_RSB = 15;

    protected KeyBoardController virtualController;
    protected final String elementId;

    private final Paint paint = new Paint();

    private int normalColor = 0xF0888888;
    protected int pressedColor = 0xA3DCDCDE;
    private int configMoveColor = 0xF0FF0000;
    private int configResizeColor = 0xF0FF00FF;
    private int configSelectedColor = 0xF000FF00;

    private int configDisabledColor = 0xF0AAAAAA;

    protected int startSize_x;
    protected int startSize_y;

    float position_pressed_x = 0;
    float position_pressed_y = 0;

    public boolean enabled = true;
    public boolean hidden = false;

    private enum Mode {
        Normal,
        Resize,
        Move
    }

    private Mode currentMode = Mode.Normal;

    private int lastMoveX;
    private int lastMoveY;

    protected keyBoardVirtualControllerElement(KeyBoardController controller, Context context, String elementId) {
        super(context);

        this.virtualController = controller;
        this.elementId = elementId;
    }

    protected void moveElement(int pressed_x, int pressed_y, int x, int y) {
        int newPos_x = (int) getX() + x - pressed_x;
        int newPos_y = (int) getY() + y - pressed_y;

        // Save last position for potential resize on ACTION_UP
        lastMoveX = newPos_x;
        lastMoveY = newPos_y;

        // Only apply snapping in move mode
        if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.MoveButtons) {
            // Convert other elements to array for snapping calculation
            View[] otherViews = new View[virtualController.getElements().size() - 1];
            int index = 0;
            for (keyBoardVirtualControllerElement element : virtualController.getElements()) {
                if (element != this) {
                    otherViews[index++] = element;
                }
            }

            // Calculate snapped position without resize during movement
            LayoutSnappingHelper.SnapResult snapResult = LayoutSnappingHelper.calculateSnappedPosition(
                this, otherViews, newPos_x, newPos_y
            );

            newPos_x = snapResult.newX;
            newPos_y = snapResult.newY;

            // Provide haptic feedback if snapping occurred
            if (snapResult.didSnap || snapResult.didAdjustSpacing) {
                virtualController.vibrate(KeyEvent.ACTION_DOWN);
            }
        }

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();

        layoutParams.leftMargin = newPos_x > 0 ? newPos_x : 0;
        layoutParams.topMargin = newPos_y > 0 ? newPos_y : 0;
        layoutParams.rightMargin = 0;
        layoutParams.bottomMargin = 0;

        requestLayout();
    }

    protected void resizeElement(int pressed_x, int pressed_y, int width, int height) {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();

        int newHeight = height + (startSize_y - pressed_y);
        int newWidth = width + (startSize_x - pressed_x);

        layoutParams.height = newHeight > 20 ? newHeight : 20;
        layoutParams.width = newWidth > 20 ? newWidth : 20;

        requestLayout();
    }

    protected void checkAndApplyResize() {
        if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.MoveButtons) {
            // Convert other elements to array for overlap check
            View[] otherViews = new View[virtualController.getElements().size() - 1];
            int index = 0;
            for (keyBoardVirtualControllerElement element : virtualController.getElements()) {
                if (element != this) {
                    otherViews[index++] = element;
                }
            }

            // Check final position for resize
            LayoutSnappingHelper.SnapResult snapResult = LayoutSnappingHelper.calculateSnappedPosition(
                this, otherViews, lastMoveX, lastMoveY
            );

            if (snapResult.didResize) {
                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
                layoutParams.width = snapResult.newWidth;
                layoutParams.height = snapResult.newHeight;
                virtualController.vibrate(KeyEvent.ACTION_DOWN);
                requestLayout();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        onElementDraw(canvas);

        if (currentMode != Mode.Normal) {
            paint.setColor(configSelectedColor);
            paint.setStrokeWidth(getDefaultStrokeWidth());
            paint.setStyle(Paint.Style.STROKE);

            canvas.drawRect(paint.getStrokeWidth(), paint.getStrokeWidth(),
                    getWidth()-paint.getStrokeWidth(), getHeight()-paint.getStrokeWidth(),
                    paint);
        }

        super.onDraw(canvas);
    }

    /*
    protected void actionShowNormalColorChooser() {
        AmbilWarnaDialog colorDialog = new AmbilWarnaDialog(getContext(), normalColor, true, new AmbilWarnaDialog.OnAmbilWarnaListener() {
            @Override
            public void onCancel(AmbilWarnaDialog dialog)
            {}

            @Override
            public void onOk(AmbilWarnaDialog dialog, int color) {
                normalColor = color;
                invalidate();
            }
        });
        colorDialog.show();
    }

    protected void actionShowPressedColorChooser() {
        AmbilWarnaDialog colorDialog = new AmbilWarnaDialog(getContext(), normalColor, true, new AmbilWarnaDialog.OnAmbilWarnaListener() {
            @Override
            public void onCancel(AmbilWarnaDialog dialog) {
            }

            @Override
            public void onOk(AmbilWarnaDialog dialog, int color) {
                pressedColor = color;
                invalidate();
            }
        });
        colorDialog.show();
    }
    */

    protected void actionEnableMove() {
        currentMode = Mode.Move;
    }

    protected void actionEnableResize() {
        currentMode = Mode.Resize;
    }

    protected void actionCancel() {
        currentMode = Mode.Normal;
        invalidate();
    }

    protected int getDefaultColor() {
        if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.MoveButtons)
            return configMoveColor;
        else if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.ResizeButtons)
            return configResizeColor;
        else if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.DisableEnableButtons)
            return enabled ? configSelectedColor: configDisabledColor;
        else
            return normalColor;
    }

    protected int getDefaultStrokeWidth() {
        DisplayMetrics screen = getResources().getDisplayMetrics();
        return (int)(screen.heightPixels*0.004f);
    }

    protected void showConfigurationDialog() {
        CharSequence[] functions = new CharSequence[]{"Move", "Resize", "Cancel"};
        AppDialog.builder(getContext())
                .setTitle("Configuration")
                .setItems(functions, (dialog, which) -> {
                    if (which == 0) {
                        actionEnableMove();
                    } else if (which == 1) {
                        actionEnableResize();
                    } else {
                        actionCancel();
                    }
                })
                .show();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Ignore secondary touches on controls
        //
        // NB: We can get an additional pointer down if the user touches a non-StreamView area
        // while also touching an OSC control, even if that pointer down doesn't correspond to
        // an area of the OSC control.
        if (event.getActionIndex() != 0) {
            return true;
        }

        if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.Active) {
            return onElementTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                position_pressed_x = event.getX();
                position_pressed_y = event.getY();
                startSize_x = getWidth();
                startSize_y = getHeight();

                if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.MoveButtons)
                    actionEnableMove();
                else if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.ResizeButtons)
                    actionEnableResize();
                else if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.DisableEnableButtons)
                    actionDisableEnableButton();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                switch (currentMode) {
                    case Move: {
                        moveElement(
                                (int) position_pressed_x,
                                (int) position_pressed_y,
                                (int) event.getX(),
                                (int) event.getY());
                        break;
                    }
                    case Resize: {
                        resizeElement(
                                (int) position_pressed_x,
                                (int) position_pressed_y,
                                (int) event.getX(),
                                (int) event.getY());
                        break;
                    }
                    case Normal: {
                        break;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                if (currentMode == Mode.Move) {
                    checkAndApplyResize();
                }
                actionCancel();
                return true;
            }
            default: {
            }
        }
        return true;
    }

    abstract protected void onElementDraw(Canvas canvas);

    abstract public boolean onElementTouchEvent(MotionEvent event);

    protected static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
//            System.out.println(text);
        }
    }

    public void setColors(int normalColor, int pressedColor) {
        this.normalColor = normalColor;
        this.pressedColor = pressedColor;

        invalidate();
    }


    public void setOpacity(int opacity) {
        int hexOpacity = opacity * 255 / 100;
        this.normalColor = (hexOpacity << 24) | (normalColor & 0x00FFFFFF);
        this.pressedColor = (hexOpacity << 24) | (pressedColor & 0x00FFFFFF);

        invalidate();
    }

    protected final float getPercent(float value, float percent) {
        return value / 100 * percent;
    }

    protected final int getCorrectWidth() {
        return getWidth() > getHeight() ? getHeight() : getWidth();
    }


    public JSONObject getConfiguration() throws JSONException {
        JSONObject configuration = new JSONObject();

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();

        configuration.put("LEFT", layoutParams.leftMargin);
        configuration.put("TOP", layoutParams.topMargin);
        configuration.put("WIDTH", layoutParams.width);
        configuration.put("HEIGHT", layoutParams.height);
        configuration.put("ENABLED", enabled);
        configuration.put("HIDDEN", hidden);
        return configuration;
    }

    public void loadConfiguration(JSONObject configuration) throws JSONException {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();

        layoutParams.leftMargin = configuration.getInt("LEFT");
        layoutParams.topMargin = configuration.getInt("TOP");
        layoutParams.width = configuration.getInt("WIDTH");
        layoutParams.height = configuration.getInt("HEIGHT");
        enabled = configuration.getBoolean("ENABLED");
        hidden = configuration.optBoolean("HIDDEN", false);
        
        // Only hide if not in configuration mode
        if (virtualController.getControllerMode() != KeyBoardController.ControllerMode.DisableEnableButtons) {
            setVisibility(!hidden && enabled ? VISIBLE : GONE);
        } else {
            setVisibility(!hidden ? VISIBLE : GONE);
        }
        requestLayout();
    }

    protected void actionDisableEnableButton() {
        enabled = !enabled;
        // In configuration mode, keep the button visible
        if (!hidden && virtualController.getControllerMode() != KeyBoardController.ControllerMode.DisableEnableButtons) {
            setVisibility(enabled ? VISIBLE : GONE);
        }
        invalidate(); // Redraw to show enabled/disabled state
    }

}
