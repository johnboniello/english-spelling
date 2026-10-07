package com.johnb.englishspelling

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Play "flexible" in-app update: when a newer version is on the Play Store, Google's
 * prompt offers it, the download runs in the background while the app stays usable,
 * and a snackbar asks to restart once it's ready.
 *
 * Only works for copies installed from Play; on debug/sideloaded builds Play reports
 * no update and nothing is shown. Must be created in the activity's onCreate (it
 * registers an activity-result launcher).
 */
class InAppUpdate(private val activity: AppCompatActivity) {

    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private val prefs = activity.getSharedPreferences("in_app_update", Context.MODE_PRIVATE)

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            // Declined: don't ask again for a few days, so it doesn't nag on every launch.
            if (result.resultCode != AppCompatActivity.RESULT_OK) {
                prefs.edit().putLong(KEY_DECLINED_AT, System.currentTimeMillis()).apply()
            }
        }

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) showRestartPrompt()
    }

    /** Call from onCreate: offers an update if Play has one. */
    fun checkForUpdate() {
        manager.registerListener(listener)
        val declinedAt = prefs.getLong(KEY_DECLINED_AT, 0L)
        if (System.currentTimeMillis() - declinedAt < REASK_AFTER_MS) return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
            ) {
                manager.startUpdateFlowForResult(
                    info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                )
            }
        }
    }

    /** Call from onResume: the download may have finished while the app was in the background. */
    fun resume() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) showRestartPrompt()
        }
    }

    /** Call from onDestroy. */
    fun stop() = manager.unregisterListener(listener)

    private fun showRestartPrompt() {
        Snackbar.make(
            activity.findViewById(android.R.id.content),
            "New version ready",
            Snackbar.LENGTH_INDEFINITE
        ).setAction("Restart") { manager.completeUpdate() }.show()
    }

    private companion object {
        const val KEY_DECLINED_AT = "declined_at"
        const val REASK_AFTER_MS = 3L * 24 * 60 * 60 * 1000
    }
}
