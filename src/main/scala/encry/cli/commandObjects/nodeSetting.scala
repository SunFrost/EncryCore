package encry.cli.commandObjects

import scala.util.Try

object nodeEcho extends Command {

  override def execute(args: String) = Try{
    println(args.split("=").toSeq(1))
  }
}
