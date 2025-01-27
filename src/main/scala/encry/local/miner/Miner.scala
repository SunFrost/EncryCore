package encry.local.miner

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import encry.EncryApp
import encry.api.http.DataHolderForApi.{UpdatingMinerStatus, UpdatingTransactionsNumberForApi}
import encry.consensus.{CandidateBlock, EncrySupplyController, EquihashPowScheme}
import encry.local.miner.Miner._
import encry.local.miner.Worker.NextChallenge
import encry.modifiers.mempool.TransactionFactory
import encry.network.DeliveryManager.FullBlockChainIsSynced
import encry.network.NodeViewSynchronizer.ReceivableMessages.SemanticallySuccessfulModifier
import encry.settings.EncryAppSettings
import encry.stats.StatsSender._
import encry.utils.NetworkTime.Time
import encry.view.actors.NodeViewHolder.CurrentView
import encry.view.actors.NodeViewHolder.ReceivableMessages.{GetDataFromCurrentView, LocallyGeneratedBlock}
import encry.view.history.History
import encry.view.mempool.MemoryPool.TransactionsForMiner
import encry.view.state.UtxoState
import encry.view.wallet.EncryWallet
import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.encryfoundation.common.crypto.PrivateKey25519
import org.encryfoundation.common.modifiers.history.{Block, Header}
import org.encryfoundation.common.modifiers.mempool.transaction.Transaction
import org.encryfoundation.common.modifiers.state.box.Box.Amount
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.{Difficulty, Height, ModifierId}
import encry.EncryApp.{ec, nodeViewHolder, timeProvider}

import scala.collection._
import scala.concurrent.duration._

class Miner(dataHolder: ActorRef, influx: Option[ActorRef], settings: EncryAppSettings) extends Actor with StrictLogging {

  implicit val timeout: Timeout = Timeout(5.seconds)

  type TransactionIdAsKey = scala.collection.mutable.WrappedArray.ofByte

  def toKey(id: ModifierId): TransactionIdAsKey = new mutable.WrappedArray.ofByte(id)

