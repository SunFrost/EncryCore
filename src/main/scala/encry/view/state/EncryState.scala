package encry.view.state

import java.io.File

import akka.actor.ActorRef
import encry.crypto.Address
import encry.modifiers.EncryPersistentModifier
import encry.modifiers.mempool.{EncryBaseTransaction, EncryPaymentTransaction}
import encry.modifiers.state.box._
import encry.modifiers.state.box.body.PaymentBoxBody
import encry.modifiers.state.box.proposition.AddressProposition
import encry.settings.{Algos, EncryAppSettings, NodeSettings}
import scorex.core.VersionTag
import scorex.core.transaction.state.MinimalState
import scorex.core.utils.ScorexLogging
import scorex.crypto.authds.ADDigest
import scorex.crypto.encode.Base16

import scala.util.Try

trait EncryState[IState <: MinimalState[EncryPersistentModifier, IState]]
  extends MinimalState[EncryPersistentModifier, IState] with ScorexLogging {

  self: IState =>

  def rootHash(): ADDigest

  // TODO: Implement correctly.
  def stateHeight(): Int = 0

  // Extracts `state changes` from the given sequence of transactions.
  def boxChanges(txs: Seq[EncryBaseTransaction]): EncryBoxStateChanges = {
    // Use neither `.filter` nor any validity checks here!
    // This method should be invoked when all txs are already validated.
    EncryBoxStateChanges(
      txs.flatMap { tx =>
        tx match {
          case tx: EncryPaymentTransaction =>
            tx.unlockers.map( unl => Removal(unl.closedBoxId)) ++
              tx.newBoxes.map( bx => Insertion(bx) )

//        case tx: AnotherTypeTransaction => ...
        }
      }
    )
  }

// TODO: Implement:  def boxesOf(proposition: Proposition): Seq[Box[proposition.type]]

  // ID of last applied modifier.
  override def version: VersionTag

  override def applyModifier(mod: EncryPersistentModifier): Try[IState]

  override def rollbackTo(version: VersionTag): Try[IState]

  def rollbackVersions: Iterable[VersionTag]

  override type NVCT = this.type

}

object EncryState extends ScorexLogging{

  // 33 bytes in Base58 encoding.
  val afterGenesisStateDigestHex: String = "f2343e160d4e42a83a87ea1a2f56b6fa2046ab8146c5e61727c297be578da0f510"
  val afterGenesisStateDigest: ADDigest = ADDigest @@ Base16.decode(afterGenesisStateDigestHex)

  lazy val genesisStateVersion: VersionTag = VersionTag @@ Algos.hash(afterGenesisStateDigest.tail)

  def stateDir(settings: EncryAppSettings) = new File(s"${settings.directory}/state")

  def generateGenesisUtxoState(stateDir: File, nodeViewHolderRef: Option[ActorRef]): (UtxoState, BoxHolder) = {
    log.info("Generating genesis UTXO state")
    lazy val genesisSeed = Long.MaxValue
    lazy val rndGen = new scala.util.Random(genesisSeed)
    lazy val initialBoxesNumber = 10000

    lazy val initialBoxes: Seq[EncryBaseBox] =
      (1 to initialBoxesNumber).map(_ => EncryPaymentBox(
        AddressProposition(Address @@ "f2343e160d4e42a83a87ea1a2f56b6fa2046ab8146c5e61727c297be578da0f510"),
        rndGen.nextLong(),
        PaymentBoxBody(10000L)))

    val bh = BoxHolder(initialBoxes)

    UtxoState.fromBoxHolder(bh, stateDir, nodeViewHolderRef).ensuring(us => {
      log.info("Genesis UTXO state generated")
      us.rootHash.sameElements(afterGenesisStateDigest) && us.version.sameElements(genesisStateVersion)
    }) -> bh
  }

  def generateGenesisDigestState(stateDir: File, settings: NodeSettings): DigestState = {
    DigestState.create(Some(genesisStateVersion), Some(afterGenesisStateDigest), stateDir, settings).get //todo: .get
  }

  def readOrGenerate(settings: EncryAppSettings, nodeViewHolderRef: Option[ActorRef]): Option[EncryState[_]] = {
    val dir = stateDir(settings)
    dir.mkdirs()

    if (dir.listFiles().isEmpty) {
      None
    } else {
      //todo: considering db state
      if (settings.nodeSettings.ADState) DigestState.create(None, None, dir, settings.nodeSettings).toOption
      else Some(UtxoState.create(dir/*, nodeViewHolderRef*/))
    }
  }
}