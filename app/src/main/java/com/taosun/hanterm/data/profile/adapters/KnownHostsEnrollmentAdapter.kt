package com.taosun.hanterm.data.profile.adapters

import android.content.Context
import com.taosun.hanterm.data.profile.HostEnrollmentPort
import com.taosun.hanterm.ssh.security.KnownHostsStore

/** Production [HostEnrollmentPort] — forget only. */
internal class KnownHostsEnrollmentAdapter(
    context: Context,
) : HostEnrollmentPort {
    private val store = KnownHostsStore(context)

    override suspend fun delete(host: String, port: Int) {
        store.delete(host, port)
    }
}
