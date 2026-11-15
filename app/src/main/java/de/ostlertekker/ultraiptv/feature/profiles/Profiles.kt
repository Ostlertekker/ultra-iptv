package de.ostlertekker.ultraiptv.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ProfileEntity
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.ui.PinDialog
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

val avatarColors = listOf(
    Color(0xFF9D6BFF), Color(0xFF4FC3F7), Color(0xFFFFC94D), Color(0xFF2EE59D),
    Color(0xFFFF4FD8), Color(0xFFFF6A33), Color(0xFF7C8CFF), Color(0xFFE6E6E6),
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(val user: UserRepository) : ViewModel() {
    val profiles = user.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val active = user.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun select(p: ProfileEntity, done: () -> Unit) = viewModelScope.launch { user.selectProfile(p); done() }
    fun create(name: String) = viewModelScope.launch { user.createProfile(name, profiles.value.size % avatarColors.size) }
    fun update(p: ProfileEntity) = viewModelScope.launch { user.updateProfile(p) }
    fun delete(p: ProfileEntity) = viewModelScope.launch { user.deleteProfile(p) }
    fun setPin(p: ProfileEntity, pin: String?) = viewModelScope.launch { user.setPin(p, pin) }
    fun checkPin(p: ProfileEntity, pin: String) = user.checkPin(p, pin)
}

@Composable
fun ProfileAvatar(p: ProfileEntity, size: Int) {
    Box(Modifier.size(size.dp).background(avatarColors[p.colorIndex % avatarColors.size], CircleShape), contentAlignment = Alignment.Center) {
        Text(p.name.take(1).uppercase(), color = Color.Black, fontSize = (size / 2.4).sp, fontWeight = FontWeight.Bold)
    }
}

/** Profilauswahl beim Start (nur wenn mehrere Profile existieren). */
@Composable
fun ProfilePickerScreen(onPicked: () -> Unit, vm: ProfilesViewModel = hiltViewModel()) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(profiles.size) { if (profiles.isNotEmpty()) runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WER SCHAUT?", color = p.text, fontSize = 34.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily, letterSpacing = 4.sp)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                profiles.forEachIndexed { i, prof ->
                    var focused by remember { mutableStateOf(false) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.tvFocusable(RoundedCornerShape(80.dp), onClick = {
                            if (prof.pinHash != null) pinFor = prof else vm.select(prof, onPicked)
                        }, focusRequester = if (i == 0) first else null, onFocus = { focused = it }).padding(6.dp)) {
                            ProfileAvatar(prof, 120)
                        }
                        Text(prof.name, color = if (focused) p.text else p.textDim, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
                        if (prof.pinHash != null) Text("🔒 PIN", color = p.textDim, fontSize = 12.sp)
                        if (prof.maxAge > 0) Text("ab ${prof.maxAge}", color = p.textDim, fontSize = 12.sp)
                    }
                }
            }
        }
        val target = pinFor
        if (target != null) {
            PinDialog("PIN für ${target.name}", onSubmit = { pin ->
                val ok = vm.checkPin(target, pin)
                if (ok) { pinFor = null; scope.launch { vm.select(target, onPicked) } }
                ok
            }, onDismiss = { pinFor = null })
        }
    }
}
