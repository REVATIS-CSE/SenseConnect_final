package com.example.senseconnect.core.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.senseconnect.SenseConnectApp
import com.example.senseconnect.core.AppContainer
import com.example.senseconnect.core.status.FixAction

object AppPermissions {
    val CAMERA = arrayOf(Manifest.permission.CAMERA)
    val MICROPHONE = arrayOf(Manifest.permission.RECORD_AUDIO)
    val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val SMS = arrayOf(Manifest.permission.SEND_SMS)
}

/**
 * Wraps the runtime-permission flow. Reports whether the permission was granted and whether
 * Android will no longer show the dialog ("Don't ask again"), in which case the caller should
 * direct the user to App Settings.
 */
class PermissionRequester(
    caller: ActivityResultCaller,
    private val activity: () -> Activity,
    private val onResult: (granted: Boolean, blocked: Boolean) -> Unit,
) {
    private var pending: Array<String> = emptyArray()

    private val launcher = caller.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        val blocked = !granted && pending.none {
            ActivityCompat.shouldShowRequestPermissionRationale(activity(), it)
        }
        onResult(granted, blocked)
    }

    fun request(permissions: Array<String>) {
        pending = permissions
        launcher.launch(permissions)
    }
}

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun Context.openLocationSettings() {
    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

fun Context.openTtsSettings() {
    try {
        startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/** Maps a [FixAction] that needs a permission to the permission group to request. */
fun FixAction.permissions(): Array<String>? = when (this) {
    FixAction.REQUEST_CAMERA -> AppPermissions.CAMERA
    FixAction.REQUEST_MICROPHONE -> AppPermissions.MICROPHONE
    FixAction.REQUEST_LOCATION -> AppPermissions.LOCATION
    else -> null
}

/** ViewModel factory that hands the shared [AppContainer] to a ViewModel constructor. */
inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SenseConnectApp
        create(app.container)
    }
}
