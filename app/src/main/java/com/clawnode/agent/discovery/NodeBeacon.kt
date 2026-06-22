package com.clawnode.agent.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.clawnode.agent.core.ClawLog

/**
 * 节点 LAN 广播（`_miniorange-node._tcp`），供桌面端「发现设备」。
 */
object NodeBeacon {

    private const val TAG = "NodeBeacon"
    const val SERVICE_TYPE = "_miniorange-node._tcp."
    private const val PLACEHOLDER_PORT = 9

    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start(context: Context, sn: String, model: String, paired: Boolean = true) {
        stop(context)
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val instance = sanitizeInstance(sn)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = instance
            serviceType = SERVICE_TYPE
            port = PLACEHOLDER_PORT
            setAttribute("sn", sn)
            setAttribute("model", model)
            setAttribute("role", "node")
            setAttribute("paired", if (paired) "1" else "0")
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                ClawLog.bp(TAG, "registered", "name=${info.serviceName} sn=$sn")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                ClawLog.w(TAG, "register_fail", "code=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                ClawLog.bp(TAG, "unregistered", "name=${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                ClawLog.w(TAG, "unregister_fail", "code=$errorCode")
            }
        }
        runCatching {
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener!!)
        }.onFailure { e ->
            ClawLog.e(TAG, "register_error", e.message ?: "", e)
        }
    }

    fun stop(context: Context) {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val listener = registrationListener
        if (nsd != null && listener != null) {
            runCatching { nsd.unregisterService(listener) }
        }
        registrationListener = null
    }

    private fun sanitizeInstance(sn: String): String {
        val base = sn.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')
        return "clawnode-${base.take(48).ifBlank { "unknown" }}"
    }
}
