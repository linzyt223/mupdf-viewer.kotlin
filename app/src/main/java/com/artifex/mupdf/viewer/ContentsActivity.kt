package com.artifex.mupdf.viewer

// MainActivity.kt
import ImageGridScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.mupdfviewer.ui.theme.MuPDFViewerTheme

class ContentsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MuPDFViewerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ImageGridScreen(isVertical = true) // 수직 방향 그리드
                }
            }
        }
    }
}
