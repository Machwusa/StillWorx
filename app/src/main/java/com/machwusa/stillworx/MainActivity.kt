package com.machwusa.stillworx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.machwusa.stillworx.presentation.BoardRoute
import com.machwusa.stillworx.presentation.BoardViewModel
import com.machwusa.stillworx.ui.theme.StillWorxTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: BoardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StillWorxTheme {
                BoardRoute(viewModel)
            }
        }
    }
}
