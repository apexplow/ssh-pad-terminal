package com.taosun.hanterm.data.profile.adapters

import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.data.profile.ProfileStorePort
import com.taosun.hanterm.data.profile.StoredProfile

/** [ProfileStorePort] backed by [AppPreferences] connection fields only. */
internal class SharedPreferencesProfileStore(
    private val prefs: AppPreferences,
) : ProfileStorePort {

    override fun read(): StoredProfile = StoredProfile(
        host = prefs.host,
        port = prefs.port,
        username = prefs.username,
        privateKeyName = prefs.privateKeyName,
        passwordBlob = prefs.getEncryptedPassword(),
    )

    override fun write(profile: StoredProfile) {
        prefs.host = profile.host
        prefs.port = profile.port
        prefs.username = profile.username
        prefs.privateKeyName = profile.privateKeyName
        if (profile.passwordBlob == null) {
            // Empty sentinel → getEncryptedPassword() returns null.
            prefs.setEncryptedPassword(ByteArray(0))
        } else {
            prefs.setEncryptedPassword(profile.passwordBlob)
        }
    }

    override fun clearConnectionFields() {
        prefs.clearConnectionFields()
    }
}
