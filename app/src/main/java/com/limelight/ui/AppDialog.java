package com.limelight.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.text.method.LinkMovementMethod;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Application-owned modal UI that is attached to an activity's content view.
 *
 * <p>This class never creates a Dialog, PopupWindow, or another Android window. It is suitable for
 * vendor Android builds where framework dialog contents are missing or incorrectly measured.</p>
 */
public final class AppDialog {
    public interface ButtonAction {
        /** @return true to dismiss the overlay, or false to keep it open. */
        boolean onClick(@NonNull AppDialog dialog);
    }

    public interface ItemAction {
        void onClick(@NonNull AppDialog dialog, int which);
    }

    public interface MultiChoiceAction {
        void onClick(@NonNull AppDialog dialog, int which, boolean checked);
    }

    private static final int MAX_CARD_WIDTH_DP = 640;
    private static final int MIN_SCREEN_MARGIN_DP = 24;
    private static final Map<AppCompatActivity, AppDialog> SHOWN_DIALOGS = new WeakHashMap<>();

    private final AppCompatActivity activity;
    private final FrameLayout host;
    private final FrameLayout overlay;
    private final TextView messageView;
    private final View previousFocus;
    private final List<BackgroundFocusState> backgroundFocusStates = new ArrayList<>();
    private final OnBackPressedCallback backCallback;
    private final boolean cancelable;
    private final Runnable onCancel;
    private final Runnable onDismiss;
    private volatile boolean showing = true;

