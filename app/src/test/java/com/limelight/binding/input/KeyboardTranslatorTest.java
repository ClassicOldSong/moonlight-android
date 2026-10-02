package com.limelight.binding.input;

import android.view.KeyEvent;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KeyboardTranslatorTest {
    @Test
    public void zenkakuHankakuTranslatesToVkKanjiRegardlessOfScanCode() {
        KeyboardTranslator translator = new KeyboardTranslator(new PreferenceConfiguration());
        short expected = (short) 0x8019;

        assertEquals(expected, translator.translate(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, 0, -1));
        assertEquals(expected, translator.translate(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, 41, -1));
        assertEquals(expected, translator.translate(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, 85, -1));
    }
}
