package com.example.sshterminal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ConfigScreen(modifier: Modifier = Modifier) {
    val host = remember { mutableStateOf("") }
    val port = remember { mutableStateOf("22") }
    val username = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    val privateKeyName = remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = host.value,
                onValueChange = { host.value = it },
                label = { Text("Host") },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = port.value,
                onValueChange = { port.value = it },
                label = { Text("Port") },
                modifier = Modifier.weight(0.35f),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = username.value,
            onValueChange = { username.value = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password.value,
            onValueChange = { password.value = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = privateKeyName.value,
                onValueChange = { privateKeyName.value = it },
                label = { Text("Private key") },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                singleLine = true,
            )
            Button(onClick = { privateKeyName.value = "selected.pem" }) {
                Text("Import")
            }
        }
    }
}
