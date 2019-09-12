package encry.network

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import encry.modifiers.InstanceFactory
import encry.network.DownloadedModifiersValidator.ModifiersForValidating
import encry.network.NodeViewSynchronizer.ReceivableMessages.UpdatedHistory
import encry.network.PeerConnectionHandler.{ConnectedPeer, Outgoing}
import encry.settings.EncryAppSettings
import encry.view.NodeViewHolder.ReceivableMessages.ModifierFromRemote
import encry.view.history.History
import org.encryfoundation.common.modifiers.history._
import org.encryfoundation.common.modifiers.mempool.transaction.{Transaction, TransactionProtoSerializer}
import org.encryfoundation.common.network.BasicMessagesRepo.Handshake
import org.encryfoundation.common.utils.TaggedTypes.{Difficulty, ModifierId, ModifierTypeId}
import org.encryfoundation.common.utils.constants.TestNetConstants
import org.scalatest.{BeforeAndAfterAll, Matchers, OneInstancePerTest, WordSpecLike}

import scala.concurrent.duration._
class DownloadedModifiersValidatorSpeedTests extends WordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with InstanceFactory
  with OneInstancePerTest {

  implicit val system: ActorSystem = ActorSystem()

  val settingsWithAllPeers: EncryAppSettings = NetworkUtils.TestNetworkSettings.read("MainTestSettings.conf")

  override def afterAll(): Unit = system.terminate()

  "DownloadedModifiersValidatorTests" should {
    "speed" in {
      val nodeViewHolder = TestProbe()
      val peersKeeper = TestProbe()
      val deliveryManager = TestProbe()
      val nodeViewSync = TestProbe()
      val mempool = TestProbe()

      val address: InetSocketAddress = new InetSocketAddress("0.0.0.0", 9000)
      val peerHandler: TestProbe = TestProbe()
      val connectedPeer: ConnectedPeer = ConnectedPeer(address, peerHandler.ref, Outgoing,
        Handshake(protocolToBytes(settingsWithAllPeers.network.appVersion), "test node", Some(address), System.currentTimeMillis())
      )

      val downloadedModifiersValidator = TestActorRef[DownloadedModifiersValidator](DownloadedModifiersValidator.props(
        settingsWithAllPeers, nodeViewHolder.ref, peersKeeper.ref, nodeViewSync.ref, mempool.ref, None)
      )
      val history: History = generateDummyHistory(settingsWithAllPeers)

      val historyWith10Blocks = (1 to 1).foldLeft(history, Seq.empty[Block]) {
        case ((prevHistory, blocks), i) =>
          val block: Block = generateNextBlock(prevHistory, 0, None, 3000, 0)
          (prevHistory.append(block.header).right.get._1.append(block.payload).right.get._1.reportModifierIsValid(block),
            blocks :+ block)
      }

      nodeViewSync.send(downloadedModifiersValidator, UpdatedHistory(historyWith10Blocks._1))

      val headerMods: Map[ModifierId, Array[Byte]] = (historyWith10Blocks._2.map(b =>
        b.header.id -> HeaderProtoSerializer.toProto(b.header).toByteArray
      )).toMap

      val payloadMods: Map[ModifierId, Array[Byte]] = (historyWith10Blocks._2.map(b =>
        b.payload.id -> PayloadProtoSerializer.toProto(b.payload).toByteArray
      )).toMap

      println(s"txs: ${historyWith10Blocks._2.head.payload.txs.size}")

      val transactionsMods: Map[ModifierId, Array[Byte]] = (historyWith10Blocks._2.head.payload.txs.map(tx =>
        tx.id -> TransactionProtoSerializer.toProto(tx).toByteArray
      )).toMap

      deliveryManager.send(downloadedModifiersValidator, ModifiersForValidating(connectedPeer, Header.modifierTypeId, headerMods))
      deliveryManager.send(downloadedModifiersValidator, ModifiersForValidating(connectedPeer, Payload.modifierTypeId, payloadMods))
      deliveryManager.send(downloadedModifiersValidator, ModifiersForValidating(connectedPeer, Transaction.modifierTypeId, transactionsMods))

      val modifier1 = nodeViewHolder.expectMsgPF[Option[ModifierFromRemote]](100 millis) {
        case msg@ModifierFromRemote(modifier) if modifier.modifierTypeId == Header.modifierTypeId => Some(msg)
        case _ => None
      }
      val modifier2 = nodeViewHolder.expectMsgPF[Option[ModifierFromRemote]](1000 millis) {
        case msg@ModifierFromRemote(modifier) if modifier.modifierTypeId == Payload.modifierTypeId => Some(msg)
        case _ => None
      }

      assert(modifier1.nonEmpty)
      assert(modifier2.nonEmpty)
    }

    def generateNextBlock(history: History, difficultyDiff: BigInt = 0, prevId: Option[ModifierId] = None, txsQty: Int = 100,
                          additionalDifficulty: BigInt = 0): Block = {

      val previousHeaderId: ModifierId =
        prevId.getOrElse(history.getBestHeader.map(_.id).getOrElse(Header.GenesisParentId))
      val requiredDifficulty: Difficulty = history.getBestHeader.map(parent =>
        history.requiredDifficultyAfter(parent).getOrElse(Difficulty @@ BigInt(0)))
        .getOrElse(TestNetConstants.InitialDifficulty)
      val txs = (if (txsQty != 0) genValidPaymentTxs(txsQty) else Seq.empty) ++
        Seq(coinbaseTransaction)
      val header = genHeader.copy(
        parentId = previousHeaderId,
        height = history.getBestHeaderHeight + 1,
        difficulty = Difficulty @@ (requiredDifficulty + difficultyDiff + additionalDifficulty),
        transactionsRoot = Payload.rootHash(txs.map(_.id))
      )

      Block(header, Payload(header.id, txs))
    }

  }

}
