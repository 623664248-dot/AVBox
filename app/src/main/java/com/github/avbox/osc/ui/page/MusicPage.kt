@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.avbox.osc.ui.page

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.github.avbox.osc.R
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.music.LxMusicPlayerActivity

@Composable
fun MusicPage(
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val vm: MusicViewModel = viewModel()
    val context = LocalContext.current

    val hotWords by vm.hotWords.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selectedSource by vm.selectedSource.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val searched by vm.searched.collectAsStateWithLifecycle()
    val scriptOptions by vm.scriptOptions.collectAsStateWithLifecycle()
    val scriptBusy by vm.scriptBusy.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var scriptDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.music_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            )
            IconButton(onClick = { vm.search(query) }) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.music_search_action),
                )
            }
            TextButton(
                onClick = { scriptDialogOpen = true },
                enabled = !scriptBusy,
            ) {
                Text(stringResource(R.string.music_source_switch))
            }
        }

        if (sources.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sources, key = { it.code }) { source ->
                    FilterChip(
                        selected = source.code == selectedSource,
                        onClick = { vm.selectSource(source.code) },
                        label = { Text(sourceLabel(source.code)) },
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                results.isNotEmpty() -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(results, key = { index, _ -> index }) { index, track ->
                            if (index >= results.size - 5) {
                                LaunchedEffect(index) { vm.loadMore() }
                            }
                            MusicTrackRow(
                                track = track,
                                onClick = {
                                    LxMusicPlayerActivity.start(
                                        context,
                                        MusicTrack.listToJson(results),
                                        index,
                                    )
                                },
                            )
                        }
                        if (loadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }

                searched -> {
                    Text(
                        text = stringResource(R.string.music_empty_result),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (scriptBusy) {
                            Text(
                                text = stringResource(R.string.music_script_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                        if (hotWords.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.music_hot_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                hotWords.forEach { word ->
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            query = word
                                            vm.search(word)
                                        },
                                        label = { Text(word) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (scriptDialogOpen) {
        AVBoxAlertDialog(
            onDismissRequest = { scriptDialogOpen = false },
            title = { Text(stringResource(R.string.music_source_switch)) },
            text = {
                Column {
                    scriptOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scriptDialogOpen = false
                                    vm.switchScript(option.id)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (option.selected) "● " else "○ ",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { scriptDialogOpen = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun MusicTrackRow(track: MusicTrack, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.img,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(track.singer, track.albumName)
                    .filter { it.isNotEmpty() }
                    .joinToString(" - "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.intervalText.isNotEmpty()) {
            Text(
                text = track.intervalText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun sourceLabel(code: String): String {
    return when (code) {
        "kg" -> stringResource(R.string.music_source_kg)
        "kw" -> stringResource(R.string.music_source_kw)
        "tx" -> stringResource(R.string.music_source_tx)
        "wy" -> stringResource(R.string.music_source_wy)
        "mg" -> stringResource(R.string.music_source_mg)
        else -> code
    }
}
