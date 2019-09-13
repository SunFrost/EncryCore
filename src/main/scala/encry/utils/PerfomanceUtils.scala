package encry.utils

object PerfomanceUtils {

  private var timeMap: Map[String, Long] = Map()
  private var sumMap: Map[String, Long] = Map()

  def time[T](label: String, body: => T): T = {
    val start = System.nanoTime()
    val result = body
    sumMap += label -> (System.nanoTime() - start)
    result
  }

  def start(label: String) {
    timeMap += label -> System.nanoTime()
  }

  def finish(label: String) {
    timeMap.get(label).foreach { start =>
      sumMap += label -> (System.nanoTime() - start)
      timeMap -= label
    }
  }

  def printTimes() {
    sumMap.map(e => f"Time ${e._1}: ${e._2/1000000.0}%.3fms").foreach(println)
  }

}
