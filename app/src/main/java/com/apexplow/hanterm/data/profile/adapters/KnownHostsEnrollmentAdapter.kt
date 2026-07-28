package com.apexplow.hanterm.data.profile.adapters

import android.content.Context
import com.apexplow.hanterm.data.profile.HostEnrollmentPort
import com.apexplow.hanterm.ssh.security.KnownHostsStore

/** Production [HostEnrollmentPort] — forget only. */
internal class KnownHostsEnrollmentAdapter(
    context: Context,
) : HostEnrollmentPort {
    private val store = KnownHostsStore(context)

    override suspend fun delete(host: String, port: Int) {
        store.delete(host, port)
    }
}
