package org.nemo.fujibanks.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shown once, before the first time this app opens a session with a camera.
 *
 * The README says the same thing at length, and a README is read by people
 * browsing a repository, not by the person who just plugged a camera into a
 * phone. The part that has to be said before the cable is live is said here.
 *
 * It gates the connection rather than following it: "if your warranty matters
 * more to you than this app, do not connect" is worth nothing once the session
 * is already open. Declining is a real answer and leaves the camera untouched.
 */
@Composable
fun CameraNoticeDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    FujiDialog(
        title = "Before the first connection",
        subtitle = "This app writes to your camera's settings. Worth one read.",
        // Tapping beside it would be a third answer to a question that has two,
        // and the quiet one would be indistinguishable from consent.
        dismissOnOutside = false,
        onDismiss = onDecline,
        cancel = DialogAction("Not now", onClick = onDecline),
        confirm = DialogAction("I understand", onClick = onAccept),
    ) {
        Para("What it writes", 
            "Installing a recipe writes the custom settings banks C1–C7 over the " +
                "cable. It sets each property on its own rather than replacing a " +
                "settings file, and touches nothing outside the banks you chose.")

        Para("What it does first",
            "Every write is preceded by a full snapshot of all seven banks, and " +
                "nothing is sent if that read fails. You see a diff before anything " +
                "goes out, every property is read back and compared afterwards, and " +
                "Undo puts the snapshot back. That does not make a write safe — it " +
                "makes a bad one visible and reversible.")

        Para("What it never touches",
            "Your photographs and your card. In bank mode the camera does not " +
                "expose the card at all. In USB CARD READER this app only reads.")

        Para("The real risk",
            "This app has been tested on an X-T30 III and on no other body. Its " +
                "property map came from a project that measured a different camera, " +
                "and the two already disagree in two places — so the map is known to " +
                "vary, and nobody has checked yours. A write can land in a property " +
                "this app has mislabelled, and the read-back will confirm the wrong " +
                "setting was written correctly. Run Dump first, from the long press " +
                "on the title.")

        Para("Warranty",
            "Fujifilm licenses its camera SDK selectively and this project is not " +
                "licensed by them. Connecting third-party software may affect what a " +
                "manufacturer will cover later. Nobody here can tell you how that " +
                "would go for you. If the warranty matters more to you than this app " +
                "does, do not connect.")

        Para("As is",
            "A personal tool, published because the protocol work in it is worth " +
                "sharing — not a product. No warranty of any kind, no support, no " +
                "promise it is fit for anything. You connect your camera by your own " +
                "choice and at your own risk. The author is not liable for anything " +
                "that follows — altered or lost settings, damage to the camera or the " +
                "card, data lost from either, repair or replacement costs, a warranty " +
                "claim refused, or the shots you miss — however it comes about. If it " +
                "does not do what you want, stop using it. That is the whole remedy.")

        Spacer(Modifier.height(4.dp))
        Text(
            "Do not unplug the camera while a write is running.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Film.Warn,
        )
    }
}

@Composable
private fun ColumnScope.Para(heading: String, body: String) {
    Text(heading, style = LabelCaps)
    Spacer(Modifier.height(2.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        color = Film.TextSecondary,
    )
    Spacer(Modifier.height(12.dp))
}