  val dateFormat: SimpleDateFormat = new SimpleDateFormat("HH:mm:ss")
  var startTime: Long = System.currentTimeMillis()
  var sleepTime: Long = System.currentTimeMillis()
  var candidateOpt: Option[CandidateBlock] = None
  var syncingDone: Boolean = settings.node.offlineGeneration
  val numberOfWorkers: Int = settings.node.numberOfMiningWorkers
  val powScheme: EquihashPowScheme = EquihashPowScheme(settings.constants.n, settings.constants.k,
    settings.constants.Version, settings.constants.PreGenesisHeight, settings.constants.MaxTarget)
  var transactionsPool: IndexedSeq[Transaction] = IndexedSeq.empty[Transaction]

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[SemanticallySuccessfulModifier])
    context.system.eventStream.subscribe(self, classOf[FullBlockChainIsSynced])
    context.system.scheduler.schedule(5.seconds, 5.seconds)(
      influx.foreach(_ ! InfoAboutTransactionsFromMiner(transactionsPool.size))
    )
    context.system.scheduler.schedule(5.seconds, 5.seconds) {
      dataHolder ! UpdatingTransactionsNumberForApi(transactionsPool.length)
      dataHolder ! UpdatingMinerStatus(MinerStatus(context.children.nonEmpty && candidateOpt.nonEmpty, candidateOpt))
    }
  }

  override def postStop(): Unit = killAllWorkers()

  def killAllWorkers(): Unit = context.children.foreach(context.stop)

  def needNewCandidate(b: Block): Boolean =
    !candidateOpt.flatMap(_.parentOpt).map(_.id).exists(id => Algos.encode(id) == Algos.encode(b.header.id))

  override def receive: Receive = if (settings.node.mining && syncingDone) miningEnabled else miningDisabled

  def mining: Receive = {
    case StartMining if context.children.nonEmpty & syncingDone =>
      killAllWorkers()
      self ! StartMining
    case StartMining if syncingDone =>
      for (i <- 0 until numberOfWorkers) yield context.actorOf(
        Props(classOf[Worker], i, numberOfWorkers, settings.constants.PreGenesisHeight).withDispatcher("mining-dispatcher").withMailbox("mining-mailbox"))
      candidateOpt match {
        case Some(candidateBlock) =>
          logger.info(s"Starting mining at ${dateFormat.format(new Date(System.currentTimeMillis()))}")
          context.children.foreach(_ ! NextChallenge(candidateBlock))
        case None =>
          logger.info("Candidate is empty! Producing new candidate!")
          produceCandidate()
      }
    case TransactionsForMiner(txs) => transactionsPool = transactionsPool ++ txs
    case StartMining => logger.info("Can't start mining because of chain is not synced!")
    case DisableMining if context.children.nonEmpty =>
      killAllWorkers()
      candidateOpt = None
      context.become(miningDisabled)
    case MinedBlock(block, workerIdx) if candidateOpt.exists(_.timestamp == block.header.timestamp) =>
      logger.info(s"Going to propagate new block (${block.header.height}, ${block.header.encodedId}, ${block.payload.txs.size}" +
        s" from worker $workerIdx with nonce: ${block.header.nonce}.")
      logger.debug(s"Set previousSelfMinedBlockId: ${Algos.encode(block.id)}")
      killAllWorkers()
      nodeViewHolder ! LocallyGeneratedBlock(block)
      if (settings.influxDB.isDefined) {
        context.actorSelection("/user/statsSender") ! MiningEnd(block.header, workerIdx, context.children.size)
        context.actorSelection("/user/statsSender") ! MiningTime(System.currentTimeMillis() - startTime)
      }
      candidateOpt = None
      sleepTime = System.currentTimeMillis()
  }

  def miningEnabled: Receive =
    receiveSemanticallySuccessfulModifier
      .orElse(receiverCandidateBlock)
      .orElse(mining)
      .orElse(chainEvents)
      .orElse(unknownMessage)

  def miningDisabled: Receive = {
    case EnableMining =>
      context.become(miningEnabled)
      self ! StartMining
    case FullBlockChainIsSynced() =>
      syncingDone = true
      if (settings.node.mining) self ! EnableMining
    case TransactionsForMiner(_) =>
    case DisableMining | SemanticallySuccessfulModifier(_) =>
  }

  def receiveSemanticallySuccessfulModifier: Receive = {
    case SemanticallySuccessfulModifier(mod: Block) if needNewCandidate(mod) =>
      logger.info(s"Got new block. Starting to produce candidate at height: ${mod.header.height + 1} " +
        s"at ${dateFormat.format(new Date(System.currentTimeMillis()))}")
      produceCandidate()
    case SemanticallySuccessfulModifier(_) =>
  }

  def receiverCandidateBlock: Receive = {
    case c: CandidateBlock => procCandidateBlock(c)
    case cEnv: CandidateEnvelope if cEnv.c.nonEmpty => procCandidateBlock(cEnv.c.get)
    case _: CandidateEnvelope =>
      logger.debug("Received empty CandidateEnvelope, going to suspend mining for a while")
      self ! DisableMining
  }

  def unknownMessage: Receive = {
    case m => logger.debug(s"Unexpected message $m")
  }

  def chainEvents: Receive = {
    case FullBlockChainIsSynced() => syncingDone = true
  }

  def procCandidateBlock(c: CandidateBlock): Unit = {
    logger.info(s"Got candidate block $c in ${dateFormat.format(new Date(System.currentTimeMillis()))}")
    candidateOpt = Some(c)
    self ! StartMining
  }

  def createCandidate(view: CurrentView[History, UtxoState, EncryWallet],
                      bestHeaderOpt: Option[Header]): CandidateBlock = {
    val txsU: IndexedSeq[Transaction] = transactionsPool.filter(x => view.state.validate(x).isRight).distinct
    val filteredTxsWithoutDuplicateInputs = txsU.foldLeft(List.empty[String], IndexedSeq.empty[Transaction]) {
      case ((usedInputsIds, acc), tx) =>
        if (tx.inputs.forall(input => !usedInputsIds.contains(Algos.encode(input.boxId)))) {
          (usedInputsIds ++ tx.inputs.map(input => Algos.encode(input.boxId))) -> (acc :+ tx)
        } else usedInputsIds -> acc
    }._2
    val timestamp: Time = timeProvider.estimatedTime
    val height: Height = Height @@ (bestHeaderOpt.map(_.height).getOrElse(settings.constants.PreGenesisHeight) + 1)
    val feesTotal: Amount = filteredTxsWithoutDuplicateInputs.map(_.fee).sum
    val supplyTotal: Amount = EncrySupplyController.supplyAt(view.state.height, settings.constants.InitialEmissionAmount,
      settings.constants.EmissionEpochLength, settings.constants.EmissionDecay)
    val minerSecret: PrivateKey25519 = view.vault.accountManager.mandatoryAccount
    val coinbase: Transaction = TransactionFactory
      .coinbaseTransactionScratch(minerSecret.publicImage, timestamp, supplyTotal, feesTotal, view.state.height)

    val txs: Seq[Transaction] = filteredTxsWithoutDuplicateInputs.sortBy(_.timestamp) :+ coinbase

    val difficulty: Difficulty = bestHeaderOpt.map(parent => view.history.requiredDifficultyAfter(parent) match {
      case Right(value) => value
      case Left(value)  => EncryApp.forceStopApplication(999, value.toString)
    })
      .getOrElse(settings.constants.InitialDifficulty)

    val candidate: CandidateBlock =
      CandidateBlock(bestHeaderOpt, settings.constants.Version, txs, timestamp, difficulty)

    logger.info(s"Sending candidate block with ${candidate.transactions.length - 1} transactions " +
      s"and 1 coinbase for height $height.")

    transactionsPool = IndexedSeq.empty[Transaction]
    candidate
  }

  def produceCandidate(): Unit =
    nodeViewHolder ! GetDataFromCurrentView[History, UtxoState, EncryWallet, CandidateEnvelope] {
      nodeView =>
        val producingStartTime: Time = System.currentTimeMillis()
        startTime = producingStartTime
        val bestHeaderOpt: Option[Header] = nodeView.history.getBestBlock.map(_.header)
        bestHeaderOpt match {
          case Some(h) => logger.info(s"Best header at height ${h.height}")
          case None => logger.info(s"No best header opt")
        }
        val candidate: CandidateEnvelope =
          if ((bestHeaderOpt.isDefined &&
            (syncingDone || nodeView.history.isFullChainSynced)) || settings.node.offlineGeneration) {
            logger.info(s"Starting candidate generation at " +
              s"${dateFormat.format(new Date(System.currentTimeMillis()))}")
            if (settings.influxDB.isDefined)
              context.actorSelection("user/statsSender") ! SleepTime(System.currentTimeMillis() - sleepTime)
            logger.info("Going to calculate last block:")
            val envelope: CandidateEnvelope =
              CandidateEnvelope
                .fromCandidate(createCandidate(nodeView, bestHeaderOpt))
            envelope
          } else CandidateEnvelope.empty
        candidate
    }
}

object Miner {

  case object DisableMining

  case object EnableMining

  case object StartMining

  case class MinedBlock(block: Block, workerIdx: Int)

  case class CandidateEnvelope(c: Option[CandidateBlock])

  object CandidateEnvelope {

    val empty: CandidateEnvelope = CandidateEnvelope(None)

    def fromCandidate(c: CandidateBlock): CandidateEnvelope = CandidateEnvelope(Some(c))
  }

  case class MinerStatus(isMining: Boolean, candidateBlock: Option[CandidateBlock]) {
    lazy val json: Json = Map(
      "isMining"       -> isMining.asJson,
      "candidateBlock" -> candidateBlock.map(_.asJson).getOrElse("None".asJson)
    ).asJson
  }

  implicit val jsonEncoder: Encoder[MinerStatus] = (r: MinerStatus) => Map(
    "isMining"       -> r.isMining.asJson,
    "candidateBlock" -> r.candidateBlock.map(_.asJson).getOrElse("None".asJson)
  ).asJson

  def props(dataHolder: ActorRef, influx: Option[ActorRef], settings: EncryAppSettings): Props = Props(new Miner(dataHolder, influx, settings))
}