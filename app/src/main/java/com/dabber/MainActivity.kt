package com.dabber

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Placeholder entry screen. Onboarding (permissions), model manager, settings and the
 * dictation scratchpad are added in Phase 2. This minimal activity exists so the app
 * builds and launches while the native engine and services are wired up.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = getString(R.string.app_tagline)
            textSize = 20f
            setPadding(48, 96, 48, 48)
        }
        setContentView(tv)
    }
}
