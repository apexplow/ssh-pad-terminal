# HanTerm R8 / ProGuard keep rules
# ---------------------------------
# Sprint Store / Issue #33 — paired with release { isMinifyEnabled = true }
# in app/build.gradle.kts. R8 default rules (proguard-android-optimize.txt)
# strip reflection-driven code; the libraries below NEED to survive intact
# or sshj + BouncyCastle + Termux JNI will break in mysterious ways at
# runtime. Each block is scoped to the package that actually uses
# reflection so we don't bloat the APK beyond what's load-bearing.

# --- SSHJ (com.hierynomus:sshj:0.40.0) ------------------------------
# sshj negotiates ciphers / MACs / key-exchanges via reflection on the
# ServiceLoader-style registries under com.hierynomus.sshj.transport,
# .userauth, .key, .signature, .mac, .cipher. R8 inlines those references
# away unless we keep the package wholesale. Members are public API
# anyway — keeping them doesn't leak anything we don't already ship.
-keep class com.hierynomus.sshj.** { *; }
-keep interface com.hierynomus.sshj.** { *; }
-dontwarn com.hierynomus.sshj.**

# sshj's Ed25519 implementation calls KeyFactory.getInstance("Ed25519"),
# which is provider-lookup — keep the JCA key/spec classes it touches.
-keep class java.security.spec.EdECPrivateKeySpec { *; }
-keep class java.security.spec.EdECPublicKeySpec { *; }
-dontwarn java.security.spec.EdECPrivateKeySpec
-dontwarn java.security.spec.EdECPublicKeySpec

# --- BouncyCastle (org.bouncycastle:bcprov-jdk18on) -----------------
# BouncyCastleBootstrap.ensureRegistered() inserts a BC JCE provider
# looked up by name ("BC"). If R8 strips BC classes that BC itself
# reflectively registers, KeyFactory.getInstance("Ed25519") returns
# a hollow provider and Ed25519 auth falls over silently. Wholesale
# keep is the documented escape hatch and BC is a debug/release build
# dependency, so the APK size cost is bounded.
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Termux terminal-emulator + terminal-view ------------------------
# com.termux.terminal.TerminalEmulator holds native bindings; the JNI
# bridge loads symbols by name. The view package uses reflection on
# the renderer interface to dispatch paint() callbacks. Both survive
# R8 cleanly only with explicit keep. (We do NOT modify their internals
# — see CLAUDE.md hard constraint; we only tell R8 not to strip them.)
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-dontwarn com.termux.terminal.**
-dontwarn com.termux.view.**

# --- kotlinx.coroutines debug agent ---------------------------------
# The debug agent is loaded reflectively by name when coroutines are
# configured with CoroutineExceptionHandler debug mode. We don't ship
# that handler in release builds, but R8 still complains; suppress.
-dontwarn kotlinx.coroutines.debug.**
-dontwarn kotlinx.coroutines.flow.**

# --- Compose runtime + Material3 ------------------------------------
# Compose's compiler plugin emits stable markers and lambdas that the
# R8 defaults handle, but Compose runtime ships a couple of services
# (SavedStateRegistryController, ViewModel factory) that the defaults
# occasionally strip when minification is aggressive. Silence the
# warnings; the keep rules themselves are inherited from the BOM.
-dontwarn androidx.compose.runtime.**
-dontwarn androidx.compose.ui.tooling.**