package com.freekiosk.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.freekiosk.wear.R

/**
 * Second-level SOS screen — itself the confirmation step (getting here takes
 * a deliberate tap on the dashboard). One giant red button, swipe to cancel.
 */
@Composable
fun SosScreen(onConfirm: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = context.getString(R.string.help_call_confirm_title),
            style = MaterialTheme.typography.title2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.primaryButtonColors(
                backgroundColor = Color(0xFFB71C1C),
                contentColor = Color.White,
            ),
            modifier = Modifier.size(88.dp),
        ) {
            Text(text = "🆘", style = MaterialTheme.typography.display3)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = context.getString(R.string.help_call_hint),
            style = MaterialTheme.typography.caption1,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
    }
}
