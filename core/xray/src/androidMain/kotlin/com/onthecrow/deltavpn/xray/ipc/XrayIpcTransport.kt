package com.onthecrow.deltavpn.xray.ipc

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor

/**
 * The exact byte layout of a [XrayIpc.TX_INVOKE] transaction, written once so the two sides cannot
 * drift apart.
 *
 * Hand-rolled Parcel code fails in a particular way: add a field to the writer, forget the reader, and
 * every later field is read from the wrong offset — no compile error, no exception, just a String that
 * used to be a binder. Keeping both halves adjacent is the whole defence.
 */
internal object XrayIpcTransport {

    fun writeInvoke(
        data: Parcel,
        requestJson: String,
        host: IBinder?,
        tun: ParcelFileDescriptor?,
    ) {
        data.writeInterfaceToken(XrayIpc.ENGINE_DESCRIPTOR)
        data.writeString(requestJson)
        data.writeStrongBinder(host)
        // Length-prefixed rather than relying on writeParcelable's own null handling, so the reader
        // never has to guess whether a descriptor was sent.
        if (tun == null) {
            data.writeInt(0)
        } else {
            data.writeInt(1)
            tun.writeToParcel(data, 0)
        }
    }

    fun readInvoke(data: Parcel): Invoke {
        data.enforceInterface(XrayIpc.ENGINE_DESCRIPTOR)
        val requestJson = data.readString().orEmpty()
        val host = data.readStrongBinder()
        val tun = if (data.readInt() == 1) {
            ParcelFileDescriptor.CREATOR.createFromParcel(data)
        } else {
            null
        }
        return Invoke(requestJson, host, tun)
    }

    data class Invoke(
        val requestJson: String,
        val host: IBinder?,
        /**
         * Owned by the receiver once read. Whoever takes it is responsible for closing it or detaching
         * the raw descriptor out of it — dropping it on the floor leaks a descriptor in a process that
         * is meant to outlive many of these calls.
         */
        val tun: ParcelFileDescriptor?,
    )
}
