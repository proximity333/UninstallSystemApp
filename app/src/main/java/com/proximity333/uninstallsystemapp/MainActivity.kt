package com.proximity333.uninstallsystemapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.proximity333.uninstallsystemapp.feature.MainViewModel
import com.proximity333.uninstallsystemapp.ui.MainScreen
import com.proximity333.uninstallsystemapp.ui.theme.UninstallerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UninstallerTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    viewModel.messages.collect { message ->
                        snackbarHostState.showSnackbar(message)
                    }
                }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshConnection()
                }

                MainScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    viewModel = viewModel,
                )
            }
        }
    }
}