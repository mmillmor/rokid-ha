package com.milliesoft.rokid_ha.input

import android.view.KeyEvent

/**
 * Standardized temple touchpad & hardware button gestures on Rokid Glasses (YodaOS-Sprite).
 */
enum class RokidGesture {
    /** Swipe forward/right along the temple -> Next card, next option, or increase value */
    SWIPE_FORWARD,

    /** Swipe backward/left along the temple -> Previous card, previous option, or decrease value */
    SWIPE_BACKWARD,

    /** Single tap on temple touchpad (or DPAD_CENTER / ENTER) -> Select / Toggle / Confirm */
    SINGLE_TAP,

    /** Double tap on temple touchpad (or BACK key) -> Exit adjustment mode / Go back */
    DOUBLE_TAP_BACK,

    /** Long press on temple touchpad or Voice key -> Activate voice command recognition */
    VOICE_TRIGGER
}

object RokidKeyMapper {
    /**
     * Maps YodaOS-Sprite KeyEvents from the Rokid Glasses temple touchpad to [RokidGesture].
     */
    fun mapKeyDown(keyCode: Int, event: KeyEvent?): RokidGesture? {
        if (event != null && event.repeatCount > 0) {
            // Long-press on Enter/Center triggers voice command
            if (event.repeatCount == 1 &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                return RokidGesture.VOICE_TRIGGER
            }
            // Allow repeated swipes for smooth temperature/dimmer adjustment
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP -> RokidGesture.SWIPE_FORWARD

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN -> RokidGesture.SWIPE_BACKWARD

                else -> null
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_MEDIA_NEXT -> RokidGesture.SWIPE_FORWARD

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> RokidGesture.SWIPE_BACKWARD

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> RokidGesture.SINGLE_TAP

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> RokidGesture.DOUBLE_TAP_BACK

            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_F1 -> RokidGesture.VOICE_TRIGGER

            else -> null
        }
    }
}
