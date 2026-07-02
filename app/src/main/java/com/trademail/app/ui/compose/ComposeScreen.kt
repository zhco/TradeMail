package com.trademail.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trademail.app.TradeMailApp
import com.trademail.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    replyTo: String,
    replySubject: String,
    onBack: () -> Unit,
    viewModel: ComposeViewModel = viewModel()
) {
    val isSending by viewModel.isSending.collectAsState()
    val sendResult by viewModel.sendResult.collectAsState()
    val account by viewModel.account.collectAsState()

    var to by remember { mutableStateOf(replyTo) }
    var subject by remember { mutableStateOf(if (replySubject.startsWith("Re:")) replySubject else "Re: $replySubject") }
    var body by remember { mutableStateOf("") }

    val isReply = replyTo.isNotBlank()

    LaunchedEffect(sendResult) {
        if (sendResult == "发送成功") {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isReply) "回复邮件" else "写邮件", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.sendNew(to, subject, body) },
                        enabled = !isSending && to.isNotBlank() && subject.isNotBlank() && body.isNotBlank()
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, "发送")
                        }
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
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)
        ) {
            // 翻译提示
            Card(
                colors = CardDefaults.cardColors(containerColor = Blue50),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "输入中文，发送时自动翻译为英文",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Blue700
                )
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = { Text("收件人") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = isReply,
                enabled = !isReply
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("主题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("邮件正文（中文）") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                maxLines = Int.MAX_VALUE
            )

            sendResult?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = if (msg == "发送成功") Green500 else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
