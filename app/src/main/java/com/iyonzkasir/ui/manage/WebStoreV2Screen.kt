package com.iyonzkasir.ui.manage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iyonzkasir.core.*
import com.iyonzkasir.data.local.*
import com.iyonzkasir.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebStoreV2Screen(app: AppContainer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val outlets by app.teamRepo.activeOutlets.collectAsState(initial = emptyList())
    var selectedOutletId by remember { mutableStateOf("") }
    var savedSlug by remember { mutableStateOf("") }
    var slugInput by remember { mutableStateOf("") }
    var tableNo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        savedSlug = app.prefRepo.getWebSlug()
        slugInput = savedSlug
    }
    LaunchedEffect(outlets) {
        if (selectedOutletId.isBlank() && outlets.isNotEmpty()) {
            selectedOutletId = SessionManager.activeTransactionOutletId(outlets.first().id)
        }
    }
    val chosenOutlet = outlets.firstOrNull { it.id == selectedOutletId } ?: outlets.firstOrNull()
    val activeUrl = if (savedSlug.isNotBlank()) {
        val base = "https://iyonzkasirapp-8dfd5.web.app/?store=$savedSlug"
        if (tableNo.isBlank()) base else "$base&meja=${tableNo.trim()}"
    } else ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Menu Online & Link Meja") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(AppSp.lg),
            verticalArrangement = Arrangement.spacedBy(AppSp.md)
        ) {
            Text("Info Cabang untuk Web", fontWeight = FontWeight.Bold)
            if (outlets.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSp.xs)) {
                    items(outlets, key = { it.id }) { o ->
                        FilterChip(selected = o.id == chosenOutlet?.id, onClick = { selectedOutletId = o.id }, label = { Text(o.name) })
                    }
                }
            }
            OutlinedTextField(
                value = slugInput,
                onValueChange = {
                    slugInput = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(32)
                    statusMsg = null
                },
                label = { Text("Link Toko (Store ID) *") },
                placeholder = { Text("cth: kopi-iyonz") },
                supportingText = { Text("Huruf kecil, angka, dan tanda '-' (min 3 karakter)") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val cleanSlug = slugInput.trim('-')
                    if (cleanSlug.length < 3) {
                        isError = true; statusMsg = "Link minimal 3 karakter"; return@Button
                    }
                    busy = true; statusMsg = "Menyiapkan foto & menyinkronkan menu..."
                    isError = false
                    scope.launch {
                        val avail = app.gateway.checkSlugAvailable(cleanSlug, SessionManager.businessId).getOrElse { false }
                        if (!avail) {
                            busy = false; isError = true
                            statusMsg = "Link '$cleanSlug' sudah dipakai toko lain"; return@launch
                        }
                        app.syncNow()
                        val prods = app.catalogRepo.products.first()
                        val bundles = app.catalogRepo.bundles.first()
                        val groupsMap = mutableMapOf<String, List<BundleGroupEntity>>()
                        val optionsMap = mutableMapOf<String, List<BundleOptionEntity>>()
                        bundles.forEach { b ->
                            val (_, gs, om) = app.catalogRepo.loadBundleFull(b.id)
                            groupsMap[b.id] = gs
                            om.forEach { (gid, opts) -> optionsMap[gid] = opts }
                        }
                        val colorHex = String.format(Locale.US, "#%06X", (0xFFFFFF and AppThemeState.preset.primaryHex.toInt()))
                        val tax = app.prefRepo.getDefaultTaxPercent()
                        val res = app.gateway.publishWebStorefront(
                            businessId = SessionManager.businessId,
                            slug = cleanSlug,
                            storeName = chosenOutlet?.name ?: "Toko Saya",
                            address = chosenOutlet?.address ?: "",
                            phoneWa = normalizeWaPhone(chosenOutlet?.phoneWa ?: ""),
                            themeColorHex = colorHex,
                            taxPercent = tax,
                            products = prods,
                            bundles = bundles,
                            groupsMap = groupsMap,
                            optionsMap = optionsMap
                        )
                        busy = false
                        res.onSuccess {
                            app.prefRepo.setWebSlug(cleanSlug)
                            savedSlug = cleanSlug; slugInput = cleanSlug
                            app.startRealtimeListeners()
                            isError = false; statusMsg = "✅ Menu online berhasil dipublikasikan!"
                        }.onFailure {
                            isError = true; statusMsg = "❌ Gagal: ${it.message}"
                        }
                    }
                },
                enabled = !busy && slugInput.length >= 3,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.brand)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Sync & Publikasikan Menu Web", fontWeight = FontWeight.Bold)
            }
            statusMsg?.let {
                Text(it, color = if (isError) AppColors.danger else AppColors.success, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            if (activeUrl.isNotBlank()) {
                HorizontalDivider()
                Text("Link Pembeli / QR Meja", fontWeight = FontWeight.Bold)
                OutlinedTextField(tableNo, { tableNo = it.take(10) }, label = { Text("Nomor Meja (opsional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.brandSoft)) {
                    Text(activeUrl, Modifier.padding(AppSp.md), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSp.sm)) {
                    OutlinedButton(onClick = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Web Menu", activeUrl))
                        statusMsg = "Link disalin ✅"; isError = false
                    }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Salin Link") }
                    Button(onClick = {
                        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, activeUrl) }, "Bagikan Link Menu")) }
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.brand)) {
                        Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Bagikan")
                    }
                }
            }
        }
    }
}