    private AppDialog(Builder builder) {
        activity = builder.activity;
        cancelable = builder.cancelable;
        onCancel = builder.onCancel;
        onDismiss = builder.onDismiss;
        host = activity.findViewById(android.R.id.content);

        synchronized (SHOWN_DIALOGS) {
            AppDialog existing = SHOWN_DIALOGS.get(activity);
            if (existing != null) {
                existing.dismiss();
            }
            SHOWN_DIALOGS.put(activity, this);
        }

        View focusedView = activity.getCurrentFocus();
        previousFocus = focusedView != null ? focusedView : host.findFocus();

        overlay = (FrameLayout) LayoutInflater.from(activity)
                .inflate(R.layout.app_dialog_overlay, host, false);
        View card = overlay.findViewById(R.id.app_dialog_card);
        TextView titleView = overlay.findViewById(R.id.app_dialog_title);
        TextView closeView = overlay.findViewById(R.id.app_dialog_close);
        messageView = overlay.findViewById(R.id.app_dialog_message);
        FrameLayout customContainer = overlay.findViewById(R.id.app_dialog_custom_container);
        LinearLayout itemsContainer = overlay.findViewById(R.id.app_dialog_items);
        LinearLayout actionsContainer = overlay.findViewById(R.id.app_dialog_actions);

        if (builder.title == null || builder.title.length() == 0) {
            titleView.setVisibility(View.GONE);
        } else {
            titleView.setText(builder.title);
        }
        closeView.setVisibility(cancelable ? View.VISIBLE : View.GONE);
        closeView.setOnClickListener(view -> cancel());

        if (builder.message == null || builder.message.length() == 0) {
            messageView.setVisibility(View.GONE);
        } else {
            messageView.setText(builder.message);
            messageView.setMovementMethod(LinkMovementMethod.getInstance());
        }

        if (builder.customView != null) {
            detach(builder.customView);
            customContainer.addView(builder.customView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            customContainer.setVisibility(View.VISIBLE);
        }

        View initialFocus = null;
        if (builder.items != null) {
            itemsContainer.setVisibility(View.VISIBLE);
            for (int i = 0; i < builder.items.length; i++) {
                final int index = i;
                View item = LayoutInflater.from(activity)
                        .inflate(R.layout.app_dialog_item, itemsContainer, false);
                TextView indicator = item.findViewById(R.id.app_dialog_item_indicator);
                TextView label = item.findViewById(R.id.app_dialog_item_label);
                label.setText(builder.items[i]);

                boolean checked = builder.checkedItems != null &&
                        index < builder.checkedItems.length && builder.checkedItems[index];
                if (builder.choiceMode == ChoiceMode.NONE) {
                    indicator.setVisibility(View.GONE);
                } else {
                    updateIndicator(indicator, checked, builder.choiceMode == ChoiceMode.MULTI);
                    item.setSelected(checked);
                }
                item.setContentDescription(builder.items[i]);
                item.setOnClickListener(view -> {
                    if (builder.choiceMode == ChoiceMode.MULTI) {
                        boolean newChecked = !builder.checkedItems[index];
                        builder.checkedItems[index] = newChecked;
                        view.setSelected(newChecked);
                        updateIndicator(indicator, newChecked, true);
                        if (builder.multiChoiceAction != null) {
                            builder.multiChoiceAction.onClick(this, index, newChecked);
                        }
                    } else {
                        if (builder.itemAction != null) {
                            builder.itemAction.onClick(this, index);
                        }
                        dismiss();
                    }
                });
                itemsContainer.addView(item);
                if (initialFocus == null || checked) {
                    initialFocus = item;
                }
            }
        }

        if (initialFocus == null && builder.customView != null) {
            initialFocus = findFirstFocusable(builder.customView);
        }

        addButton(actionsContainer, builder.neutralText, builder.neutralAction);
        addButton(actionsContainer, builder.negativeText, builder.negativeAction);
        View positiveButton = addButton(actionsContainer, builder.positiveText, builder.positiveAction);
        if (actionsContainer.getChildCount() > 0) {
            actionsContainer.setVisibility(View.VISIBLE);
            if (initialFocus == null) {
                initialFocus = positiveButton != null
                        ? positiveButton : actionsContainer.getChildAt(0);
            }
        }
        if (initialFocus == null && cancelable) {
            initialFocus = closeView;
        }

        backCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (cancelable) {
                    cancel();
                }
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, backCallback);

        overlay.setOnClickListener(view -> {
            if (builder.canceledOnTouchOutside) {
                cancel();
            }
        });
        card.setOnClickListener(view -> { /* Consume clicks inside the card. */ });
        overlay.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                if (cancelable) {
                    cancel();
                }
                return true;
            }
            return false;
        });

        ViewCompat.setOnApplyWindowInsetsListener(overlay, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int margin = dp(MIN_SCREEN_MARGIN_DP);
            view.setPadding(Math.max(margin, bars.left), Math.max(margin, bars.top),
                    Math.max(margin, bars.right), Math.max(margin, bars.bottom));
            return insets;
        });
        overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                // Activity teardown can detach the hierarchy without an explicit dismiss call.
                finishDismiss(false);
            }
        });
        suspendBackgroundFocus();
        if (previousFocus != null) {
            previousFocus.clearFocus();
        }
        enableTouchModeFocus(overlay);
        host.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ViewCompat.requestApplyInsets(overlay);

        overlay.post(() -> {
            int availableWidth = Math.max(0,
                    overlay.getWidth() - overlay.getPaddingLeft() - overlay.getPaddingRight());
            ViewGroup.LayoutParams params = card.getLayoutParams();
            params.width = Math.min(availableWidth, dp(MAX_CARD_WIDTH_DP));
            card.setLayoutParams(params);

            // Wait for the width-constrained card to be measured before limiting its height.
            card.post(() -> {
                int availableHeight = Math.max(0,
                        overlay.getHeight() - overlay.getPaddingTop() - overlay.getPaddingBottom());
                if (availableHeight > 0 && card.getMeasuredHeight() > availableHeight) {
                    ViewGroup.LayoutParams cardParams = card.getLayoutParams();
                    cardParams.height = availableHeight;
                    card.setLayoutParams(cardParams);

                    // Keep the header, custom content, and actions visible while long menus scroll.
                    View itemsScroll = overlay.findViewById(R.id.app_dialog_items_scroll);
                    LinearLayout.LayoutParams scrollParams =
                            (LinearLayout.LayoutParams) itemsScroll.getLayoutParams();
                    scrollParams.height = 0;
                    scrollParams.weight = 1;
                    itemsScroll.setLayoutParams(scrollParams);
                }
            });
        });

        View focus = initialFocus != null ? initialFocus : overlay;
        requestDialogFocus(focus);
        focus.post(() -> {
            requestDialogFocus(focus);
            focus.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        });
    }

    /** Inflates custom dialog content with layout parameters resolved against a neutral parent. */
    @NonNull
    public static View inflateContent(@NonNull Context context, @LayoutRes int layoutResource) {
        FrameLayout parent = new FrameLayout(context);
        return LayoutInflater.from(context).inflate(layoutResource, parent, false);
    }

    public static Builder builder(@NonNull Context context) {
        return new Builder(context);
    }

    public void dismiss() {
        if (!isMainThread()) {
            activity.runOnUiThread(this::dismiss);
            return;
        }
        finishDismiss(true);
    }

    private void finishDismiss(boolean removeOverlay) {
        if (!showing) {
            return;
        }
        showing = false;
        backCallback.remove();
        if (removeOverlay && overlay.getParent() == host) {
            host.removeView(overlay);
        }
        restoreBackgroundFocus();
        if (removeOverlay && previousFocus != null && previousFocus.isAttachedToWindow()) {
            previousFocus.requestFocus();
        }
        synchronized (SHOWN_DIALOGS) {
            if (SHOWN_DIALOGS.get(activity) == this) {
                SHOWN_DIALOGS.remove(activity);
            }
        }
        if (onDismiss != null) {
            onDismiss.run();
        }
    }

    public void cancel() {
        if (!isMainThread()) {
            activity.runOnUiThread(this::cancel);
            return;
        }
        if (!cancelable || !showing) {
            return;
        }
        if (onCancel != null) {
            onCancel.run();
        }
        dismiss();
    }

    public boolean isShowing() {
        return showing;
    }

    public void setMessage(@Nullable CharSequence message) {
        if (!isMainThread()) {
            activity.runOnUiThread(() -> setMessage(message));
            return;
        }
        if (!showing) {
            return;
        }
        messageView.setText(message);
        messageView.setVisibility(message == null || message.length() == 0
                ? View.GONE : View.VISIBLE);
    }

    @NonNull
    public View getOverlayView() {
        return overlay;
    }

    private View addButton(LinearLayout container, CharSequence text, ButtonAction action) {
        if (text == null) {
            return null;
        }
        AppCompatTextView button = new AppCompatTextView(activity);
        button.setText(text);
        button.setTextColor(ContextCompat.getColor(activity, R.color.custom_list_text_primary));
        button.setTextSize(16);
        button.setGravity(android.view.Gravity.CENTER);
        button.setFocusable(true);
        button.setClickable(true);
        button.setMinWidth(dp(72));
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackgroundResource(R.drawable.custom_list_item_background);
        button.setOnClickListener(view -> {
            if (action == null || action.onClick(this)) {
                dismiss();
            }
        });
        container.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void updateIndicator(TextView indicator, boolean checked, boolean multiChoice) {
        indicator.setText(checked
                ? (multiChoice ? R.string.app_dialog_multi_selected : R.string.custom_list_selected)
                : (multiChoice ? R.string.app_dialog_multi_unselected : R.string.custom_list_unselected));
        indicator.setTextColor(ContextCompat.getColor(activity,
                checked ? R.color.custom_list_accent : R.color.custom_list_text_secondary));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void enableTouchModeFocus(@NonNull View view) {
        if (view.isFocusable()) {
            view.setFocusableInTouchMode(true);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                enableTouchModeFocus(group.getChildAt(i));
            }
        }
    }

    private static void requestDialogFocus(@NonNull View view) {
        if (!view.requestFocus()) {
            view.requestFocusFromTouch();
        }
    }

    private void suspendBackgroundFocus() {
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            BackgroundFocusState state = new BackgroundFocusState(child);
            backgroundFocusStates.add(state);
            child.setFocusableInTouchMode(false);
            child.setFocusable(false);
            if (child instanceof ViewGroup) {
                ((ViewGroup) child).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
        }
    }

    private void restoreBackgroundFocus() {
        for (BackgroundFocusState state : backgroundFocusStates) {
            state.restore();
        }
        backgroundFocusStates.clear();
    }

    private static final class BackgroundFocusState {
        private final View view;
        private final boolean focusable;
        private final boolean focusableInTouchMode;
        private final int descendantFocusability;

        BackgroundFocusState(View view) {
            this.view = view;
            focusable = view.isFocusable();
            focusableInTouchMode = view.isFocusableInTouchMode();
            descendantFocusability = view instanceof ViewGroup
                    ? ((ViewGroup) view).getDescendantFocusability() : -1;
        }

        void restore() {
            if (view instanceof ViewGroup) {
                ((ViewGroup) view).setDescendantFocusability(descendantFocusability);
            }
            view.setFocusable(focusable);
            view.setFocusableInTouchMode(focusableInTouchMode);
        }
    }

    private enum ChoiceMode {
        NONE,
        SINGLE,
        MULTI
    }

    public static final class Builder {
        private final AppCompatActivity activity;
        private CharSequence title;
        private CharSequence message;
        private View customView;
        private CharSequence[] items;
        private boolean[] checkedItems;
        private ChoiceMode choiceMode = ChoiceMode.NONE;
        private ItemAction itemAction;
        private MultiChoiceAction multiChoiceAction;
        private CharSequence positiveText;
        private CharSequence negativeText;
        private CharSequence neutralText;
        private ButtonAction positiveAction;
        private ButtonAction negativeAction;
        private ButtonAction neutralAction;
        private boolean cancelable = true;
        private boolean canceledOnTouchOutside = true;
        private Runnable onCancel;
        private Runnable onDismiss;

        private Builder(Context context) {
            activity = findActivity(context);
            if (activity == null) {
                throw new IllegalArgumentException(
                        "AppDialog requires a context backed by AppCompatActivity");
            }
        }

        public Builder setTitle(@Nullable CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder setTitle(int titleRes) {
            return setTitle(activity.getText(titleRes));
        }

        public Builder setMessage(@Nullable CharSequence message) {
            this.message = message;
            return this;
        }

        public Builder setMessage(int messageRes) {
            return setMessage(activity.getText(messageRes));
        }

        public Builder setView(@Nullable View customView) {
            this.customView = customView;
            return this;
        }

        public Builder setItems(@NonNull CharSequence[] items, @Nullable ItemAction action) {
            this.items = items;
            itemAction = action;
            choiceMode = ChoiceMode.NONE;
            return this;
        }

        public Builder setSingleChoiceItems(@NonNull CharSequence[] items, int checkedItem,
                                            @Nullable ItemAction action) {
            this.items = items;
            checkedItems = new boolean[items.length];
            if (checkedItem >= 0 && checkedItem < items.length) {
                checkedItems[checkedItem] = true;
            }
            itemAction = action;
            choiceMode = ChoiceMode.SINGLE;
            return this;
        }

        public Builder setMultiChoiceItems(@NonNull CharSequence[] items,
                                           @NonNull boolean[] checkedItems,
                                           @Nullable MultiChoiceAction action) {
            if (items.length != checkedItems.length) {
                throw new IllegalArgumentException("Items and checked state lengths must match");
            }
            this.items = items;
            this.checkedItems = checkedItems;
            multiChoiceAction = action;
            choiceMode = ChoiceMode.MULTI;
            return this;
        }

        public Builder setPositiveButton(@Nullable CharSequence text, @Nullable ButtonAction action) {
            positiveText = text;
            positiveAction = action;
            return this;
        }

        public Builder setPositiveButton(int textRes, @Nullable ButtonAction action) {
            return setPositiveButton(activity.getText(textRes), action);
        }

        public Builder setNegativeButton(@Nullable CharSequence text, @Nullable ButtonAction action) {
            negativeText = text;
            negativeAction = action;
            return this;
        }

        public Builder setNegativeButton(int textRes, @Nullable ButtonAction action) {
            return setNegativeButton(activity.getText(textRes), action);
        }

        public Builder setNeutralButton(@Nullable CharSequence text, @Nullable ButtonAction action) {
            neutralText = text;
            neutralAction = action;
            return this;
        }

        public Builder setCancelable(boolean cancelable) {
            this.cancelable = cancelable;
            if (!cancelable) {
                canceledOnTouchOutside = false;
            }
            return this;
        }

        public Builder setCanceledOnTouchOutside(boolean canceledOnTouchOutside) {
            this.canceledOnTouchOutside = canceledOnTouchOutside;
            return this;
        }

        public Builder setOnCancel(@Nullable Runnable onCancel) {
            this.onCancel = onCancel;
            return this;
        }

        public Builder setOnDismiss(@Nullable Runnable onDismiss) {
            this.onDismiss = onDismiss;
            return this;
        }

        public AppDialog show() {
            return new AppDialog(this);
        }
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    @Nullable
    private static View findFirstFocusable(@NonNull View view) {
        if (view.isFocusable() && view.getVisibility() == View.VISIBLE) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View focusable = findFirstFocusable(group.getChildAt(i));
                if (focusable != null) {
                    return focusable;
                }
            }
        }
        return null;
    }

    private static AppCompatActivity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof AppCompatActivity) {
                return (AppCompatActivity) context;
            }
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) {
                break;
            }
            context = base;
        }
        return null;
    }

    private static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }
}
