package com.jigong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jigong.data.WorkRepo
import com.jigong.ui.HomeScreen
import com.jigong.ui.JigongTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 全进程一份，别每次重建 Activity 都开一条新连接（见 WorkRepo.get 的注释）
        val repo = WorkRepo.get(this)

        setContent {
            JigongTheme {
                HomeScreen(repo)
            }
        }
    }
}
