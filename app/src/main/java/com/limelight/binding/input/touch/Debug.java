package com.limelight.binding.input.touch;

import java.text.MessageFormat;

public class Debug {
    public static int maxCount = 0;
    public static String actions = "";

    public static void format(String pattern, Object... arguments) {
        actions = MessageFormat.format(pattern, arguments);
    }
}