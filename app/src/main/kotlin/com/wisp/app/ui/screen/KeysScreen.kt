package com.wisp.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.wisp.app.R
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.SigningMode

private fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private enum class SignerHealth { Checking, Available, Unavailable }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    keyRepository: KeyRepository,
    onBack: () -> Unit
) {
    val signingMode = remember { keyRepository.getSigningMode() }
    val pubkeyHex = remember { keyRepository.getPubkeyHex() }
    val keypair = remember { keyRepository.getKeypair() }
    val npub = remember {
        pubkeyHex?.let { Nip19.npubEncode(it.hexToByteArray()) }
            ?: keypair?.let { Nip19.npubEncode(it.pubkey) }
    }
    var nsec by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val revealPrivateKeyTitle = stringResource(R.string.btn_reveal_private_key)
    val revealPrivateKeyDescription = stringResource(R.string.settings_authenticate_view_key)

    // Clear nsec from memory when the composable leaves composition
    DisposableEffect(Unit) {
        onDispose { nsec = null }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_keys)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            // Public key section (always shown)
            Text(
                text = stringResource(R.string.settings_public_key),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_public_key_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = npub ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = {
                        npub?.let {
                            clipboardManager.setText(AnnotatedString(it))
                            Toast.makeText(context, context.getString(R.string.settings_public_key_copied), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.cd_copy_npub),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // For remote-signer accounts the local nsec doesn't exist on
            // device. Replace the Private Key section with details about the
            // separate signer app the private key actually lives in.
            if (signingMode == SigningMode.REMOTE) {
                RemoteSignerSection(keyRepository = keyRepository)
            } else {
                PrivateKeySection(
                    nsec = nsec,
                    onReveal = { nsec = it },
                    keypair = keypair,
                    revealPrivateKeyTitle = revealPrivateKeyTitle,
                    revealPrivateKeyDescription = revealPrivateKeyDescription
                )
            }
        }
    }
}

@Composable
private fun PrivateKeySection(
    nsec: String?,
    onReveal: (String) -> Unit,
    keypair: com.wisp.app.nostr.Keys.Keypair?,
    revealPrivateKeyTitle: String,
    revealPrivateKeyDescription: String
) {
    val context = LocalContext.current

    Text(
        text = stringResource(R.string.settings_private_key),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))

    if (nsec != null) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = nsec,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {
                    val clip = ClipData.newPlainText("", nsec)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        clip.description.extras = PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(clip)
                    Toast.makeText(context, context.getString(R.string.settings_private_key_copied), Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.btn_copy),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    } else {
        Button(
            onClick = {
                val activity = context.findFragmentActivity()
                    ?: return@Button

                val biometricManager = BiometricManager.from(context)
                val canAuth = biometricManager.canAuthenticate(
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                    // No device lock set — reveal directly
                    keypair?.let { onReveal(Nip19.nsecEncode(it.privkey)) }
                    return@Button
                }

                val executor = ContextCompat.getMainExecutor(context)
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        keypair?.let { onReveal(Nip19.nsecEncode(it.privkey)) }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            Toast.makeText(context, context.getString(R.string.settings_auth_failed, errString), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(revealPrivateKeyTitle)
                    .setDescription(revealPrivateKeyDescription)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()

                BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Visibility, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.btn_reveal_private_key))
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.private_key_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}

/**
 * Replacement for the Private Key section when the active account is a remote
 * signer. Mirrors the iOS Keys screen's Remote Signer block, adapted to the
 * NIP-55 protocol Android actually uses: the signer is a local app referenced
 * by package name (no relay-mediated RPC, no transport pubkey, no relay list).
 * Health check is a PackageManager probe — is the recorded signer package
 * still installed and resolvable?
 */
@Composable
private fun RemoteSignerSection(keyRepository: KeyRepository) {
    val context = LocalContext.current
    val signerPackage = remember { keyRepository.getSignerPackage() }
    var refreshTick by remember { mutableStateOf(0) }
    var health by remember { mutableStateOf(SignerHealth.Checking) }

    LaunchedEffect(refreshTick, signerPackage) {
        health = SignerHealth.Checking
        health = if (signerPackage != null && isPackageInstalled(context, signerPackage)) {
            SignerHealth.Available
        } else {
            SignerHealth.Unavailable
        }
    }

    Text(
        text = stringResource(R.string.settings_remote_signer),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.settings_remote_signer_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))

    // Status pill: coloured dot + label + Refresh.
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HealthDot(health = health)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = when (health) {
                    SignerHealth.Checking -> stringResource(R.string.settings_remote_signer_status_checking)
                    SignerHealth.Available -> stringResource(R.string.settings_remote_signer_status_available)
                    SignerHealth.Unavailable -> stringResource(R.string.settings_remote_signer_status_unavailable)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { refreshTick++ },
                enabled = health != SignerHealth.Checking
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.settings_remote_signer_refresh))
            }
        }
    }

    if (signerPackage != null) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_remote_signer_package),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = signerPackage,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
        }
    } else {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_remote_signer_status_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HealthDot(health: SignerHealth) {
    val color = when (health) {
        SignerHealth.Checking -> MaterialTheme.colorScheme.primary
        SignerHealth.Available -> Color(0xFF4CAF50)
        SignerHealth.Unavailable -> MaterialTheme.colorScheme.error
    }
    // Pulse a soft ring while checking; static dot otherwise.
    val pulse = if (health == SignerHealth.Checking) {
        val transition = rememberInfiniteTransition(label = "signer-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        ).value
    } else {
        1f
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
        if (health == SignerHealth.Checking) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .scale(pulse)
                    .background(color.copy(alpha = 0.35f), CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
    }
}

private fun isPackageInstalled(context: android.content.Context, packageName: String): Boolean =
    try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

