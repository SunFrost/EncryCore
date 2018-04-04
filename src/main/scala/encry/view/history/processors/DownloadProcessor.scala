package encry.view.history.processors

import encry.modifiers.EncryPersistentModifier
import encry.modifiers.history.ADProofs
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.history.block.header.EncryBlockHeader
import encry.modifiers.history.block.payload.EncryBlockPayload
import encry.settings.{Constants, NodeSettings}
import encry.view.history.Height
import encry.view.history.storage.FullBlockDownloadProcessor
import scorex.core.{ModifierId, ModifierTypeId}
import scorex.core.utils.{NetworkTimeProvider, ScorexLogging}

trait DownloadProcessor extends ScorexLogging {

  protected val nodeSettings: NodeSettings

  protected val timeProvider: NetworkTimeProvider

  protected[history] lazy val FBDProcessor: FullBlockDownloadProcessor = FullBlockDownloadProcessor(nodeSettings)

  private var isHeadersChainSyncedVar: Boolean = false

  def bestBlockOpt: Option[EncryBlock]

  def bestBlockIdOpt: Option[ModifierId]

  def typedModifierById[T <: EncryPersistentModifier](id: ModifierId): Option[T]

  def contains(id: ModifierId): Boolean

  def headerIdsAtHeight(height: Int): Seq[ModifierId]

  def isHeadersChainSynced: Boolean = isHeadersChainSyncedVar

  def modifiersToDownload(howMany: Int, excluding: Iterable[ModifierId]): Seq[(ModifierTypeId, ModifierId)] = {
    def contitution(height: Height, acc: Seq[(ModifierTypeId, ModifierId)]): Seq[(ModifierTypeId, ModifierId)] = {
      if (acc.lengthCompare(howMany) >= 0) acc
      else {
        headerIdsAtHeight(height).headOption.flatMap(id => typedModifierById[EncryBlockHeader](id)) match {
          case Some(bestHeaderAtThisHeight) =>
            val toDownload = requiredModifiersForHeader(bestHeaderAtThisHeight)
              .filter(m => !excluding.exists(ex => ex sameElements m._2))
              .filter(m => !contains(m._2))
            contitution(Height @@ (height + 1), acc ++ toDownload)
          case None => acc
        }
      }
    }

    bestBlockOpt match {
      case _ if !isHeadersChainSynced => Seq.empty
      case Some(fb) => contitution(Height @@ (fb.header.height + 1), Seq.empty)
      case None => contitution(FBDProcessor.minimalHeightOfBlock, Seq.empty)
    }
  }

  protected def toDownload(header: EncryBlockHeader): Seq[(ModifierTypeId, ModifierId)] = {
    if (!nodeSettings.verifyTransactions){
      Seq.empty
    } else if (header.height >= FBDProcessor.minimalHeightOfBlock) requiredModifiersForHeader(header)
    else if (!isHeadersChainSynced && isNewHeader(header)) {
      log.info(s"Headers chain is synced after header ${header.encodedId} at height ${header.height}")
      isHeadersChainSyncedVar = true
      FBDProcessor.setMinimalHeightOfBlock(header)
      Seq.empty
    } else Seq.empty
  }

  private def requiredModifiersForHeader(header: EncryBlockHeader): Seq[(ModifierTypeId, ModifierId)] =
    if (!nodeSettings.verifyTransactions) Seq.empty
    else Seq((EncryBlockPayload.modifierTypeId, header.id), (ADProofs.modifierTypeId, header.adProofsId))

  private def isNewHeader(header: EncryBlockHeader): Boolean = {
    // TODO: Magic Number
    timeProvider.time() - header.timestamp < Constants.Chain.desiredBlockInterval.toMillis * 3
  }
}