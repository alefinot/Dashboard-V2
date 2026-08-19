package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeDisplaySmall
import com.alefinot.dashboardpp.ui.theme.HudeSmall
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel

@Composable
fun ManualEntryScreen(vm: ConnectionViewModel) {
    var ip by remember { mutableStateOf(vm.lastKnownIp.value ?: "") }
    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
        ) {
            Text(
                "ENTER DEVICE IP",
                color = HudColors.Cyan,
                style = HudeDisplaySmall,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Phone and dashboard must share a local network: the same\n"
                    + "Wi-Fi, the phone's hotspot, or USB tethering.\n"
                    + "Check the IP in your network's device list,\n"
                    + "or scan again when it's powered and awake.",
                color = HudColors.TextDim,
                style = HudeSmall,
            )
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = ip,
                onValueChange = { ip = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, HudColors.CyanDim)
                    .background(HudColors.Panel)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(color = HudColors.Cyan),
            )
            if (vm.manualError.value != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    vm.manualError.value ?: "",
                    color = HudColors.Red,
                    style = HudeSmall,
                )
            }
            Spacer(Modifier.height(24.dp))
            HudButton("CONNECT") { vm.submitManualIp(ip) }
            Spacer(Modifier.height(8.dp))
            HudButton("BACK TO SCAN") { vm.rescan() }
        }
    }
}
