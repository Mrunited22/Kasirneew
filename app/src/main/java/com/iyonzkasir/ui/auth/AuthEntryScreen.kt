package com.iyonzkasir.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.iyonzkasir.core.*
import com.iyonzkasir.di.AppContainer
import kotlinx.coroutines.launch

@Composable
fun AuthEntryScreen(
    app: AppContainer,
    onAuthenticated: (isNewBusiness: Boolean) -> Unit,
    onOpenRestore: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRegisterMode by remember { mutableStateOf(false) }
    var ownerName by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(AppSp.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(AppSp.xxl))
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(AppColors.brand), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Storefront, null, Modifier.size(40.dp), tint = Color.White)
        }
        Spacer(Modifier.height(AppSp.md))
        Text("iyonzkasir", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Kasir simpel untuk warung, gerobak & multi-cabang", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(AppSp.lg))

        OutlinedButton(
            onClick = {
                val resId = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
                val webClientId = if (resId != 0) ctx.getString(resId) else ""
                if (webClientId.isBlank()) {
                    errorMsg = "SHA-1 belum dipasang / google-services.json belum diupdate"
                    return@OutlinedButton
                }
                busy = true; errorMsg = null; infoMsg = null
                scope.launch {
                    try {
                        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
                        val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
                        val credRes = CredentialManager.create(ctx).getCredential(ctx, req)
                        val cred = credRes.credential
                        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                            app.gateway.loginWithGoogle(idToken).onSuccess { info ->
                                SessionManager.bindBusiness(ctx, info.uid, "")
                                if (info.isNewBusiness) {
                                    val ownerN = info.displayName.ifBlank { "Owner" }
                                    val bizN = businessName.trim().ifBlank { "$ownerN Store" }
                                    val (outlet, owner) = app.teamRepo.createInitialSetup(info.uid, ownerN, bizN)
                                    SessionManager.setViewingOutlet(ctx, outlet.id)
                                    SessionManager.loginStaff(ctx, owner.toSessionUser())
                                    app.syncNow()
                                    busy = false
                                    onAuthenticated(true)
                                } else {
                                    val syncRes = app.syncNow()
                                    if (syncRes.isFailure) {
                                        busy = false
                                        errorMsg = "Gagal tarik data: ${syncRes.exceptionOrNull()?.message}"
                                        return@onSuccess
                                    }
                                    if (app.teamRepo.getOwner() == null) {
                                        app.teamRepo.createInitialSetup(info.uid, info.displayName.ifBlank { "Owner" }, "Usaha Saya")
                                    }
                                    busy = false
                                    onAuthenticated(false)
                                }
                            }.onFailure {
                                busy = false
                                errorMsg = it.message ?: "Login Google gagal"
                            }
                        } else {
                            busy = false
                            errorMsg = "Tipe akun tidak dikenali"
                        }
                    } catch (_: GetCredentialCancellationException) {
                        busy = false
                    } catch (e: GetCredentialException) {
                        busy = false
                        errorMsg = "Google Sign-In gagal: ${e.localizedMessage ?: e.message}"
                    } catch (e: Exception) {
                        busy = false
                        errorMsg = e.message ?: "Gagal login Google"
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = AppRd.card
        ) {
            Icon(Icons.Default.AccountCircle, null, tint = AppColors.brand)
            Spacer(Modifier.width(AppSp.sm))
            Text("Lanjutkan dengan Google", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(AppSp.md))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text("  atau pakai email  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f))
        }
        Spacer(Modifier.height(AppSp.md))

        TabRow(selectedTabIndex = if (isRegisterMode) 1 else 0, containerColor = AppColors.card) {
            Tab(selected = !isRegisterMode, onClick = { isRegisterMode = false; errorMsg = null; infoMsg = null },
                text = { Text("Masuk Usaha", fontWeight = FontWeight.SemiBold) })
            Tab(selected = isRegisterMode, onClick = { isRegisterMode = true; errorMsg = null; infoMsg = null },
                text = { Text("Daftar Baru", fontWeight = FontWeight.SemiBold) })
        }
        Spacer(Modifier.height(AppSp.lg))
        Card(shape = AppRd.cardLg, colors = CardDefaults.cardColors(containerColor = AppColors.card)) {
            Column(Modifier.padding(AppSp.lg), verticalArrangement = Arrangement.spacedBy(AppSp.md)) {
                if (isRegisterMode) {
                    OutlinedTextField(ownerName, { ownerName = it; errorMsg = null }, label = { Text("Nama Pemilik *") },
                        placeholder = { Text("cth: Budi Santoso") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(businessName, { businessName = it; errorMsg = null }, label = { Text("Nama Usaha / Toko *") },
                        placeholder = { Text("cth: Kopi Gerobak Mantap") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(email, { email = it; errorMsg = null }, label = { Text("Email Pemilik *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password, onValueChange = { password = it; errorMsg = null },
                    label = { Text("Password (min 6 karakter) *") },
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                errorMsg?.let { Text(it, color = AppColors.danger, style = MaterialTheme.typography.bodySmall) }
                infoMsg?.let { Text(it, color = AppColors.success, style = MaterialTheme.typography.bodySmall) }

                Button(
                    onClick = {
                        if (email.isBlank() || password.length < 6) { errorMsg = "Email wajib & password minimal 6 karakter"; return@Button }
                        if (isRegisterMode && (ownerName.isBlank() || businessName.isBlank())) { errorMsg = "Nama pemilik & nama usaha wajib diisi"; return@Button }
                        busy = true; errorMsg = null; infoMsg = null
                        scope.launch {
                            if (isRegisterMode) {
                                app.gateway.registerOwner(email, password, ownerName, businessName).onSuccess { info ->
                                    SessionManager.bindBusiness(ctx, info.uid, "")
                                    val (outlet, owner) = app.teamRepo.createInitialSetup(info.uid, ownerName, businessName)
                                    SessionManager.setViewingOutlet(ctx, outlet.id)
                                    SessionManager.loginStaff(ctx, owner.toSessionUser())
                                    app.syncNow(); busy = false; onAuthenticated(true)
                                }.onFailure { busy = false; errorMsg = it.message ?: "Pendaftaran gagal" }
                            } else {
                                app.gateway.loginOwner(email, password).onSuccess { info ->
                                    SessionManager.bindBusiness(ctx, info.uid, "")
                                    val syncRes = app.syncNow()
                                    if (syncRes.isFailure) { busy = false; errorMsg = "Gagal tarik data: ${syncRes.exceptionOrNull()?.message}"; return@onSuccess }
                                    if (app.teamRepo.getOwner() == null) app.teamRepo.createInitialSetup(info.uid, info.displayName.ifBlank { "Owner" }, "Usaha Saya")
                                    busy = false; onAuthenticated(false)
                                }.onFailure { busy = false; errorMsg = it.message ?: "Email atau password salah" }
                            }
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.brand), shape = AppRd.card
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(if (isRegisterMode) "Daftar & Mulai Jualan" else "Masuk", fontWeight = FontWeight.Bold)
                }

                if (!isRegisterMode) {
                    TextButton(onClick = {
                        if (email.isBlank()) { errorMsg = "Isi email dulu untuk reset password"; return@TextButton }
                        scope.launch {
                            app.gateway.sendPasswordReset(email)
                                .onSuccess { infoMsg = "Link reset password sudah dikirim ke $email" }
                                .onFailure { errorMsg = it.message }
                        }
                    }, modifier = Modifier.align(Alignment.End)) { Text("Lupa password?", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Spacer(Modifier.height(AppSp.lg))
        TextButton(onClick = onOpenRestore) {
            Icon(Icons.Default.SettingsBackupRestore, null, Modifier.size(18.dp))
            Spacer(Modifier.width(AppSp.xs))
            Text("Pulihkan dari File Backup (.iyonz)")
        }
    }
}
