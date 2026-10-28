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
            val token = if (Config.token(this) != null) "token set" else "no token: every request is refused"
            val allow = Config.allowListText(this).ifEmpty { "any source" }
            address.text = getString(R.string.server_address, ip, PiPupService.SERVER_PORT) + "\n" + token + " · allow: " + allow
        }

        startForegroundService(Intent(this, PiPupService::class.java))
    }
}
