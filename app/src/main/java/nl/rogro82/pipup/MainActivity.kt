/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package nl.rogro82.pipup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import nl.rogro82.pipup.Utils.getIpAddress

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startForegroundService(Intent(this, PiPupService::class.java))
    }

    // On resume, not on create: a token set over ADB while this screen is open shows when you come back to it.
    override fun onResume() {
        super.onResume()
        val status = findViewById<TextView>(R.id.textViewConnection)
        val address = findViewById<TextView>(R.id.textViewServerAddress)

        val ip = getIpAddress()
        if (ip == null) {
            status.setText(R.string.no_network_connection)
            address.visibility = View.INVISIBLE
        } else {
            status.setText(R.string.server_running)
            address.visibility = View.VISIBLE
            // Whether it is configured, never the token itself.
            val token = getString(if (Config.token(this) != null) R.string.token_set else R.string.token_missing)
            val allow = Config.allowListText(this).ifEmpty { getString(R.string.allow_any) }
            address.text = getString(R.string.server_status, ip, PiPupService.SERVER_PORT, token, allow)
        }
    }
}
