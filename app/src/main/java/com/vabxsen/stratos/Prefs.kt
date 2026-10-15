package com.vabxsen.stratos

import android.content.Context
import androidx.core.content.edit

/** Everything the game remembers between launches. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("stratos", Context.MODE_PRIVATE)

    var best: Int
        get() = sp.getInt("best", 0)
        set(v) = sp.edit { putInt("best", v) }

    var muted: Boolean
        get() = sp.getBoolean("muted", false)
        set(v) = sp.edit { putBoolean("muted", v) }

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit { putBoolean("haptics", v) }

    var climbs: Int
        get() = sp.getInt("climbs", 0)
        set(v) = sp.edit { putInt("climbs", v) }
}
