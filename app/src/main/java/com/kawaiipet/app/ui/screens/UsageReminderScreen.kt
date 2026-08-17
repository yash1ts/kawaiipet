package com.kawaiipet.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.kawaiipet.app.R
import com.kawaiipet.app.usage.InstalledAppsRepository
import com.kawaiipet.app.usage.LaunchableApp
import com.kawaiipet.app.usage.UsageReminderApp
import com.kawaiipet.app.usage.UsageReminderService
import com.kawaiipet.app.util.PermissionHelper
import com.kawaiipet.app.util.PreferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@HiltViewModel
class UsageReminderViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val prefs: PreferenceManager,
    private val installedAppsRepository: InstalledAppsRepository,
) : ViewModel() {
    val enabled = prefs.usageReminderEnabled
    val targets = prefs.usageReminderTargets
    val limitMinutes = prefs.usageReminderLimitMinutes

    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()

    init {
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            _appsLoading.value = true
            _apps.value = runCatching { installedAppsRepository.loadLaunchableApps() }
                .getOrDefault(emptyList())
            _appsLoading.value = false
        }
    }

    fun syncMonitor() {
        viewModelScope.launch {
            UsageReminderService.syncWithPrefs(appContext, prefs)
        }
    }

    suspend fun setEnabled(value: Boolean) {
        prefs.setUsageReminderEnabled(value)
        UsageReminderService.syncWithPrefs(appContext, prefs)
    }

    suspend fun toggleTarget(app: LaunchableApp) {
        val current = prefs.usageReminderTargets.first()
        val exists = current.any { it.packageName == app.packageName }
        val next = if (exists) {
            current.filterNot { it.packageName == app.packageName }
        } else {
            if (current.size >= PreferenceManager.USAGE_REMINDER_MAX_APPS) return
            current + UsageReminderApp(packageName = app.packageName, label = app.label)
        }
        prefs.setUsageReminderTargets(next)
        if (next.isEmpty()) {
            prefs.setUsageReminderEnabled(false)
        }
        UsageReminderService.syncWithPrefs(appContext, prefs)
    }

    suspend fun removeTarget(packageName: String) {
        val next = prefs.usageReminderTargets.first()
            .filterNot { it.packageName == packageName }
        prefs.setUsageReminderTargets(next)
        if (next.isEmpty()) {
            prefs.setUsageReminderEnabled(false)
        }
        UsageReminderService.syncWithPrefs(appContext, prefs)
    }

    suspend fun setLimitMinutes(minutes: Int) {
        prefs.setUsageReminderLimitMinutes(minutes)
    }

    suspend fun clearTargets() {
        prefs.clearUsageReminderTargets()
        prefs.setUsageReminderEnabled(false)
        UsageReminderService.syncWithPrefs(appContext, prefs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageReminderScreen(
    navController: NavController,
    viewModel: UsageReminderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val enabled by viewModel.enabled.collectAsState(initial = false)
    val selectedTargets by viewModel.targets.collectAsState(initial = emptyList())
    val limitMinutes by viewModel.limitMinutes.collectAsState(
        initial = PreferenceManager.USAGE_REMINDER_LIMIT_DEFAULT_MIN,
    )
    val apps by viewModel.apps.collectAsState()
    val appsLoading by viewModel.appsLoading.collectAsState()

    var hasUsageAccess by remember {
        mutableStateOf(PermissionHelper.hasUsageAccessPermission(context))
    }
    var showAppPicker by remember { mutableStateOf(false) }
    var appQuery by remember { mutableStateOf("") }

    val selectedPackages = remember(selectedTargets) {
        selectedTargets.map { it.packageName }.toSet()
    }
    val hasTargets = selectedTargets.isNotEmpty()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageAccess = PermissionHelper.hasUsageAccessPermission(context)
                viewModel.syncMonitor()
                if (hasUsageAccess) viewModel.refreshApps()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.syncMonitor()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usage_reminder_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (showAppPicker) {
            AppPickerContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                apps = apps,
                loading = appsLoading,
                query = appQuery,
                onQueryChange = { appQuery = it },
                selectedPackages = selectedPackages,
                maxApps = PreferenceManager.USAGE_REMINDER_MAX_APPS,
                onToggle = { app ->
                    scope.launch { viewModel.toggleTarget(app) }
                },
                onDone = {
                    showAppPicker = false
                    appQuery = ""
                },
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.usage_reminder_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (!hasUsageAccess) {
                Text(
                    text = stringResource(R.string.usage_reminder_permission_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        context.startActivity(
                            PermissionHelper.createUsageAccessSettingsIntent().apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.usage_reminder_grant_access))
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Text(
                    text = stringResource(R.string.usage_reminder_permission_granted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = stringResource(R.string.usage_reminder_app_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.usage_reminder_app_count,
                    selectedTargets.size,
                    PreferenceManager.USAGE_REMINDER_MAX_APPS,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            selectedTargets.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { viewModel.removeTarget(app.packageName) } }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.usage_reminder_remove_app),
                        )
                    }
                }
                HorizontalDivider()
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    viewModel.refreshApps()
                    showAppPicker = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasUsageAccess,
            ) {
                Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.usage_reminder_pick_apps))
            }
            if (hasTargets) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { scope.launch { viewModel.clearTargets() } }) {
                    Text(stringResource(R.string.usage_reminder_clear_apps))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.usage_reminder_limit_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.usage_reminder_limit_value, limitMinutes),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(R.string.usage_reminder_limit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = limitMinutes.toFloat(),
                onValueChange = { v ->
                    scope.launch { viewModel.setLimitMinutes(v.roundToInt()) }
                },
                valueRange = PreferenceManager.USAGE_REMINDER_LIMIT_MIN_MIN.toFloat()..
                    PreferenceManager.USAGE_REMINDER_LIMIT_MAX_MIN.toFloat(),
                steps = (PreferenceManager.USAGE_REMINDER_LIMIT_MAX_MIN -
                    PreferenceManager.USAGE_REMINDER_LIMIT_MIN_MIN) - 1,
                enabled = hasUsageAccess && hasTargets,
            )

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.usage_reminder_enable),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.usage_reminder_enable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        scope.launch { viewModel.setEnabled(checked) }
                    },
                    enabled = hasUsageAccess && hasTargets,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.usage_reminder_overlay_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppPickerContent(
    modifier: Modifier,
    apps: List<LaunchableApp>,
    loading: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedPackages: Set<String>,
    maxApps: Int,
    onToggle: (LaunchableApp) -> Unit,
    onDone: () -> Unit,
) {
    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }
    val atMax = selectedPackages.size >= maxApps

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.usage_reminder_pick_apps),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.usage_reminder_sorted_by_recent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.usage_reminder_done))
            }
        }
        Text(
            text = stringResource(R.string.usage_reminder_app_count, selectedPackages.size, maxApps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.usage_reminder_search_apps)) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (loading && apps.isEmpty()) {
            Text(
                text = stringResource(R.string.usage_reminder_loading_apps),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    val selected = app.packageName in selectedPackages
                    val canSelect = selected || !atMax
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canSelect) { onToggle(app) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (canSelect) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
