package com.example.sshterminal.terminal

import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalViewCrashTest {
    @Test
    fun test_terminalView_construct_doesNotCrash() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tv = TerminalView(ctx)
        android.util.Log.i("CrashTest", "constructed: emulator=${tv.termuxView.mEmulator}")
    }

    @Test
    fun test_terminalView_onCreateInputConnection_doesNotCrash() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tv = TerminalView(ctx)
        val attrs = EditorInfo()
        val ic = tv.onCreateInputConnection(attrs)
        android.util.Log.i("CrashTest", "InputConnection: $ic")
    }

    @Test
    fun test_terminalView_attachToActivityWindow_doesNotCrash() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tv = TerminalView(ctx)
        // Force measure + layout
        tv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
        )
        tv.layout(0, 0, 800, 1200)
        android.util.Log.i("CrashTest", "after layout: emulator cols=${tv.termuxView.mEmulator?.mColumns} rows=${tv.termuxView.mEmulator?.mRows}")
    }
}
