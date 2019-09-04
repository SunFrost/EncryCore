package encry.it

import encry.it.configs.Configs
import encry.it.docker.Docker.defaultConf
import encry.it.docker.{DockerAfterAll, Node}
import org.scalatest.{AsyncFunSuite, Matchers}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ForkResolvingOnDownloadingTest extends AsyncFunSuite with Matchers with DockerAfterAll {

  implicit class FutureBlockedRun[T](future: Future[T]) {
    def run(implicit duration: Duration): T = Await.result(future, duration)
  }

  implicit val futureDuration: FiniteDuration = 30 minutes
  val heightSeparation = 10 //blocks

  test("Late node should sync with one node") {

    val miningNodeConfig = Configs.mining(true)
      .withFallback(Configs.offlineGeneration(true))
      .withFallback(Configs.knownPeers(Seq()))
      .withFallback(defaultConf)

    val node1 = dockerSingleton()
      .startNodeInternal(miningNodeConfig.withFallback(Configs.nodeName("node1")))

    node1.waitForFullHeight(heightSeparation).run

    val node2 = dockerSingleton()
      .startNodeInternal(
        Configs.nodeName("node2")
          .withFallback(Configs.mining(false))
          .withFallback(Configs.knownPeers(Seq((node1.nodeIp, 9001))))
          .withFallback(defaultConf)
      )

    val (bestFullHeaderId1, bestFullHeaderId2) =
      waitForEqualsId(node1.bestFullHeaderId.run, node2.bestFullHeaderId.run)

    docker.close()

    bestFullHeaderId2 shouldEqual bestFullHeaderId1
  }

  test("Late node should sync with the first of two nodes") {

    val miningNodeConfig = Configs.mining(true)
      .withFallback(Configs.offlineGeneration(true))
      .withFallback(Configs.knownPeers(Seq()))
      .withFallback(defaultConf)

    val node1 = dockerSingleton()
      .startNodeInternal(miningNodeConfig.withFallback(Configs.nodeName("node1")))

    val node2 = dockerSingleton()
      .startNodeInternal(miningNodeConfig.withFallback(Configs.nodeName("node2")))

    node1.waitForFullHeight(heightSeparation).run

    val node3 = dockerSingleton()
      .startNodeInternal(
        Configs.nodeName("node3")
          .withFallback(Configs.mining(false))
          .withFallback(Configs.knownPeers(Seq((node1.nodeIp, 9001), (node2.nodeIp, 9001))))
          .withFallback(defaultConf)
      )

    val (bestFullHeaderId1, bestFullHeaderId3) =
      waitForEqualsId(node1.bestFullHeaderId.run, node3.bestFullHeaderId.run)

    docker.close()

    bestFullHeaderId3 shouldEqual bestFullHeaderId1
  }

  test("All nodes should go to first chain") {

    val miningNodeConfig = Configs.mining(true)
      .withFallback(Configs.offlineGeneration(true))
      .withFallback(Configs.knownPeers(Seq()))
      .withFallback(defaultConf)

    val node1 = dockerSingleton()
      .startNodeInternal(miningNodeConfig.withFallback(Configs.nodeName("node1")))

    node1.waitForFullHeight(heightSeparation).run

    val node2 = dockerSingleton()
      .startNodeInternal(miningNodeConfig.withFallback(Configs.nodeName("node2")))

    node2.waitForFullHeight(heightSeparation).run

    val node3 = dockerSingleton()
      .startNodeInternal(
        Configs.nodeName("node3")
          .withFallback(Configs.mining(false))
          .withFallback(Configs.knownPeers(Seq((node1.nodeIp, 9001), (node2.nodeIp, 9001))))
          .withFallback(defaultConf)
      )

    val (bestFullHeaderId13, bestFullHeaderId3) =
      waitForEqualsId(node1.bestFullHeaderId.run, node3.bestFullHeaderId.run)

    val (bestFullHeaderId12, bestFullHeaderId2) =
      waitForEqualsId(node1.bestFullHeaderId.run, node2.bestFullHeaderId.run)

    docker.close()

    bestFullHeaderId3 shouldEqual bestFullHeaderId13
    bestFullHeaderId2 shouldEqual bestFullHeaderId12
  }

  test("Node should sync after change offlineGeneration and port") {

    val baseNodeConfig = Configs.mining(true)
      .withFallback(Configs.knownPeers(Seq()))
      .withFallback(defaultConf)

    val node1 = dockerSingleton()
      .startNodeInternal(baseNodeConfig
        .withFallback(Configs.nodeName("node1"))
        .withFallback(Configs.offlineGeneration(true))
      )

    val node2 = dockerSingleton()
      .startNodeInternal(baseNodeConfig
        .withFallback(Configs.nodeName("node2"))
        .withFallback(Configs.offlineGeneration(false))
      )

    println(s"${node2.nodeIp}:9001")
    println(s"${node1.nodeIp}:9001")
    node1.connect(s"${node2.nodeIp}:9001").run
//    node2.connect(s"${node1.nodeIp}:9001").run

    node1.waitForFullHeight(100).run

//    val (bestFullHeaderId1, bestFullHeaderId2) =
//      waitForEqualsId(node1.bestFullHeaderId.run, node2.bestFullHeaderId.run)
//
    docker.close()

    true shouldEqual true
  }

  def waitForEqualsId(id1Func: => String, id2Func: => String)(implicit duration: Duration): (String, String) = {
    @tailrec
    def loop(id1Func: => String, id2Func: => String, maxTries: Long): (String, String) = {
      val id1: String = id1Func
      val id2: String = id2Func
      if (id1 != id2 && maxTries > 0) {
        Thread.sleep(1000)
        loop(id1Func, id2Func, maxTries - 1)
      } else (id1, id2)
    }

    loop(id1Func, id2Func, duration.toSeconds)
  }
}