package com.apexplow.hanterm.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.apexplow.hanterm.data.prefs.AppPreferences
import com.apexplow.hanterm.data.profile.ConnectionProfile
import com.apexplow.hanterm.ssh.ConnectionRuntime

/**
 * Factory for the top-level [HanTermAppViewModel] (Issue #41).
 *
 * Constructed **inside** `HanTermApp` from the lifecycle collaborators
 * resolved at composition time. The factory is pure — it does not create
 * or dispose [ConnectionRuntime]; that ownership stays on
 * `HanTermApplication` (see `connectionRuntime(...)`).
 *
 * `viewModelFactory { initializer { ... } }` is the modern (Lifecycle 2.5+)
 * API. `createSavedStateHandle()` reads from `CreationExtras` so the
 * resulting `SavedStateHandle` is wired to the host `SavedStateRegistryOwner`
 * — i.e. the Activity that owns the `ViewModelStore` — automatically.
 *
 * The factory builder is `remember`-stable across recompositions to keep
 * the same factory identity each time `viewModel(factory = ...)` is called;
 * `viewModel()` keys by owner + Class and ignores a swapped factory when
 * the owner already has an instance.
 */
fun hanTermAppViewModelFactory(
    application: Application,
    prefs: AppPreferences,
    profile: ConnectionProfile,
    runtime: ConnectionRuntime,
    isNetworkAvailable: () -> Boolean = {
        com.apexplow.hanterm.net.NetworkAvailability.isOnline(application)
    },
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        HanTermAppViewModel(
            application = application,
            prefs = prefs,
            profile = profile,
            runtime = runtime,
            savedStateHandle = createSavedStateHandle(),
            isNetworkAvailable = isNetworkAvailable,
        )
    }
}
