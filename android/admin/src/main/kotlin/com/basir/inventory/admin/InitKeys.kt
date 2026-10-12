package com.basir.inventory.admin

import com.basir.inventory.shared.ProviderKeys
import java.io.File

/** Creates provider keys once and exports their public halves for the Android build. */
fun main(args: Array<String>) {
    val keys = AdminStore.loadKeys() ?: ProviderKeys.generate().also {
        AdminStore.saveKeys(it)
        println("Created new provider keys in ${AdminStore.keysFile}")
    }
    val out = File(args.firstOrNull() ?: "public.properties")
    out.parentFile?.mkdirs()
    out.writeText(AdminStore.publicProperties(keys))
    println("Wrote public keys to $out")
    println("IMPORTANT: copy ${AdminStore.keysFile} to a safe place (USB/Google Drive). Losing it means")
    println("every installed app must be rebuilt with new keys.")
}
