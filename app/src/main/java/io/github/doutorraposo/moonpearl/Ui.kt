package io.github.doutorraposo.moonpearl

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A scrolling page with a large title, optional back arrow, kept readable on wide screens. */
@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(BackArrow, stringResource(R.string.back)) }
                }
                Text(
                    title,
                    style = if (onBack != null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun Section(@StringRes title: Int?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The whole row toggles, not just the switch. */
@Composable
fun SwitchRow(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
fun IniSwitch(
    ini: Ini,
    section: String,
    key: String,
    @StringRes label: Int,
    edit: (Ini.() -> Unit) -> Unit,
    @StringRes description: Int? = null,
) {
    SwitchRow(stringResource(label), ini.getBool(section, key), description?.let { stringResource(it) }) { checked ->
        edit { setBool(section, key, checked) }
    }
}

/** Material's back arrow, drawn here to avoid pulling in the icons library for two icons. */
val BackArrow: ImageVector = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White)) {
        moveTo(20f, 11f); horizontalLineTo(7.83f); lineTo(13.42f, 5.41f); lineTo(12f, 4f)
        lineTo(4f, 12f); lineTo(12f, 20f); lineTo(13.41f, 18.59f); lineTo(7.83f, 13f)
        horizontalLineTo(20f); close()
    }
}.build()

val ChevronRight: ImageVector = ImageVector.Builder("Chevron", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White)) {
        moveTo(10f, 6f); lineTo(8.59f, 7.41f); lineTo(13.17f, 12f); lineTo(8.59f, 16.59f)
        lineTo(10f, 18f); lineTo(16f, 12f); close()
    }
}.build()
