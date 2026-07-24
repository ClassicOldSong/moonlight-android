package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.KeyMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class KeyboardTranslatorTest {
    private KeyboardTranslator translator;

    @Before
    public void setUp() {
        translator = new KeyboardTranslator(new PreferenceConfiguration());
    }

    @Test
    public void translatesJapaneseAndroidKeyCodes() {
        assertEquals(keyMap(KeyMapper.VK_OEM_AUTO),
                translator.translate(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, 0, 0));
        assertEquals(keyMap(KeyMapper.VK_CONVERT),
                translator.translate(KeyEvent.KEYCODE_HENKAN, 0, 0));
        assertEquals(keyMap(KeyMapper.VK_NONCONVERT),
                translator.translate(KeyEvent.KEYCODE_MUHENKAN, 0, 0));
        assertEquals(keyMap(KeyMapper.VK_OEM_COPY),
                translator.translate(KeyEvent.KEYCODE_KATAKANA_HIRAGANA, 0, 0));
    }

    @Test
    public void translatesJapaneseLinuxScanCodesAsFallback() {
        assertEquals(keyMap(KeyMapper.VK_OEM_AUTO),
                translator.translate(KeyEvent.KEYCODE_UNKNOWN, KeyMapper.KEY_ZENKAKUHANKAKU, 0));
        assertEquals(keyMap(KeyMapper.VK_OEM_FINISH),
                translator.translate(KeyEvent.KEYCODE_UNKNOWN, KeyMapper.KEY_KATAKANA, 0));
        assertEquals(keyMap(KeyMapper.VK_OEM_COPY),
                translator.translate(KeyEvent.KEYCODE_UNKNOWN, KeyMapper.KEY_HIRAGANA, 0));
        assertEquals(keyMap(KeyMapper.VK_CONVERT),
                translator.translate(KeyEvent.KEYCODE_UNKNOWN, KeyMapper.KEY_HENKAN, 0));
        assertEquals(keyMap(KeyMapper.VK_OEM_COPY),
                translator.translate(KeyEvent.KEYCODE_UNKNOWN, KeyMapper.KEY_KATAKANAHIRAGANA, 0));
        assertEquals(keyMap(KeyMapper.VK_NONCONVERT),
                translator.translate(KeyEvent.KEYCODE_UNKNOWN, KeyMapper.KEY_MUHENKAN, 0));
    }

    @Test
    public void keepsJapaneseImeKeysLayoutSpecific() {
        assertTrue(KeyboardTranslator.isJapaneseImeKey(KeyEvent.KEYCODE_ZENKAKU_HANKAKU));
        assertTrue(KeyboardTranslator.isJapaneseImeKey(KeyEvent.KEYCODE_HENKAN));
        assertTrue(KeyboardTranslator.isJapaneseImeKey(KeyEvent.KEYCODE_MUHENKAN));
        assertTrue(KeyboardTranslator.isJapaneseImeKey(KeyEvent.KEYCODE_KATAKANA_HIRAGANA));
        assertFalse(KeyboardTranslator.isJapaneseImeKey(KeyEvent.KEYCODE_A));

        assertFalse(translator.hasNormalizedMapping(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, 0));
        assertFalse(translator.hasNormalizedMapping(KeyEvent.KEYCODE_HENKAN, 0));
        assertFalse(translator.hasNormalizedMapping(KeyEvent.KEYCODE_MUHENKAN, 0));
        assertFalse(translator.hasNormalizedMapping(KeyEvent.KEYCODE_KATAKANA_HIRAGANA, 0));
    }

    @Test
    public void keepsCommonKeyboardMappingsUnchanged() {
        assertEquals(keyMap(KeyMapper.VK_A), translator.translate(KeyEvent.KEYCODE_A, 30, 0));
        assertEquals(keyMap(KeyMapper.VK_LCONTROL), translator.translate(KeyEvent.KEYCODE_CTRL_LEFT, 29, 0));
        assertEquals(keyMap(KeyMapper.VK_LMENU), translator.translate(KeyEvent.KEYCODE_ALT_LEFT, 56, 0));
        assertEquals(keyMap(KeyMapper.VK_LWIN), translator.translate(KeyEvent.KEYCODE_META_LEFT, 125, 0));
        assertEquals(keyMap(KeyMapper.VK_TAB), translator.translate(KeyEvent.KEYCODE_TAB, 15, 0));
    }

    private static short keyMap(int virtualKey) {
        return (short) (0x8000 | virtualKey);
    }
}
