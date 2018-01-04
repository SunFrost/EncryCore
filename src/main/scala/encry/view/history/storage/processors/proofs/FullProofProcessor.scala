package encry.view.history.storage.processors.proofs

import java.awt.HeadlessException

import encry.modifiers.EncryPersistentModifier
import encry.modifiers.history.{ADProofs, HistoryModifierSerializer}
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.history.block.header.EncryBlockHeader
import encry.modifiers.history.block.payload.EncryBlockPayload
import encry.view.history.storage.processors.BlockProcessor
import io.iohk.iodb.ByteArrayWrapper
import scorex.core.consensus.History.ProgressInfo
import scorex.crypto.encode.Base58

import scala.util.Try

trait FullProofProcessor extends BaseADProofProcessor with BlockProcessor {

  protected val adState: Boolean

  override protected def process(m: ADProofs): ProgressInfo[EncryPersistentModifier] = {
    historyStorage.modifierById(m.headerId) match {
      case Some(header: EncryBlockHeader) =>
        historyStorage.modifierById(header.payloadId) match {
          case Some(txs: EncryBlockPayload) if adState =>
            processFullBlock(new EncryBlock(header, txs, Some(m)), txsAreNew = false)
          case _ =>
            val modifierRow = Seq((ByteArrayWrapper(m.id), ByteArrayWrapper(HistoryModifierSerializer.toBytes(m))))
            historyStorage.insert(m.id, modifierRow)
            ProgressInfo(None, Seq(), None, Seq())
        }
      case _ =>
        throw new Error(s"Header for modifier $m is no defined")
    }
  }

  // TODO: Replace usage of `require()`.
  override protected def validate(m: ADProofs): Try[Unit] = Try {
    require(!historyStorage.contains(m.id), s"Modifier $m is already in history")
    historyStorage.modifierById(m.headerId) match {
      case Some(header: EncryBlockHeader) =>
        require(header.adProofsRoot sameElements m.digest,
          s"Header ADProofs root ${Base58.encode(header.adProofsRoot)} differs from $m digest")
        if(!header.isGenesis && adState) {
          require(typedModifierById[EncryBlockHeader](header.parentId).exists(h => contains(h.adProofsId)),
            s"Trying to apply proofs ${m.encodedId} for header ${header.encodedId}, which parent proofs are empty")
        }
      case _ =>
        throw new Error(s"Header for modifier $m is no defined")
    }
  }
}