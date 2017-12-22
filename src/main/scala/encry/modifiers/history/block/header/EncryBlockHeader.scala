package encry.modifiers.history.block.header

import com.google.common.primitives.{Ints, _}
import encry.consensus.Difficulty
import encry.settings.{Algos, Constants}
import encry.consensus.validation.PowConsensusValidator._
import io.circe.Json
import scorex.core.block.Block._
import scorex.core.serialization.Serializer
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.{ModifierId, ModifierTypeId}
import scorex.crypto.hash.Digest32

import scala.util.Try

// TODO: Add generator signature to the header to verify miner`s identity?
case class EncryBlockHeader(override val version: Version,
                            override val parentId: ModifierId,
                            override val txMerkleRoot: Digest32,
                            override val timestamp: Timestamp,
                            override val height: Int,
                            var nonce: Long = 0L,
                            difficulty: Difficulty,
                            generatorProposition: PublicKey25519Proposition) extends EncryBaseBlockHeader {

  override type M = EncryBlockHeader

  override val modifierTypeId: ModifierTypeId = EncryBlockHeader.modifierTypeId

  override lazy val id: ModifierId = ModifierId @@ powHash

  override lazy val headerBytes: Array[Byte] = {
    Bytes.concat(
      Array(version),
      parentId,
      txMerkleRoot,
      Longs.toByteArray(timestamp),
      Ints.toByteArray(height),
      Longs.toByteArray(nonce),
      difficulty.toByteArray,
      generatorProposition.pubKeyBytes
    )
  }

  // TODO: Move POW-related components to the special trait?
  val powHash: Digest32 = Algos.hash(headerBytes)

  val validPow: Boolean = validatePow(powHash, difficulty)

  // Checks whether the block timestamp is less than
  // two hours in the future (7200000ms) (allowing for time errors).
  val validTimestamp: Boolean = (timestamp - System.currentTimeMillis()) < 7200000L

  override def serializer: Serializer[M] = EncryBlockHeaderSerializer

  override def json: Json = ???
}

object EncryBlockHeader {

  val modifierTypeId: ModifierTypeId = ModifierTypeId @@ (101: Byte)

  lazy val GenesisParentId: ModifierId = ModifierId @@ Array.fill(Constants.hashLength)(0: Byte)
}

object EncryBlockHeaderSerializer extends Serializer[EncryBlockHeader] {

  def toBytesWithoutPOW(obj: EncryBlockHeader): Array[Byte] = {
    Bytes.concat(
      Array(obj.version),
      obj.parentId,
      obj.txMerkleRoot,
      Longs.toByteArray(obj.timestamp),
      Ints.toByteArray(obj.height),
      obj.difficulty.toByteArray,
      obj.generatorProposition.pubKeyBytes
    )
  }

  override def toBytes(obj: EncryBlockHeader): Array[Byte] = {
    Bytes.concat(
      toBytesWithoutPOW(obj),
      Longs.toByteArray(obj.nonce)
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[EncryBlockHeader] = ???
}