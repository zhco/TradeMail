package com.trademail.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trademail.app.ui.inbox.InboxViewModel
import com.trademail.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    inboxViewModel: InboxViewModel = viewModel()
) {
    val account by inboxViewModel.account.collectAsState()

    var email by remember(account.email) { mutableStateOf(account.email) }
    var password by remember { mutableStateOf(account.password) }
    var imapHost by remember(account.imapHost) { mutableStateOf(account.imapHost) }
    var smtpHost by remember(account.smtpHost) { mutableStateOf(account.smtpHost) }
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账户设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 快速配置按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                QuickConfigChip("QQ邮箱", "imap.qq.com", "smtp.qq.com") {
                    imapHost = "imap.qq.com"; smtpHost = "smtp.qq.com"
                }
                QuickConfigChip("163邮箱", "imap.163.com", "smtp.163.com") {
                    imapHost = "imap.163.com"; smtpHost = "smtp.163.com"
                }
                QuickConfigChip("Gmail", "imap.gmail.com", "smtp.gmail.com") {
                    imapHost = "imap.gmail.com"; smtpHost = "smtp.gmail.com"
                }
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("邮箱地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Email, null) }
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码/授权码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                leadingIcon = { Icon(Icons.Default.Lock, null) }
            )

            OutlinedTextField(
                value = imapHost,
                onValueChange = { imapHost = it },
                label = { Text("IMAP 服务器（收件）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Download, null) }
            )

            OutlinedTextField(
                value = smtpHost,
                onValueChange = { smtpHost = it },
                label = { Text("SMTP 服务器（发件）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Upload, null) }
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    inboxViewModel.deleteAccount()
                    // 重新保存
                    viewModel<com.trademail.app.ui.settings.SettingsViewModel>().save(
                        com.trademail.app.model.Account(
                            email = email, password = password,
                            imapHost = imapHost, smtpHost = smtpHost
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = email.isNotBlank() && password.isNotBlank() && imapHost.isNotBlank() && smtpHost.isNotBlank()
            ) {
                Text("保存并连接")
            }

            Spacer(Modifier.weight(1f))

            // 辅助说明
            Card(colors = CardDefaults.cardColors(containerColor = Gray50)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("提示", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("QQ邮箱请使用授权码而非登录密码。在 QQ 邮箱设置 → 账户 → POP3/IMAP/SMTP 服务中生成。",
                        style = MaterialTheme.typography.bodySmall, color = Gray600)
                }
            }
        }
    }
}

@Composable
fun QuickConfigChip(label: String, imap: String, smtp: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) }
    )
}
