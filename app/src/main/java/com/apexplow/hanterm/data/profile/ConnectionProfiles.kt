package com.apexplow.hanterm.data.profile

import android.content.Context
import com.apexplow.hanterm.data.prefs.AppPreferences
import com.apexplow.hanterm.data.profile.adapters.AndroidKeystoreCipherAdapter
import com.apexplow.hanterm.data.profile.adapters.EncryptedPrivateKeyVaultAdapter
import com.apexplow.hanterm.data.profile.adapters.KnownHostsEnrollmentAdapter
import com.apexplow.hanterm.data.profile.adapters.SharedPreferencesProfileStore

/** Factory for the process-scoped [ConnectionProfile]. */
object ConnectionProfiles {
    fun create(context: Context, prefs: AppPreferences = AppPreferences(context)): ConnectionProfile =
        DefaultConnectionProfile(
            store = SharedPreferencesProfileStore(prefs),
            cipher = AndroidKeystoreCipherAdapter(),
            keys = EncryptedPrivateKeyVaultAdapter(context),
            hosts = KnownHostsEnrollmentAdapter(context),
        )
}
