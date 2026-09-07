package io.github.jaytech64.depenses

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.jaytech64.depenses.core.designsystem.DepensesTheme
import io.github.jaytech64.depenses.navigation.DepensesApp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DepensesTheme {
                DepensesApp()
            }
        }
    }
}
