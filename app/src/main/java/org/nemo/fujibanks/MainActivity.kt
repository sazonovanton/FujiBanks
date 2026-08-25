package org.nemo.fujibanks

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.nemo.fujibanks.ui.BanksViewModel
import org.nemo.fujibanks.ui.FujiBanksApp
import org.nemo.fujibanks.ui.FujiBanksTheme
import org.nemo.fujibanks.ui.UsbPermissionReceiver

class MainActivity : ComponentActivity() {

    private val viewModel: BanksViewModel by viewModels()

    private val permissionReceiver = UsbPermissionReceiver { granted ->
        viewModel.onPermissionResult(granted)
    }

    /**
     * The camera locks its controls while connected, so it gets unplugged every
     * time a setting is changed. Watching attach and detach means that cycle
     * does not need a manual reconnect.
     */
    private val hotplugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    viewModel.onDeviceAttached()
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    viewModel.onDeviceDetached()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash theme exists only to paint the window before Compose has
        // drawn anything. Swapping it here, before the first frame, means the
        // mark never sits behind the running app.
        setTheme(R.style.Theme_FujiBanks)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // RECEIVER_NOT_EXPORTED: the broadcast is ours, sent by the USB service
        // back to this package only.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, UsbPermissionReceiver.filter(), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, UsbPermissionReceiver.filter())
        }

        val hotplugFilter = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hotplugReceiver, hotplugFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(hotplugReceiver, hotplugFilter)
        }

        setContent {
            FujiBanksTheme {
                FujiBanksApp(viewModel)
            }
        }

        // Plugging the camera in launches us via the USB_DEVICE_ATTACHED filter,
        // so try to connect straight away.
        viewModel.findAndConnect()
    }

    /** Re-plugging the camera delivers the attach intent here, not to onCreate. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.onDeviceAttached()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(permissionReceiver) }
        runCatching { unregisterReceiver(hotplugReceiver) }
    }
}
