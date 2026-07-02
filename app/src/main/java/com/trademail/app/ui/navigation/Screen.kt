package com.trademail.app.ui.navigation

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Inbox : Screen("inbox")
    data object Compose : Screen("compose/{replyTo}/{replySubject}") {
        fun createRoute(replyTo: String = "", replySubject: String = "") =
            "compose/$replyTo/$replySubject"
    }
    data object Settings : Screen("settings")
    data object ModelDownload : Screen("model_download")
    data object EmailDetail : Screen("detail/{emailIndex}") {
        fun createRoute(index: Int) = "detail/$index"
    }
}
