package encry.cli.commands

import akka.pattern._
import akka.util.Timeout
import encry.cli.Response
import encry.settings.EncryAppSettings
import encry.view.history.History
import encry.view.wallet.EncryWallet
import encry.EncryApp._
import encry.view.actors.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import encry.view.state.UtxoState
import org.encryfoundation.common.utils.Algos
import scala.concurrent.Future

object PrintPubKeys extends Command {

  override def execute(args: Command.Args, settings: EncryAppSettings): Future[Option[Response]] = {
    implicit val timeout: Timeout = Timeout(settings.restApi.timeout)
    (nodeViewHolder ?
      GetDataFromCurrentView[History, UtxoState, EncryWallet, Option[Response]] { view =>
        Some(Response(view.vault.publicKeys.foldLeft("")((str, k) => str + Algos.encode(k.pubKeyBytes) + "\n")))
      }).mapTo[Option[Response]]
  }
}
