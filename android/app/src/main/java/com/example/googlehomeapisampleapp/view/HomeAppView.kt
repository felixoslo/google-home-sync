/* Copyright 2025 Google LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package no.klevenberg.homeyroomsync.view

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import no.klevenberg.homeyroomsync.AccountSwitchProxyActivity
import no.klevenberg.homeyroomsync.MainActivity
import no.klevenberg.homeyroomsync.cloudlinking.CloudLinkingBottomSheet
import no.klevenberg.homeyroomsync.cloudlinking.CloudLinkingViewModel
import no.klevenberg.homeyroomsync.commissioning.OtaUpdateScreen
import no.klevenberg.homeyroomsync.commissioning.qrcodescanner.MatterQrCodeScanner
import no.klevenberg.homeyroomsync.history.HistoryVideoPlayerScreen
import no.klevenberg.homeyroomsync.history.HistoryView
import no.klevenberg.homeyroomsync.searchablehome.SearchableHomeBottomSheetView
import no.klevenberg.homeyroomsync.ui.theme.GoogleHomeAPISampleAppTheme
import no.klevenberg.homeyroomsync.view.automations.ActionView
import no.klevenberg.homeyroomsync.view.automations.AutomationView
import no.klevenberg.homeyroomsync.view.automations.AutomationsView
import no.klevenberg.homeyroomsync.view.automations.CandidatesView
import no.klevenberg.homeyroomsync.view.automations.DraftView
import no.klevenberg.homeyroomsync.view.automations.StarterView
import no.klevenberg.homeyroomsync.view.devices.DeviceView
import no.klevenberg.homeyroomsync.view.devices.DevicesView
import no.klevenberg.homeyroomsync.view.hubs.HubDiscoveryView
import no.klevenberg.homeyroomsync.view.settings.PresenceSettingsView
import no.klevenberg.homeyroomsync.view.usermanagement.UserManagementView
import no.klevenberg.homeyroomsync.viewmodel.HomeAppViewModel
import no.klevenberg.homeyroomsync.viewmodel.automations.ActionViewModel
import no.klevenberg.homeyroomsync.viewmodel.automations.AutomationViewModel
import no.klevenberg.homeyroomsync.viewmodel.automations.CandidateViewModel
import no.klevenberg.homeyroomsync.viewmodel.automations.DraftViewModel
import no.klevenberg.homeyroomsync.viewmodel.automations.StarterViewModel
import no.klevenberg.homeyroomsync.viewmodel.devices.DeviceViewModel
import no.klevenberg.homeyroomsync.viewmodel.structures.RoomViewModel
import no.klevenberg.homeyroomsync.viewmodel.usermanagement.UserManagementViewModel
import no.klevenberg.homeyroomsync.viewmodel.ota.OtaUiState
import no.klevenberg.homeyroomsync.roomsync.RoomSyncLog
import no.klevenberg.homeyroomsync.roomsync.RoomSyncRunner
import no.klevenberg.homeyroomsync.roomsync.RoomSyncScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The main Composable function for the Google Home API Sample App. This function displays the
 * appropriate view based on the state of the [HomeAppViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAppView(homeAppVM: HomeAppViewModel) {
  /** Value tracking whether a user is signed-in on the app * */
  val isSignedIn: Boolean = homeAppVM.homeApp.permissionsManager.isSignedIn.collectAsState().value

  /** Values tracking what is being selected on the app * */
  val selectedTab: HomeAppViewModel.NavigationTab by homeAppVM.selectedTab.collectAsState()
  val selectedDeviceVM: DeviceViewModel? by homeAppVM.selectedDeviceVM.collectAsState()
  val selectedAutomationVM: AutomationViewModel? by homeAppVM.selectedAutomationVM.collectAsState()
  val selectedCandidateVMs: List<CandidateViewModel>? by
    homeAppVM.selectedCandidateVMs.collectAsState()
  val selectedDraftVM: DraftViewModel? by homeAppVM.selectedDraftVM.collectAsState()
  val selectedStarterVM: StarterViewModel? =
    selectedDraftVM?.selectedStarterVM?.collectAsState()?.value
  val selectedActionVM: ActionViewModel? =
    selectedDraftVM?.selectedActionVM?.collectAsState()?.value
  val showCreateRoom = remember { mutableStateOf(false) }
  val showHomeyRoomSync = remember { mutableStateOf(false) }
  val showLanding = remember { mutableStateOf(true) }
  val roomSettingsFor = remember { mutableStateOf<RoomViewModel?>(null) }
  val moveDeviceFor = remember { mutableStateOf<DeviceViewModel?>(null) }
  val launchHubDiscovery = remember { mutableStateOf(false) }
  val showSearchableHome = remember { mutableStateOf(false) }
  val showUserManagement = remember { mutableStateOf(false) }
  val showPresenceSettings = remember { mutableStateOf(false) }
  val selectedStructureVM by homeAppVM.selectedStructureVM.collectAsState()

  val showQrCodeScanner by homeAppVM.showQrCodeScanner.collectAsStateWithLifecycle()

  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  LaunchedEffect(Unit) {
    homeAppVM.navigateToProxyActivity.collect {
      Log.i(
        MainActivity.TAG,
        "HomeActivity: Received navigate event from ViewModel. Launching AccountSwitchProxyActivity.",
      )
      val intent =
        Intent(context, AccountSwitchProxyActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
      context.startActivity(intent)
    }
  }

  val hubActivationLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult(),
      onResult = { result ->
        if (result.resultCode == Activity.RESULT_OK) {
          scope.launch { snackbarHostState.showSnackbar("Hub activation process completed.") }
        } else {
          homeAppVM.handleActivationFailure(result.resultCode)
          scope.launch { snackbarHostState.showSnackbar("Hub activation cancelled or failed.") }
        }
        launchHubDiscovery.value = false
      },
    )

  LaunchedEffect(true) {
    homeAppVM.hubDiscoveryViewModel.hubActivationIntentFlow.collect { intent ->
      hubActivationLauncher.launch(intent)
    }
  }

  val selectedHistoryDeviceVM by homeAppVM.selectedHistoryDeviceVM.collectAsStateWithLifecycle()
  val selectedVideoEvent by homeAppVM.selectedVideoEvent.collectAsStateWithLifecycle()

  // PermissionsManager already subscribes to permission changes. Never add duplicate collectors.

  // Run only after explicit Google Home permission and structure selection. In-app automation
  // is complemented by the scheduled WorkManager worker for when Android allows background work.
  LaunchedEffect(isSignedIn, selectedStructureVM?.id) {
    val selected = selectedStructureVM
    if (isSignedIn && selected != null) {
      // Never silently rebind to another home: the configured Google Home.
      val pinned = RoomSyncLog.structureId(context)
      val approvedName = selected.name.trim().equals(no.klevenberg.homeyroomsync.roomsync.SyncSettings.HOME_NAME,ignoreCase=true)
      if (pinned.isBlank() && approvedName) RoomSyncLog.setStructureId(context,selected.id)
      if (RoomSyncLog.structureId(context) == selected.id) {
        RoomSyncScheduler.ensure(context)
        delay(3500)
        while (homeAppVM.homeApp.permissionsManager.isSignedIn.value) {
          RoomSyncRunner.execute(context,selected.structure,client=homeAppVM.homeApp.homeClient)
          delay(30*60*1000L)
        }
      } else {
        RoomSyncLog.setStatus(context,"Sperret: Feil Google Home-hjem (forventet konfigurert hjem)")
      }
    }
  }


  // Apply theme on the top-level view:
  GoogleHomeAPISampleAppTheme {
    // Top-level external frame for the views:
    Column(modifier = Modifier.fillMaxSize()) {
      Spacer(modifier = Modifier.height(48.dp).fillMaxWidth().background(Color.Transparent))

      if (showQrCodeScanner) {
        MatterQrCodeScanner(
          onQrCodeScanned = { payload ->
            // Call the ViewModel to close the scanner and start the commissioning API call
            homeAppVM.onCommissionCamera(payload)
          },
          onPermissionDenied = {
            homeAppVM.closeQrCodeScanner()
            scope.launch { snackbarHostState.showSnackbar("Camera permission denied.") }
          },
          onClose = { homeAppVM.closeQrCodeScanner() },
        )
        return@Column // Critical: Stop rendering the rest of the view hierarchy
      }

      val showOtaScreen by homeAppVM.showOtaScreen.collectAsStateWithLifecycle()

      if (showOtaScreen) {
        val otaUiState by homeAppVM.otaUiState.collectAsStateWithLifecycle()
        val otaDeviceName by homeAppVM.otaDeviceName.collectAsStateWithLifecycle()

        OtaUpdateScreen(
          deviceName = otaDeviceName,
          otaUiState = otaUiState,
          onComplete = { homeAppVM.closeOtaScreen() },
          paddingValues = PaddingValues(),
        )
        return@Column
      }

      Column(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Transparent)) {
        if (!isSignedIn) {
          WelcomeView(homeAppVM)
        } else if(showLanding.value) {
          val last=RoomSyncLog.lastV2Read(context)
          val displayDate=if(last>0) java.time.format.DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(last)) else "Ingen test"
          HomeySyncLandingView(
            homeName=selectedStructureVM?.name,
            readTime=displayDate,
            onOpenSync={showHomeyRoomSync.value=true},
            onOpenDevices={showLanding.value=false},
            onPermissions={homeAppVM.homeApp.permissionsManager.requestPermissions()}
          )
        } else {
          TextButton(onClick={showLanding.value=true}) {
            Text("← Til Homey Room Sync")
          }
        // Video player screen — shown when a history event or HomeBrief thumbnail is tapped
        selectedVideoEvent?.let { event ->
          HistoryVideoPlayerScreen(event = event, onBack = { homeAppVM.closeVideoPlayer() })
          return@Column
        }
        if (selectedHistoryDeviceVM != null) {
          HistoryView(viewModel = homeAppVM)
          return@Column
        }
        if (showUserManagement.value) {
          val userManagementViewModel: UserManagementViewModel = viewModel()
            UserManagementView(
              viewModel = userManagementViewModel,
              homeAppVM = homeAppVM,
              onBack = { showUserManagement.value = false }
            )
            return@Column
          }
        if (showPresenceSettings.value) {
          PresenceSettingsView(
            homeAppVM = homeAppVM,
            onBack = { showPresenceSettings.value = false }
          )
          return@Column
        }
        if (selectedDeviceVM != null) {
          DeviceView(homeAppVM)
        }
        if (selectedAutomationVM != null) {
          AutomationView(homeAppVM)
        }
        if (selectedStarterVM != null) {
          StarterView(homeAppVM)
        }
        if (selectedActionVM != null) {
          ActionView(homeAppVM)
        }
        if (selectedDraftVM != null) {
          DraftView(homeAppVM)
        }
        if (selectedCandidateVMs != null) {
          CandidatesView(homeAppVM)
        }
        when (selectedTab) {
          HomeAppViewModel.NavigationTab.DEVICES ->
            DevicesView(
              homeAppVM = homeAppVM,
              onRequestCreateRoom = { showCreateRoom.value = true },
              onRequestHomeyRoomSync = { showHomeyRoomSync.value = true },
              onRequestRoomSettings = { room -> roomSettingsFor.value = room },
              onRequestMoveDevice = { device -> moveDeviceFor.value = device },
              onRequestAddHub = {
                homeAppVM.startHubDiscovery()
                launchHubDiscovery.value = true
              },
              onSearchHome = { showSearchableHome.value = true },
              onNavigateToUserManagement = { showUserManagement.value = true },
              onNavigateToPresenceSettings = { showPresenceSettings.value = true }
            )

          HomeAppViewModel.NavigationTab.AUTOMATIONS ->
            AutomationsView(
              homeAppVM = homeAppVM,
              onNavigateToUserManagement = { showUserManagement.value = true },
              onNavigateToPresenceSettings = { showPresenceSettings.value = true }
            )

          HomeAppViewModel.NavigationTab.HISTORY -> {
            androidx.compose.material3.Surface(
              color = MaterialTheme.colorScheme.background,
              modifier = Modifier.fillMaxSize(),
            ) {
              HistoryView(viewModel = homeAppVM)
            }
          }
        }
        }
      }

      // Room and Hub Dialogs
      if (showHomeyRoomSync.value) {
        selectedStructureVM?.let { vm ->
          HomeyRoomSyncSheet(structureVM = vm, homeClient = homeAppVM.homeApp.homeClient, onClose = { showHomeyRoomSync.value = false })
        }
      }
      if (showCreateRoom.value) {
        val roomName = remember { mutableStateOf("") }
        AlertDialog(
          onDismissRequest = { showCreateRoom.value = false },
          title = { Text("Create Room") },
          text = {
            OutlinedTextField(
              value = roomName.value,
              onValueChange = { roomName.value = it },
              label = { Text("Room name") },
              singleLine = true,
            )
          },
          confirmButton = {
            TextButton(
              onClick = {
                val name = roomName.value.trim()
                if (name.isNotEmpty()) {
                  homeAppVM.createRoomInSelectedStructure(name)
                }
                showCreateRoom.value = false
              }
            ) {
              Text("Create")
            }
          },
          dismissButton = {
            TextButton(onClick = { showCreateRoom.value = false }) { Text("Cancel") }
          },
        )
      }

      roomSettingsFor.value?.let { activeRoom ->
        ModalBottomSheet(onDismissRequest = { roomSettingsFor.value = null }) {
          Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
              Text("Room settings", modifier = Modifier.weight(1f))
              TextButton(
                onClick = {
                  homeAppVM.deleteRoomFromSelectedStructure(activeRoom)
                  roomSettingsFor.value = null
                }
              ) {
                Text("Delete")
              }
            }
            val renameText = remember(activeRoom.id) { mutableStateOf(activeRoom.name.value) }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
              OutlinedTextField(
                value = renameText.value,
                onValueChange = { renameText.value = it },
                label = { Text("Room name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
              Spacer(Modifier.width(12.dp))
              TextButton(
                onClick = {
                  val newName = renameText.value.trim()
                  if (newName.isNotEmpty() && newName != activeRoom.name.value) {
                    homeAppVM.viewModelScope.launch { activeRoom.renameRoom(newName) }
                  }
                  roomSettingsFor.value = null
                }
              ) {
                Text("Save")
              }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { roomSettingsFor.value = null }) { Text("Close") }
          }
        }
      }

      moveDeviceFor.value?.let { deviceToMove ->
        ModalBottomSheet(onDismissRequest = { moveDeviceFor.value = null }) {
          val structureVM = homeAppVM.selectedStructureVM.collectAsState().value
          val rooms: List<RoomViewModel> =
            structureVM?.roomVMs?.collectAsState()?.value ?: emptyList()

          val expanded = remember { mutableStateOf(false) }
          val selectedRoom = remember { mutableStateOf<RoomViewModel?>(null) }

          Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Move \"${deviceToMove.name.collectAsState().value}\" to…")
            Spacer(Modifier.height(12.dp))

            ExposedDropdownMenuBox(
              expanded = expanded.value,
              onExpandedChange = { expanded.value = !expanded.value },
            ) {
              TextField(
                readOnly = true,
                value = selectedRoom.value?.name?.value ?: "Select a room",
                onValueChange = {},
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
              )
              ExposedDropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
              ) {
                rooms.forEach { room ->
                  val roomName by room.name.collectAsState()
                  DropdownMenuItem(
                    text = { Text(roomName) },
                    onClick = {
                      selectedRoom.value = room
                      expanded.value = false
                    },
                  )
                }
              }
            }

            Spacer(Modifier.height(16.dp))
            Button(
              onClick = {
                selectedRoom.value?.let { target ->
                  homeAppVM.moveDeviceToRoom(deviceToMove, target)
                  moveDeviceFor.value = null
                }
              },
              enabled = selectedRoom.value != null,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text("Move")
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { moveDeviceFor.value = null }) { Text("Close") }
          }
        }
      }

      if (launchHubDiscovery.value) {
        Dialog(
          onDismissRequest = { launchHubDiscovery.value = false },
          properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
          HubDiscoveryView(
            hubDiscoveryViewModel = homeAppVM.hubDiscoveryViewModel,
            modifier = Modifier.padding(16.dp),
          )
        }
      }

      if (showSearchableHome.value) {
        selectedStructureVM?.let { structure ->
          SearchableHomeBottomSheetView(
            structureId = structure.id,
            onDismissRequest = { showSearchableHome.value = false },
          )
        }
      }

      val showCloudLinkingSheet by homeAppVM.showCloudLinkingSheet.collectAsStateWithLifecycle()
      if (showCloudLinkingSheet) {
        val cloudLinkingViewModel: CloudLinkingViewModel =
          androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
        CloudLinkingBottomSheet(
          viewModel = cloudLinkingViewModel,
          onDismissRequest = { homeAppVM.closeCloudLinkingSheet() },
        )
      }
    }
  }
}
