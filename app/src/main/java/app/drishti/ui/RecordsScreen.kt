package app.drishti.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.drishti.core.Referral
import app.drishti.core.ResultText
import app.drishti.core.SessionCsv
import app.drishti.core.SessionRecord
import app.drishti.core.SessionRecords
import java.text.DateFormat
import java.util.Date

/** D61: saved screenings, newest first, with detail, delete and CSV export. */
@Composable
fun RecordsScreen(state: UiState.Ready, vm: MainViewModel) {
    Surface(Modifier.fillMaxSize()) {
        val detail = state.detail
        if (detail != null) RecordDetailView(detail, vm) else RecordList(state, vm)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecordList(state: UiState.Ready, vm: MainViewModel) {
    // Delete all asks twice (D61): 0 = no dialog, 1 = first question, 2 = final question.
    var deleteAllStep by remember { mutableIntStateOf(0) }
    val count = state.records.size

    LazyColumn(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Records", style = MaterialTheme.typography.headlineSmall)
            Text("$count saved on this phone. Nothing leaves the phone unless you export it.", style = MaterialTheme.typography.bodySmall)
            state.recordsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.open(Screen.HOME) }) { Text("Back") }
                OutlinedButton(onClick = { vm.export(all = true) }, enabled = count > 0) { Text("Export all") }
                OutlinedButton(onClick = { vm.export(all = false) }, enabled = state.selected.isNotEmpty()) {
                    Text("Export selected (${state.selected.size})")
                }
                OutlinedButton(onClick = { deleteAllStep = 1 }, enabled = count > 0) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                "Export makes two CSV files (sessions and trials) and opens the share sheet. They contain health " +
                    "screening results: you choose where they go.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { HorizontalDivider() }
        if (count == 0 && state.recordsError == null) item { Text("No records yet.") }
        items(state.records, key = { it.sessionId }) { r ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.openRecord(r.sessionId) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = r.sessionId in state.selected, onCheckedChange = { vm.toggleSelected(r.sessionId) })
                Column(Modifier.weight(1f)) {
                    Text("${r.token} · ${ResultText.decisionLabel(r.overall)}", fontWeight = FontWeight.Bold, color = colorOf(r.overall))
                    Text(date(r), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    when (deleteAllStep) {
        1 -> AlertDialog(
            onDismissRequest = { deleteAllStep = 0 },
            title = { Text("Delete all records?") },
            text = { Text("Export first if you need them.") },
            confirmButton = { TextButton(onClick = { deleteAllStep = 2 }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { deleteAllStep = 0 }) { Text("Cancel") } },
        )
        2 -> AlertDialog(
            onDismissRequest = { deleteAllStep = 0 },
            title = { Text("Delete all ${records(count)}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { deleteAllStep = 0; vm.deleteAllRecords() }) {
                    Text("Delete ${records(count)}", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteAllStep = 0 }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecordDetailView(d: RecordDetail, vm: MainViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    val r = d.record
    LazyColumn(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.closeRecord() }) { Text("Back") }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            Text("Token ${r.token}", style = MaterialTheme.typography.headlineSmall)
            Text(date(r))
            Text(r.overallText, fontWeight = FontWeight.Bold, color = colorOf(r.overall))
        }
        item { Part("Right eye", r.rightReferral, r.rightAcuity, r.rightReason, r.rightPinholeText, r.rightQuality) }
        item { Part("Left eye", r.leftReferral, r.leftAcuity, r.leftReason, r.leftPinholeText, r.leftQuality) }
        item { Part("Near (both eyes)", r.nearReferral, r.nearAcuity, r.nearReason, null, r.nearQuality) }
        item { HorizontalDivider() }
        item { Text("All saved fields", style = MaterialTheme.typography.titleMedium) }
        // Every column, exactly as exported, so nothing saved is hidden from the operator.
        items(SessionCsv.SESSION_HEADER.zip(SessionCsv.sessionCells(r))) { (name, cell) ->
            Text("$name: ${cellText(cell)}", style = MaterialTheme.typography.bodySmall)
        }
        item { Text("Trials saved: ${d.trialCount}", style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete record ${r.token}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteRecord(r.sessionId) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Part(title: String, decision: Referral, acuity: String, reason: String, pinhole: String?, quality: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title: ${ResultText.decisionLabel(decision)} · $acuity", fontWeight = FontWeight.Bold, color = colorOf(decision))
        Text(reason)
        pinhole?.let { Text(it) }
        quality.split(SessionRecords.QUALITY_SEPARATOR).filter { it.isNotBlank() }.forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun records(n: Int) = if (n == 1) "1 record" else "$n records"

private fun cellText(c: app.drishti.core.CsvCell): String = when (c) {
    is app.drishti.core.CsvCell.Text -> c.value ?: "—"
    is app.drishti.core.CsvCell.Num -> c.value?.toString() ?: "—"
    is app.drishti.core.CsvCell.Bool -> c.value?.toString() ?: "—"
}

private fun date(r: SessionRecord): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.createdAtMs))

@Composable
private fun colorOf(r: Referral) = when (r) {
    Referral.PASS -> androidx.compose.ui.graphics.Color(0xFF1B6B3A)
    Referral.REFER -> androidx.compose.ui.graphics.Color(0xFFB04A00)
    Referral.INCONCLUSIVE -> androidx.compose.ui.graphics.Color(0xFF5A4FB0)
}
