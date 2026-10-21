package com.nock

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Fast "open Nock" from the other device: a direct Wear message (~0.1-0.25 s) instead of the
 * Play-services remote-intent route (~4-5 s measured). Starting an activity from the background
 * needs the "Appear on top" permission; without it the sender falls back to the slow route.
 */
class OpenService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_OPEN) return
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    companion object { const val PATH_OPEN = "/nock/open" }
}
