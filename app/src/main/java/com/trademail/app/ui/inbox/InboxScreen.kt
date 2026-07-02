package com.trademail.app.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trademail.app.TradeMailApp
import com.trademail.app.model.Email
import com.trademail.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onNavigateToSetup: () -> Unit,
    onNavigateToCompose: (String, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToModel: () -> Unit,
    viewModel: InboxViewModel = viewModel()
) {
    val emails by viewModel.emails.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val account by viewModel.account.collectAsState()

    LaunchedEffect(account.email) {
        if (account.email.isNotBlank()) viewModel.refresh()
    }

    if (account.email.isBlank()) {
        // 未配置账户 → 引导设置
        SetupPrompt(onNavigateToSetup)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收件箱", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "设置", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigateToCompose("", "") },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Edit, "写邮件", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 翻译状态提示
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            error?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.refresh() }) { Text("重试") }
                    }
                }
            }

            if (emails.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Inbox, "空", modifier = Modifier.size(64.dp), tint = Gray200)
                        Spacer(Modifier.height(8.dp))
                        Text("收件箱为空", color = Gray600)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(emails) { _, email ->
                        EmailListItem(email = email)
                        HorizontalDivider(color = Gray200, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun EmailListItem(email: Email) {
    val dateStr = if (email.receivedDate > 0) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(email.receivedDate))
    } else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* 查看详情 */ }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = email.from,
                fontWeight = if (!email.isRead) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = dateStr,
                style = MaterialTheme.typography.bodySmall,
                color = Gray600
            )
        }
        Spacer(Modifier.height(4.dp))

        // 原文主题
        Text(
            text = email.subject,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (!email.isRead) FontWeight.Medium else FontWeight.Normal
        )

        // 翻译后主题
        if (email.translatedSubject.isNotBlank() && email.translatedSubject != email.subject) {
            Text(
                text = email.translatedSubject,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = Blue600
            )
        }

        Spacer(Modifier.height(2.dp))

        // 翻译后的正文预览
        val displayBody = email.translatedBody.ifBlank { email.bodyPlain }
        Text(
            text = displayBody.take(120),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = Gray600
        )
    }
}

@Composable
fun SetupPrompt(onNavigate: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Mail, "邮件", modifier = Modifier.size(80.dp), tint = Gray200)
            Spacer(Modifier.height(16.dp))
            Text("欢迎使用 TradeMail", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("外贸专用邮箱客户端，自动中英互译，完全离线", color = Gray600)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNavigate) { Text("配置邮箱账户") }
        }
    }
}
