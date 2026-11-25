package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.repository.BrandState
import com.example.data.security.SecurityUtils
import com.example.ui.components.BrandLogoImage
import com.example.ui.components.PrajusCard
import com.example.ui.components.PrajusSectionHeader
import com.example.ui.theme.LocalPrajusColors
import com.example.ui.theme.parseHexColor

@Composable
fun AparenciaScreen(
    brand: BrandState,
    onUploadLogo: (String, android.net.Uri) -> Unit,
    onResetLogo: (String) -> Unit,
    onSaveColors: (String, String, String) -> Unit,
    onResetAll: () -> Unit
) {
    val brandColors = LocalPrajusColors.current
    var pendingSlot by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    var azulEscuro by remember(brand.azulEscuro) { mutableStateOf(brand.azulEscuro) }
    var azul by remember(brand.azul) { mutableStateOf(brand.azul) }
    var dourado by remember(brand.dourado) { mutableStateOf(brand.dourado) }
    var colorError by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri != null && slot != null) {
            onUploadLogo(slot, uri)
        }
    }

    val launchLogoPicker: (String) -> Unit = { slot ->
        pendingSlot = slot
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(modifier = Modifier.widthIn(max = 920.dp)) {
                    PrajusSectionHeader(
                        title = "Aparência",
                        trailing = {
                            OutlinedButton(
                                onClick = { showResetConfirm = true },
                                modifier = Modifier.testTag("brand_reset_all_button")
                            ) {
                                Text("Restaurar padrão")
                            }
                        }
                    )
                }
            }
        }

        // Logos card
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    Text("Logotipos", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "PNG, JPG ou WEBP, até 600 KB. Prefira arquivos com fundo transparente ou branco.",
                        style = MaterialTheme.typography.bodySmall,
                        color = brandColors.mutedText
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    LogoSlotRow(
                        slot = "horizontal",
                        title = "Logotipo horizontal",
                        desc = "Menu lateral e topo. Fundo claro.",
                        customPath = brand.logoHorizontalPath,
                        onUpload = { launchLogoPicker("horizontal") },
                        onReset = { onResetLogo("horizontal") }
                    )
                    HorizontalDivider(color = brandColors.divider, modifier = Modifier.padding(vertical = 10.dp))
                    LogoSlotRow(
                        slot = "vertical",
                        title = "Logotipo vertical",
                        desc = "Tela de entrada. Fundo claro.",
                        customPath = brand.logoVerticalPath,
                        onUpload = { launchLogoPicker("vertical") },
                        onReset = { onResetLogo("vertical") }
                    )
                    HorizontalDivider(color = brandColors.divider, modifier = Modifier.padding(vertical = 10.dp))
                    LogoSlotRow(
                        slot = "icone",
                        title = "Ícone",
                        desc = "Atalhos e destaque institucional.",
                        customPath = brand.iconePath,
                        onUpload = { launchLogoPicker("icone") },
                        onReset = { onResetLogo("icone") }
                    )
                }
            }
        }

        // Colors card
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    Text("Cores", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))

                    ColorHexRow(
                        label = "Azul corporativo (menu e entrada)",
                        hex = azulEscuro,
                        presets = listOf("#1D2F4D", "#14213D", "#1E3A5F", "#2C2C54"),
                        onHexChange = { azulEscuro = it; colorError = null }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ColorHexRow(
                        label = "Azul (botões e destaques)",
                        hex = azul,
                        presets = listOf("#2F5C8E", "#2563EB", "#0F766E", "#4338CA"),
                        onHexChange = { azul = it; colorError = null }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ColorHexRow(
                        label = "Dourado (detalhes)",
                        hex = dourado,
                        presets = listOf("#CFA75B", "#D97706", "#B45309", "#10B981"),
                        onHexChange = { dourado = it; colorError = null }
                    )

                    if (colorError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(colorError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                azulEscuro = BrandState.DEFAULT_AZUL_ESCURO
                                azul = BrandState.DEFAULT_AZUL
                                dourado = BrandState.DEFAULT_DOURADO
                                onSaveColors(azulEscuro, azul, dourado)
                            }
                        ) {
                            Text("Voltar à Paleta A")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (!SecurityUtils.isValidHexColor(azulEscuro) ||
                                    !SecurityUtils.isValidHexColor(azul) ||
                                    !SecurityUtils.isValidHexColor(dourado)
                                ) {
                                    colorError = "Use códigos hexadecimais válidos (#RRGGBB)."
                                } else {
                                    onSaveColors(azulEscuro, azul, dourado)
                                }
                            },
                            modifier = Modifier.testTag("brand_save_colors_button")
                        ) {
                            Text("Salvar cores")
                        }
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Restaurar identidade padrão?") },
            text = { Text("Restaurar logotipos e cores padrão do Prajus?") },
            confirmButton = {
                Button(onClick = {
                    showResetConfirm = false
                    onResetAll()
                }) { Text("Restaurar") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun LogoSlotRow(
    slot: String,
    title: String,
    desc: String,
    customPath: String?,
    onUpload: () -> Unit,
    onReset: () -> Unit
) {
    val brandColors = LocalPrajusColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.White,
            border = BorderStroke(1.dp, brandColors.divider),
            modifier = Modifier.size(width = 104.dp, height = 52.dp)
        ) {
            BrandLogoImage(
                slot = slot,
                customPath = customPath,
                modifier = Modifier.padding(4.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "$desc · ${if (customPath != null) "personalizado" else "padrão Prajus"}",
                style = MaterialTheme.typography.bodySmall,
                color = brandColors.mutedText
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onUpload,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Trocar", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onReset,
                    enabled = customPath != null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Padrão", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ColorHexRow(
    label: String,
    hex: String,
    presets: List<String>,
    onHexChange: (String) -> Unit
) {
    val swatch = parseHexColor(hex, Color.Gray)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(swatch)
            )
            OutlinedTextField(
                value = hex,
                onValueChange = onHexChange,
                singleLine = true,
                modifier = Modifier.width(130.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { p ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(parseHexColor(p, Color.Gray))
                            .clickable { onHexChange(p) }
                    )
                }
            }
        }
    }
}
