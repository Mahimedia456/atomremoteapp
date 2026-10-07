package com.atomosremote.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.atomosremote.nativeapp.ui.AtomosApp
import com.atomosremote.nativeapp.ui.theme.AtomosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtomosTheme { AtomosApp() }
        }
    }
}
