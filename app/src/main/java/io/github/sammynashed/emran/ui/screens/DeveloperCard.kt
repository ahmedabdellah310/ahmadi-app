package io.github.sammynashed.emran.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.sammynashed.emran.R
import io.github.sammynashed.emran.ui.design.LocalSky

@Composable
fun DeveloperCard(ar: Boolean) {
    val sky = LocalSky.current
    val context = LocalContext.current
    var zoom by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(sky.ink.copy(alpha = 0.06f)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The photo fills the whole circle: the square original, centred, clipped to the circle (head and torso stay).
        Image(
            painter = painterResource(R.drawable.developer_photo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(124.dp).clip(CircleShape).clickable { zoom = true },
        )
        Spacer(Modifier.height(10.dp))
        Text("أحمد عبداللاه", color = sky.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(sky.ink.copy(alpha = 0.14f))
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ahmedabdellah310_bot")))
                    }
                }.padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("افتح البوت على تيليجرام", color = sky.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(sky.ink.copy(alpha = 0.14f))
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/share/1bzDtsm17e/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("صفحتي على فيسبوك", color = sky.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (zoom) {
        Dialog(onDismissRequest = { zoom = false }) {
            Image(
                painter = painterResource(R.drawable.developer_photo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(300.dp).clip(CircleShape).clickable { zoom = false },
            )
        }
    }
}
