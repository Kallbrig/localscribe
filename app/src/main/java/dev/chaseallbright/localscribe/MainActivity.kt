package dev.chaseallbright.localscribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chaseallbright.localscribe.ui.theme.LocalScribeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalScribeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OnboardingPlaceholder(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun OnboardingPlaceholder(modifier: Modifier = Modifier) {
    Text(text = "LocalScribe", modifier = modifier, style = MaterialTheme.typography.headlineMedium)
}
