/* Copyright 2025 Google LLC. Modifications copyright 2026 contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package no.klevenberg.homeyroomsync.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.klevenberg.homeyroomsync.viewmodel.HomeAppViewModel

@Composable
fun WelcomeView(homeAppVM:HomeAppViewModel) {
  val context=LocalContext.current
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    .padding(horizontal=22.dp,vertical=22.dp),
    verticalArrangement=Arrangement.spacedBy(20.dp)) {
    Text("HOMEY  /  GOOGLE HOME",style=MaterialTheme.typography.labelLarge,
      color=MaterialTheme.colorScheme.primary)
    Text("Rom som hører sammen.",
      style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
    Text("Koble til Google Home for å sammenligne rom med Homey. Du velger selv hva som skal flyttes.",
      style=MaterialTheme.typography.bodyLarge)
    Card(shape=RoundedCornerShape(22.dp),
      colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
      Column(Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Kom i gang",style=MaterialTheme.typography.titleLarge,
          fontWeight=FontWeight.Bold)
        Text("Gi appen tilgang til Google-hjemmet du vil kontrollere. En lesetest endrer ingenting.",
          style=MaterialTheme.typography.bodyMedium)
        Button(modifier=Modifier.fillMaxWidth(),
          onClick={homeAppVM.homeApp.permissionsManager.requestPermissions()}) {
          Text("Gi tilgang til Google Home")
        }
      }
    }
    Card(shape=RoundedCornerShape(18.dp)) {
      Column(Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Bytt Google-konto",style=MaterialTheme.typography.titleMedium)
        Text("Bruk dette bare hvis det er feil Google-konto som er valgt.",
          style=MaterialTheme.typography.bodySmall)
        OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={
          homeAppVM.signInWithGoogleAccount(context)
        }) {Text("Velg Google-konto")}
      }
    }
    Text("TIDLIG ALFA  •  Automatisk synkronisering er avslått. "+
      "Romflyttinger krever en egen bekreftelse.",
      style=MaterialTheme.typography.labelSmall,
      color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
