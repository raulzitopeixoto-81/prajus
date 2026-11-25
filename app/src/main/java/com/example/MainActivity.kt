package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.remote.attemptAutoSignIn
import com.example.data.remote.authStateFlow
import com.example.data.remote.signOutFromGoogle
import com.example.data.repository.BrandState
import com.example.data.repository.PrajusRepository
import com.example.ui.screens.AppShell
import com.example.ui.screens.LoginScreen
import com.example.ui.theme.PrajusTheme
import com.example.ui.viewmodel.MainViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
            PrajusRootContent(widthSizeClass = widthSizeClass)
        }
    }
}

@Composable
private fun PrajusRootContent(
    widthSizeClass: WindowWidthSizeClass,
    auth: FirebaseAuth = Firebase.auth
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    var autoSignInFinished by remember { mutableStateOf(auth.currentUser != null) }

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = { autoSignInFinished = true },
                onUnauthenticated = { autoSignInFinished = true },
                scope = scope
            )
        } else {
            autoSignInFinished = true
        }
    }

    val user = currentUser
    if (user == null) {
        PrajusTheme(brand = BrandState()) {
            val snackbarHostState = remember { SnackbarHostState() }
            if (!autoSignInFinished) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { _ ->
                    LoginScreen(
                        onAuthSuccess = { autoSignInFinished = true },
                        onShowSnackbar = { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    )
                }
            }
        }
    } else {
        key(user.uid) {
            AuthenticatedPrajusApp(
                user = user,
                widthSizeClass = widthSizeClass,
                credentialManager = credentialManager
            )
        }
    }
}

@Composable
private fun AuthenticatedPrajusApp(
    user: FirebaseUser,
    widthSizeClass: WindowWidthSizeClass,
    credentialManager: CredentialManager
) {
    val scope = rememberCoroutineScope()
    val uid = user.uid
    val displayName = user.displayName.orEmpty()
    val email = user.email.orEmpty()

    val viewModel: MainViewModel = viewModel(
        key = uid,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                MainViewModel(
                    repo = PrajusRepository(db = db, context = app.applicationContext),
                    currentUserId = uid,
                    googleDisplayName = displayName,
                    googleEmail = email
                )
            }
        }
    )

    val brandState by viewModel.brandState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.snackbar.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    PrajusTheme(brand = brandState) {
        AppShell(
            viewModel = viewModel,
            brand = brandState,
            widthSizeClass = widthSizeClass,
            snackbarHostState = snackbarHostState,
            onSignOut = {
                signOutFromGoogle(
                    credentialManager = credentialManager,
                    scope = scope
                )
            }
        )
    }
}
