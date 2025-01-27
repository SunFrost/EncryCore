package encry.network

import HeaderProto.HeaderProtoMessage
import PayloadProto.PayloadProtoMessage
import com.typesafe.scalalogging.StrictLogging
import encry.modifiers.history.{HeaderUtils, PayloadUtils}
import org.encryfoundation.common.modifiers.PersistentModifier
import org.encryfoundation.common.modifiers.history.{Header, HeaderProtoSerializer, Payload, PayloadProtoSerializer}
import org.encryfoundation.common.utils.TaggedTypes.ModifierTypeId
import scala.util.{Failure, Try}

object ModifiersToNetworkUtils extends StrictLogging {

  def toProto(modifier: PersistentModifier): Array[Byte] = modifier match {
    case m: Header   => HeaderProtoSerializer.toProto(m).toByteArray
    case m: Payload  => PayloadProtoSerializer.toProto(m).toByteArray
    case m           => throw new RuntimeException(s"Try to serialize unknown modifier: $m to proto.")
  }

  def fromProto(modType: ModifierTypeId, bytes: Array[Byte]): Try[PersistentModifier] = Try(modType match {
    case Header.modifierTypeId   => HeaderProtoSerializer.fromProto(HeaderProtoMessage.parseFrom(bytes))
    case Payload.modifierTypeId  => PayloadProtoSerializer.fromProto(PayloadProtoMessage.parseFrom(bytes))
    case m                       => Failure(new RuntimeException(s"Try to deserialize unknown modifier: $m from proto."))
  }).flatten

  def isSyntacticallyValid(modifier: PersistentModifier, modifierIdSize: Int): Boolean = modifier  match {
    case h: Header  => HeaderUtils.syntacticallyValidity(h, modifierIdSize).isSuccess
    case p: Payload => PayloadUtils.syntacticallyValidity(p, modifierIdSize).isSuccess
    case _          => true
  }
}