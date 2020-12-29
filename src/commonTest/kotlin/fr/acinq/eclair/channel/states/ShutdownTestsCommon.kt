package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.addHtlc
import fr.acinq.eclair.channel.TestsHelper.signAndRevack
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShutdownTestsCommon : EclairTestSuite() {
    @Test
    fun `recv CMD_ADD_HTLC`() {
        val (_, bob) = init()
        val add = CMD_ADD_HTLC(500000000.msat, r1, cltvExpiry = CltvExpiry(300000), TestConstants.emptyOnionPacket, UUID.randomUUID())
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(add))
        assertTrue { bob1 is ShuttingDown }
        assertTrue { actions1.any { it is ChannelAction.ProcessCmdRes.AddFailed && it.error == ChannelUnavailable(bob.channelId) } }
    }

    @Test
    fun `recv CMD_FULFILL_HTLC`() {
        val (_, bob) = init()
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(0, r1)))
        val fulfill = actions1.findOutgoingMessage<UpdateFulfillHtlc>()
        assertTrue { bob1 is ShuttingDown && bob1.commitments.localChanges.proposed.contains(fulfill) }
    }

    @Test
    fun `recv UpdateFulfillHtlc`() {
        val (alice, bob) = init()
        val (_, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(0, r1)))
        val fulfill = actions1.findOutgoingMessage<UpdateFulfillHtlc>()
        val (alice1, _) = alice.process(ChannelEvent.MessageReceived(fulfill))
        assertTrue { alice1 is ShuttingDown && alice1.commitments.remoteChanges.proposed.contains(fulfill) }
    }

    @Test
    fun `recv CMD_FAIL_HTLC`() {
        val (_, bob) = init()
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(0, CMD_FAIL_HTLC.Reason.Failure(PermanentChannelFailure))))
        val fail = actions1.findOutgoingMessage<UpdateFailHtlc>()
        assertTrue { bob1 is ShuttingDown && bob1.commitments.localChanges.proposed.contains(fail) }
    }

    @Test
    fun `recv CMD_FAIL_HTLC (unknown htlc id)`() {
        val (_, bob) = init()
        val cmdFail = CMD_FAIL_HTLC(42, CMD_FAIL_HTLC.Reason.Failure(PermanentChannelFailure))
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(cmdFail))
        assertEquals(actions1, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmdFail, UnknownHtlcId(bob.channelId, 42))))
        assertEquals(bob, bob1)
    }

    @Test
    fun `recv CMD_FAIL_MALFORMED_HTLC`() {
        val (_, bob) = init()
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_FAIL_MALFORMED_HTLC(1, ByteVector32(Crypto.sha256(ByteVector.empty)), FailureMessage.BADONION)))
        val fail = actions1.findOutgoingMessage<UpdateFailMalformedHtlc>()
        assertTrue { bob1 is ShuttingDown && bob1.commitments.localChanges.proposed.contains(fail) }
    }

    @Test
    fun `recv CMD_FAIL_MALFORMED_HTLC (unknown htlc id)`() {
        val (_, bob) = init()
        val cmdFail = CMD_FAIL_MALFORMED_HTLC(42, ByteVector32(Crypto.sha256(ByteVector.empty)), FailureMessage.BADONION)
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(cmdFail))
        assertEquals(actions1, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmdFail, UnknownHtlcId(bob.channelId, 42))))
        assertEquals(bob, bob1)
    }

    @Test
    fun `recv CMD_FAIL_MALFORMED_HTLC (invalid failure_code)`() {
        val (_, bob) = init()
        val cmdFail = CMD_FAIL_MALFORMED_HTLC(42, randomBytes32(), 42)
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(cmdFail))
        assertEquals(actions1, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmdFail, InvalidFailureCode(bob.channelId))))
        assertEquals(bob, bob1)
    }

    @Test
    fun `recv UpdateFailHtlc`() {
        val (alice, bob) = init()
        val (_, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(0, CMD_FAIL_HTLC.Reason.Failure(PermanentChannelFailure))))
        val fail = actions1.findOutgoingMessage<UpdateFailHtlc>()
        val (alice1, _) = alice.process(ChannelEvent.MessageReceived(fail))
        assertTrue { alice1 is ShuttingDown && alice1.commitments.remoteChanges.proposed.contains(fail) }
    }

    @Test
    fun `recv UpdateFailMalformedHtlc`() {
        val (alice, bob) = init()
        val (_, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_FAIL_MALFORMED_HTLC(1, ByteVector32(Crypto.sha256(ByteVector.empty)), FailureMessage.BADONION)))
        val fail = actions1.findOutgoingMessage<UpdateFailMalformedHtlc>()
        val (alice1, _) = alice.process(ChannelEvent.MessageReceived(fail))
        assertTrue { alice1 is ShuttingDown && alice1.commitments.remoteChanges.proposed.contains(fail) }
    }

    @Test
    fun `recv CMD_SIGN`() {
        val (alice, bob) = init()
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(0, r1)))
        val fulfill = actions1.findOutgoingMessage<UpdateFulfillHtlc>()
        val (alice1, _) = alice.process(ChannelEvent.MessageReceived(fulfill))
        val (_, alice2) = signAndRevack(bob1, alice1)
        val (alice3, _) = alice2.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        assertTrue { alice3 is ShuttingDown && alice3.commitments.remoteNextCommitInfo.isLeft }
    }

    @Test
    fun `recv CMD_SIGN (no changes)`() {
        val (_, bob) = init()
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        assertEquals(bob, bob1)
        assertTrue { actions1.isEmpty() }
    }

    @Test
    fun `recv NewBlock (no htlc timed out)`() {
        val (alice, _) = init()

        run {
            val (alice1, actions1) = alice.process(ChannelEvent.NewBlock(alice.currentBlockHeight + 1, alice.currentTip.second))
            assertEquals(alice.copy(currentTip = alice1.currentTip), alice1)
            assertTrue(actions1.isEmpty())
        }

        run {
            val (alice1, actions1) = alice.process(ChannelEvent.CheckHtlcTimeout)
            assertEquals(alice, alice1)
            assertTrue(actions1.isEmpty())
        }
    }

    @Test
    fun `recv NewBlock (an htlc timed out)`() {
        val (alice, _) = init()
        val commitTx = alice.commitments.localCommit.publishableTxs.commitTx.tx
        val htlcExpiry = alice.commitments.localCommit.spec.htlcs.map { it.add.cltvExpiry }.first()
        val (alice1, actions1) = alice.process(ChannelEvent.NewBlock(htlcExpiry.toLong().toInt(), alice.currentTip.second))
        assertTrue(alice1 is Closing)
        assertNotNull(alice1.localCommitPublished)
        actions1.hasTx(commitTx)
        actions1.hasOutgoingMessage<Error>()
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (their commit)`() {
        val (alice, bob) = init()
        // bob publishes his current commit tx, which contains two pending htlcs alice->bob
        val bobCommitTx = bob.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(6, bobCommitTx.txOut.size) // 2 main outputs + 2 anchors + 2 pending htlcs
        val (_, remoteCommitPublished) = TestsHelper.remoteClose(bobCommitTx, alice)
        assertNotNull(remoteCommitPublished.claimMainOutputTx)
        assertTrue(remoteCommitPublished.claimHtlcSuccessTxs.isEmpty())
        assertEquals(2, remoteCommitPublished.claimHtlcTimeoutTxs.size)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (their next commit)`() {
        val (alice, bob) = run {
            val (alice0, bob0) = TestsHelper.reachNormal(ChannelVersion.STANDARD)
            val (nodes1, _, _) = addHtlc(25_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = TestsHelper.crossSign(alice1, bob1)
            val (nodes3, _, _) = addHtlc(35_000_000.msat, alice2, bob2)
            val (alice3, bob3) = nodes3
            // alice signs the next commitment, but bob doesn't
            val (alice4, actionsAlice) = alice3.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
            val commitSig = actionsAlice.hasOutgoingMessage<CommitSig>()
            val (bob4, actionsBob) = bob3.process(ChannelEvent.MessageReceived(commitSig))
            actionsBob.hasOutgoingMessage<RevokeAndAck>() // not forwarded to Alice (malicious Bob)
            shutdown(alice4, bob4)
        }

        val bobCommitTx = bob.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(6, bobCommitTx.txOut.size) // 2 main outputs + 2 anchors + 2 pending htlc
        val (alice1, aliceActions1) = alice.process(ChannelEvent.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx)))
        assertTrue(alice1 is Closing)
        assertNotNull(alice1.nextRemoteCommitPublished)
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        val rcp = alice1.nextRemoteCommitPublished!!
        assertNotNull(rcp.claimMainOutputTx)
        assertTrue(rcp.claimHtlcSuccessTxs.isEmpty())
        assertEquals(2, rcp.claimHtlcTimeoutTxs.size)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (revoked tx)`() {
        val (alice, _, revokedTx) = run {
            val (alice0, bob0) = TestsHelper.reachNormal(ChannelVersion.STANDARD)
            val (nodes1, _, _) = addHtlc(25_000_000.msat, alice0, bob0)
            val (alice1, bob1) = nodes1
            val (alice2, bob2) = TestsHelper.crossSign(alice1, bob1)
            val (nodes3, _, _) = addHtlc(35_000_000.msat, alice2, bob2)
            val (alice3, bob3) = nodes3
            val (alice4, bob4) = TestsHelper.crossSign(alice3, bob3)
            val (alice5, bob5) = shutdown(alice4, bob4)
            Triple(alice5, bob5, (bob2 as Normal).commitments.localCommit.publishableTxs.commitTx.tx)
        }

        assertEquals(5, revokedTx.txOut.size) // 2 main outputs + 2 anchors + 1 pending htlc
        val (alice1, aliceActions1) = alice.process(ChannelEvent.WatchReceived(WatchEventSpent(alice.channelId, BITCOIN_FUNDING_SPENT, revokedTx)))
        assertTrue(alice1 is Closing)
        assertEquals(1, alice1.revokedCommitPublished.size)
        aliceActions1.hasOutgoingMessage<Error>()
        aliceActions1.has<ChannelAction.Storage.StoreState>()
        aliceActions1.has<ChannelAction.Storage.GetHtlcInfos>()
        val rvk = alice1.revokedCommitPublished.first()
        assertNotNull(rvk.claimMainOutputTx)
        assertNotNull(rvk.mainPenaltyTx)
    }

    @Test
    fun `recv Disconnected`() {
        val (alice, _) = init()
        val (alice1, _) = alice.process(ChannelEvent.Disconnected)
        assertTrue { alice1 is Offline }
    }

    companion object {
        val r1 = randomBytes32()
        val r2 = randomBytes32()

        fun init(currentBlockHeight: Int = TestConstants.defaultBlockHeight): Pair<ShuttingDown, ShuttingDown> {
            val (alice, bob) = TestsHelper.reachNormal(ChannelVersion.STANDARD)
            val (_, cmdAdd1) = TestsHelper.makeCmdAdd(300_000_000.msat, bob.staticParams.nodeParams.nodeId, currentBlockHeight.toLong(), r1)
            val (alice1, actions) = alice.process(ChannelEvent.ExecuteCommand(cmdAdd1))
            val htlc1 = actions.findOutgoingMessage<UpdateAddHtlc>()
            val (bob1, _) = bob.process(ChannelEvent.MessageReceived(htlc1))

            val (_, cmdAdd2) = TestsHelper.makeCmdAdd(200_000_000.msat, bob.staticParams.nodeParams.nodeId, currentBlockHeight.toLong(), r2)
            val (alice2, actions3) = alice1.process(ChannelEvent.ExecuteCommand(cmdAdd2))
            val htlc2 = actions3.findOutgoingMessage<UpdateAddHtlc>()
            val (bob2, _) = bob1.process(ChannelEvent.MessageReceived(htlc2))

            // Alice signs
            val (alice3, bob3) = signAndRevack(alice2, bob2)
            // Bob signs back
            val (bob4, alice4) = signAndRevack(bob3, alice3)
            // Alice initiates a closing
            return shutdown(alice4, bob4)
        }

        fun shutdown(alice: ChannelState, bob: ChannelState): Pair<ShuttingDown, ShuttingDown> {
            // Alice initiates a closing
            val (alice1, actionsAlice) = alice.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null)))
            val shutdown = actionsAlice.findOutgoingMessage<Shutdown>()
            val (bob1, actionsBob) = bob.process(ChannelEvent.MessageReceived(shutdown))
            val shutdown1 = actionsBob.findOutgoingMessage<Shutdown>()
            val (alice2, _) = alice1.process(ChannelEvent.MessageReceived(shutdown1))
            assertTrue(alice2 is ShuttingDown)
            assertTrue(bob1 is ShuttingDown)
            return Pair(alice2, bob1)
        }
    }
}
