package com.trademail.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.trademail.app.TradeMailApp
import com.trademail.app.translate.ModelDownloader
import com.trademail.app.ui.compose.ComposeScreen
import com.trademail.app.ui.inbox.InboxScreen
import com.trademail.app.ui.settings.SettingsScreen
import com.trademail.app.ui.theme.*

@Composable
fun NavGraph(navController: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as TradeMailApp

    NavHost(navController = navController, startDestination = Screen.Inbox.route) {
        composable(Screen.Inbox.route) {
            InboxScreen(
                onNavigateToSetup = { navController.navigate(Screen.Settings.route) },
                onNavigateToCompose = { to, subject ->
                    navController.navigate(Screen.Compose.createRoute(to, subject))
                },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToModel = { navController.navigate(Screen.ModelDownload.route) }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.Compose.route,
            arguments = listOf(
                navArgument("replyTo") { type = NavType.StringType; defaultValue = "" },
                navArgument("replySubject") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val replyTo = backStackEntry.arguments?.getString("replyTo") ?: ""
            val replySubject = backStackEntry.arguments?.getString("replySubject") ?: ""
            ComposeScreen(
                replyTo = replyTo,
                replySubject = replySubject,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ModelDownload.route) {
            ModelDownloadScreen(
                modelDownloader = app.modelDownloader,
                onBack = { navController.popBackStack() },
                onComplete = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(
    modelDownloader: ModelDownloader,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val progress by modelDownloader.progress.collectAsState()
    val status by modelDownloader.status.collectAsState()
    val isDownloaded = modelDownloader.isModelDownloaded()

    LaunchedEffect(status) {
        if (status == ModelDownloader.DownloadStatus.COMPLETE) {
            onComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载翻译模型", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isDownloaded) {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(80.dp), tint = Green500)
                Spacer(Modifier.height(16.dp))
                Text("模型已就绪", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("离线翻译引擎已可用", color = Gray600)
            } else {
                Icon(
                    if (status == ModelDownloader.DownloadStatus.DOWNLOADING) Icons.Default.Downloading
                    else Icons.Default.CloudDownload,
                    null,
                    modifier = Modifier.size(80.dp),
                    tint = Blue600
                )
                Spacer(Modifier.height(16.dp))
                Text("翻译模型", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text("%.0f MB".format(modelDownloader.getModelSizeMB()), color = Gray600)
                Spacer(Modifier.height(24.dp))

                if (status == ModelDownloader.DownloadStatus.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Blue600,
                        trackColor = Gray200,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("%.0f%%".format(progress * 100), style = MaterialTheme.typography.bodyMedium)
                }

                if (status == ModelDownloader.DownloadStatus.ERROR) {
                    Text("下载失败，请检查网络后重试", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { /* 触发下载 */ },
                    enabled = status != ModelDownloader.DownloadStatus.DOWNLOADING
                ) {
                    Text(if (status == ModelDownloader.DownloadStatus.DOWNLOADING) "下载中..." else "开始下载")
                }
            }
        }
    }
}
