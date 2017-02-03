package net.corda.attachmentdemo

import com.google.common.net.HostAndPort
import com.google.common.util.concurrent.ListenableFuture
import joptsimple.OptionParser
import net.corda.core.*
import net.corda.core.contracts.TransactionType
import net.corda.core.crypto.Party
import net.corda.core.crypto.SecureHash
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.Emoji
import net.corda.flows.FinalityFlow
import net.corda.node.services.config.SSLConfiguration
import net.corda.node.services.messaging.CordaRPCClient
import net.corda.testing.ALICE_KEY
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlin.test.assertEquals

internal enum class Role {
    SENDER,
    RECIPIENT
}

fun main(args: Array<String>) {
    val parser = OptionParser()

    val roleArg = parser.accepts("role").withRequiredArg().ofType(Role::class.java).required()
    val certsPath = parser.accepts("certificates").withRequiredArg()
    val options = try {
        parser.parse(*args)
    } catch (e: Exception) {
        println(e.message)
        printHelp(parser)
        exitProcess(1)
    }

    val role = options.valueOf(roleArg)!!
    when (role) {
        Role.SENDER -> {
            val host = HostAndPort.fromString("localhost:10004")
            println("Connecting to sender node ($host)")
            CordaRPCClient(host, sslConfigFor("nodea", options.valueOf(certsPath))).use("demo", "demo") {
                sender(this).getOrThrow()
            }
        }
        Role.RECIPIENT -> {
            val host = HostAndPort.fromString("localhost:10006")
            println("Connecting to the recipient node ($host)")
            CordaRPCClient(host, sslConfigFor("nodeb", options.valueOf(certsPath))).use("demo", "demo") {
                try {
                    val wtx = recipient(this).getOrThrow()
                    println("File received - we're happy!\n\nFinal transaction is:\n\n${Emoji.renderIfSupported(wtx)}")
                } catch (e: Exception) {
                    println("Error: ${e.message}")
                }
            }
        }
    }
}

val PROSPECTUS_HASH = SecureHash.parse("decd098666b9657314870e192ced0c3519c2c9d395507a238338f8d003929de9")

fun sender(rpc: CordaRPCOps): ListenableFuture<Unit> {
    // Get the identity key of the other side (the recipient).
    val otherSide: Party = rpc.partyFromName("Bank B")!!

    // Make sure we have the file in storage
    // TODO: We should have our own demo file, not share the trader demo file
    if (!rpc.attachmentExists(PROSPECTUS_HASH)) {
        Thread.currentThread().contextClassLoader.getResourceAsStream("bank-of-london-cp.jar").use {
            val id = rpc.uploadAttachment(it)
            assertEquals(PROSPECTUS_HASH, id)
        }
    }

    // Create a trivial transaction that just passes across the attachment - in normal cases there would be
    // inputs, outputs and commands that refer to this attachment.
    val ptx = TransactionType.General.Builder(notary = null)
    require(rpc.attachmentExists(PROSPECTUS_HASH))
    ptx.addAttachment(PROSPECTUS_HASH)
    // TODO: Add a dummy state and specify a notary, so that the tx hash is randomised each time and the demo can be repeated.

    // Despite not having any states, we have to have at least one signature on the transaction
    ptx.signWith(ALICE_KEY)

    // Send the transaction to the other recipient
    val stx = ptx.toSignedTransaction()
    println("Sending ${stx.id}")
    val flowHandle = rpc.startFlow(::FinalityFlow, stx, setOf(otherSide))
    flowHandle.progress.subscribe(::println)
    return flowHandle.returnValue
}

fun recipient(rpc: CordaRPCOps): ListenableFuture<Unit> {
    println("Waiting to receive transaction ...")
    return rpc.verifiedTransactions().second.toFuture()
            .flatMap { stx ->
                future {
                    require(rpc.attachmentExists(PROSPECTUS_HASH)) { "Attachment does not exist" }
                    stx
                }
            }.map { stx ->
                val wtx = stx.tx
                require(wtx.attachments.isNotEmpty()) { "No attachments found in ${wtx.id}" }
                require(wtx.attachments.first() == PROSPECTUS_HASH) { "Attachment does not match: ${wtx.attachments.first()}" }
            }
}

private fun printHelp(parser: OptionParser) {
    println("""
    Usage: attachment-demo --role [RECIPIENT|SENDER] [options]
    Please refer to the documentation in docs/build/index.html for more info.

    """.trimIndent())
    parser.printHelpOn(System.out)
}


// TODO: Take this out once we have a dedicated RPC port and allow SSL on it to be optional.
private fun sslConfigFor(nodename: String, certsPath: String?): SSLConfiguration {
    return object : SSLConfiguration {
        override val keyStorePassword: String = "cordacadevpass"
        override val trustStorePassword: String = "trustpass"
        override val certificatesDirectory: Path = if (certsPath != null) Paths.get(certsPath) else Paths.get("build") / "nodes" / nodename / "certificates"
    }
}
