# TradeMail

外贸专用邮箱客户端，基于腾讯混元 Hy-MT 离线翻译模型，收件自动英译中，写回复自动中译英，完全离线运行。

## 技术栈
- Kotlin + Jetpack Compose
- Hy-MT1.5-1.8B 离线翻译 (ONNX Runtime)
- Jakarta Mail (IMAP/SMTP)
- GitHub Actions 自动构建 APK
