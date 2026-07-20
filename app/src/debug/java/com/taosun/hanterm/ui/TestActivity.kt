package com.taosun.hanterm.ui

import androidx.activity.ComponentActivity

/**
 * Empty host activity for Compose UI tests under Robolectric.
 *
 * The production [com.taosun.hanterm.MainActivity] calls [setContent] in
 * [onCreate]; using it as the compose-rule host conflicts with the test's
 * own [setContent]. This no-op activity avoids that side effect.
 *
 * It lives in the `debug` source set so the merged debug manifest can declare
 * it without shipping an exported test activity in release builds.
 */
class TestActivity : ComponentActivity()
