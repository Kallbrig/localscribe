package dev.chaseallbright.localscribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chaseallbright.localscribe.ui.history.HistoryScreen
import dev.chaseallbright.localscribe.ui.onboarding.OnboardingScreen
import dev.chaseallbright.localscribe.ui.theme.LocalScribeTheme
import dev.chaseallbright.localscribe.ui.vocabulary.VocabularyScreen

private sealed class Destination(val route: String, val label: String) {
    data object Home : Destination("home", "Home")
    data object History : Destination("history", "History")
    data object Vocabulary : Destination("vocabulary", "Vocabulary")
}

private val destinations = listOf(Destination.Home, Destination.History, Destination.Vocabulary)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalScribeTheme {
                LocalScribeAppRoot()
            }
        }
    }
}

@Composable
private fun LocalScribeAppRoot() {
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination

            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (destination) {
                                    Destination.Home -> Icons.Filled.Home
                                    Destination.History -> Icons.Filled.History
                                    Destination.Vocabulary -> Icons.Filled.TextFields
                                },
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.Home.route) { OnboardingScreen() }
            composable(Destination.History.route) { HistoryScreen() }
            composable(Destination.Vocabulary.route) { VocabularyScreen() }
        }
    }
}
