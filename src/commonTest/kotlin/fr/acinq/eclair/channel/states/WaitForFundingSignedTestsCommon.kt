package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.eclair.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.eclair.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.wire.AcceptChannel
import fr.acinq.eclair.wire.Error
import fr.acinq.eclair.wire.FundingCreated
import fr.acinq.eclair.wire.FundingSigned
import kotlin.test.*

class WaitForFundingSignedTestsCommon : EclairTestSuite() {

    @Test
    fun `recv FundingSigned with valid signature`() {
        val (alice, _, fundingSigned) = init()
        val (alice1, actions1) = alice.process(ChannelEvent.MessageReceived(fundingSigned))
        assertTrue(alice1 is WaitForFundingConfirmed)
        assertEquals(4, actions1.size)
        actions1.has<ChannelAction.Storage.StoreState>()
        val fundingTx = actions1.findTxs().first()
        val watchConfirmed = actions1.findWatch<WatchConfirmed>()
        assertEquals(WatchConfirmed(alice1.channelId, fundingTx, 3, BITCOIN_FUNDING_DEPTHOK), watchConfirmed)
        val watchSpent = actions1.findWatch<WatchSpent>()
        assertEquals(WatchSpent(alice1.channelId, fundingTx, 0, BITCOIN_FUNDING_SPENT), watchSpent)
    }

    @Test
    fun `recv FundingSigned with invalid signature`() {
        val (alice, _, fundingSigned) = init()
        val (alice1, actions1) = alice.process(ChannelEvent.MessageReceived(fundingSigned.copy(signature = ByteVector64.Zeroes)))
        assertTrue(alice1 is Aborted)
        actions1.hasOutgoingMessage<Error>()
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null)))
        assertTrue(alice1 is Aborted)
        assertNull(actions1.findOutgoingMessageOpt<Error>())
    }

    @Test
    fun `recv CMD_FORCECLOSE`() {
        val (alice, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelEvent.ExecuteCommand(CMD_FORCECLOSE))
        assertTrue(alice1 is Aborted)
        assertNull(actions1.findOutgoingMessageOpt<Error>())
    }

    @Ignore
    fun `recv Disconnected`() {
        val (alice, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelEvent.Disconnected)
        assertTrue(alice1 is Aborted)
        assertTrue(actions1.isNotEmpty()) // funding utxos should have been freed
    }

    companion object {
        fun init(
            channelVersion: ChannelVersion = ChannelVersion.STANDARD,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            fundingAmount: Satoshi = TestConstants.fundingAmount
        ): Triple<WaitForFundingSigned, WaitForFundingConfirmed, FundingSigned> {
            val (alice, bob, open) = TestsHelper.init(channelVersion, currentHeight, fundingAmount)
            val (bob1, actionsBob1) = bob.process(ChannelEvent.MessageReceived(open))
            val accept = actionsBob1.findOutgoingMessage<AcceptChannel>()
            val (alice1, actionsAlice1) = alice.process(ChannelEvent.MessageReceived(accept))
            val fundingRequest = actionsAlice1.filterIsInstance<ChannelAction.Blockchain.MakeFundingTx>().first()
            val fundingTx = Transaction(version = 2, txIn = listOf(), txOut = listOf(TxOut(fundingRequest.amount, fundingRequest.pubkeyScript)), lockTime = 0)
            val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MakeFundingTxResponse(fundingTx, 0, 100.sat))
            assertTrue(alice2 is WaitForFundingSigned)
            val fundingCreated = actionsAlice2.findOutgoingMessage<FundingCreated>()
            val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(fundingCreated))
            val fundingSigned = actionsBob2.findOutgoingMessage<FundingSigned>()
            assertTrue(bob2 is WaitForFundingConfirmed)
            return Triple(alice2, bob2, fundingSigned)
        }
    }

}