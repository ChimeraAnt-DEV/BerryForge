package dev.chimeraant.berryforge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chimeraant.berryforge.ui.components.BerryBottomBar
import dev.chimeraant.berryforge.ui.components.BerryEmptyState
import dev.chimeraant.berryforge.ui.components.BerrySnackbar
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.screens.BuildScreen
import dev.chimeraant.berryforge.ui.screens.EditorScreen
import dev.chimeraant.berryforge.ui.screens.ToolchainWizardScreen
import dev.chimeraant.berryforge.ui.screens.FileTreeScreen
import dev.chimeraant.berryforge.ui.screens.ReposScreen
import dev.chimeraant.berryforge.ui.screens.SignInScreen

/**
 * Top-level navigation.
 *
 * Deliberately not using Navigation-Compose's graph: the app has six destinations and a
 * handful of modal sub-screens, and hand-rolling the stack keeps the tab switch
 * animation instant and avoids a route-string layer that adds nothing here.
 */
@Composable
fun BerryApp(viewModel: BerryViewModel) {
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()

    var destination by remember { mutableStateOf(Destination.Repos) }
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var showWizard by remember { mutableStateOf(false) }
    var jumpToLine by remember { mutableStateOf<Int?>(null) }
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
    val toolchainReady by viewModel.toolchainReady.collectAsStateWithLifecycle()

    LaunchedEffect(signedIn, onboardingDone, toolchainReady) {
        if (signedIn && !onboardingDone && !toolchainReady) showWizard = true
    }

    Box(Modifier.fillMaxSize().background(BerryColors.Base)) {
        when {
            !signedIn -> SignInScreen(viewModel = viewModel)

            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = destination to openFilePath,
                        transitionSpec = {
                            val forward = (targetState.first.ordinal >= initialState.first.ordinal)
                            val offset = if (forward) 1 else -1
                            (slideInHorizontally(animationSpec = tween(240)) { width -> offset * width / 6 } +
                                fadeIn(tween(200))) togetherWith
                                (slideOutHorizontally(animationSpec = tween(200)) { width -> -offset * width / 6 } +
                                    fadeOut(tween(140)))
                        },
                        label = "destination",
                    ) { (dest, file) ->
                        when {
                            file != null -> EditorScreen(
                                viewModel = viewModel,
                                path = file,
                                onBack = { openFilePath = null },
                                onOpenBuildLog = { destination = Destination.Build; openFilePath = null },
                            )

                            dest == Destination.Repos -> {
                                val repo by viewModel.openRepo.collectAsStateWithLifecycle()
                                if (repo == null) {
                                    ReposScreen(
                                        viewModel = viewModel,
                                        onOpenRepo = { viewModel.openRepo(it) },
                                        onProfileClick = { destination = Destination.Settings },
                                    )
                                } else {
                                    FileTreeScreen(
                                        viewModel = viewModel,
                                        onOpenFile = { openFilePath = it },
                                        onBack = { viewModel.closeRepo() },
                                        onProfileClick = { destination = Destination.Settings },
                                    )
                                }
                            }

                            dest == Destination.Build -> BuildScreen(
                                viewModel = viewModel,
                                onOpenFileAtLine = { path, line ->
                                    jumpToLine = line
                                    openFilePath = path
                                },
                                onProfileClick = { destination = Destination.Settings },
                            )

                            dest == Destination.Editor -> {
                                val repo by viewModel.openRepo.collectAsStateWithLifecycle()
                                if (repo == null) {
                                    dev.chimeraant.berryforge.ui.components.BerryEmptyState(
                                        icon = BerryIcons.FileCode,
                                        title = "Nothing open",
                                        body = "Pick a repository and open a file to edit it.",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    FileTreeScreen(
                                        viewModel = viewModel,
                                        onOpenFile = { openFilePath = it },
                                        onBack = { destination = Destination.Repos },
                                        onProfileClick = { destination = Destination.Settings },
                                    )
                                }
                            }

                            else -> BerryEmptyState(
                                icon = BerryIcons.Layers,
                                title = dest.label,
                                body = "This surface arrives in a later phase.",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                BerryBottomBar(
                    current = destination,
                    onSelect = { destination = it },
                )
            }
        }

        BerrySnackbar(
            message = viewModel.toastValue(),
            onDismiss = { viewModel.toast(null) },
            icon = BerryIcons.Info,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
    }
}

