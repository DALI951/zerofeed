package com.zerofeed.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val repo = ZeroFeedRepository(context)
            val strictLocked = repo.isStrictLocked()
            Log.i("ZeroFeedBoot", "Reboot recovery verified. StrictLocked=$strictLocked, Streak=${repo.getStreakDays()}")
        }
    }
}
