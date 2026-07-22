package com.taosun.hanterm.data.profile

import android.content.Context
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.data.profile.adapters.AndroidKeystoreCipherAdapter
import com.taosun.hanterm.data.profile.adapters.EncryptedPrivateKeyVaultAdapter
import com.taosun.hanterm.data.profile.adapters.KnownHostsEnrollmentAdapter
import com.taosun.hanterm.data.profile.adapters.SharedPreferencesProfileStore

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
