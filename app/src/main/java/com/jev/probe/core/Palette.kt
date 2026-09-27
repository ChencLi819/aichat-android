package com.jev.probe.core

import android.graphics.Color

/**
 * The app's brand colours, in one place.
 *
 * Every screen used to hardcode the same blue literal, so a palette change meant
 * hunting through four files. Change [ACCENT] here and the whole app follows.
 */
object Palette {

    /** Primary accent: buttons, selected pills, switches, the bubble. */
    val ACCENT = Color.parseColor("#00B96B")

    /** [ACCENT] as a light fill (the highlighted reply card). */
    val ACCENT_SOFT = Color.parseColor("#E6F8F0")

    /** The floating bubble, slightly translucent so the chat shows through. */
    val BUBBLE = Color.argb(235, 0, 185, 107)
}
